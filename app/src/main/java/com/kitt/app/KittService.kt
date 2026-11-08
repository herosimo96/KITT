package com.kitt.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

interface KittUi {
    fun status(text: String)
    fun scan(on: Boolean)
    fun message(fromUser: Boolean, text: String)
}

class KittService : Service(), TextToSpeech.OnInitListener {
    companion object {
        @Volatile var ui: KittUi? = null
        @Volatile var lastStatus: String = "Avvio..."
        @Volatile var appVisible = false
        @Volatile var visibilityCallback: (() -> Unit)? = null
        val chat: MutableList<Pair<Boolean, String>> =
            java.util.Collections.synchronizedList(ArrayList<Pair<Boolean, String>>())
        const val ACTION_STOP = "com.kitt.app.STOP"
        const val ACTION_WAKE = "com.kitt.app.WAKE"
        const val ACTION_TEST = "com.kitt.app.TEST"
        const val ACTION_BOOT = "com.kitt.app.BOOT"
        const val ACTION_TEXT = "com.kitt.app.TEXT"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "kitt"
        private const val NOTIF_ID = 1
        private const val ESPEAK = "com.reecedunn.espeak"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var brain: Brain
    private lateinit var commands: Commands
    private lateinit var voice: VoiceListener

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsProblem = ""
    private var engines: List<String> = emptyList()
    private val triedEngines = mutableSetOf<String>()
    private var currentEngine = "predefinito"
    private val ttsLog = StringBuilder()
    private var focusRequest: AudioFocusRequest? = null
    private val routeNames = listOf("media", "navigazione", "assistente", "notifica", "sveglia", "chiamata")
    private var greetText: String? = null

    private var voiceStarted = false
    private var busySince = 0L
    private var busy = false
        set(value) {
            field = value
            if (value) busySince = System.currentTimeMillis()
        }
    private var userPaused = false
    private var stoppedByUser = false
    private var watchdogStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeModel: Model? = null
    private var wakeError = ""

    private var bubble: LinearLayout? = null
    private var bubbleScanner: ScannerView? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubblePause: TextView? = null
    private var bubbleClose: TextView? = null
    private var controlsVisible = false
    private var bubbleX = 0
    private var bubbleY = 0
    private var dismissed = false
    private var serviceActive = false

    private val hideControls = Runnable {
        controlsVisible = false
        refreshBubble()
    }

    private val speakGuard = Runnable {
        if (busy) {
            busy = false
            abandonFocus()
            scan(false)
            resumeListening()
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                checkHealth()
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 20000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        bubbleX = dp(16)
        bubbleY = dp(140)
        brain = Brain(this)
        commands = Commands(this)
        voice = VoiceListener(
            { pcm, fromWake -> onUtterance(pcm, fromWake) },
            { msg -> setStatus(msg) },
            { onWake() },
            { onSleep() }
        )
        tts = TextToSpeech(this, this)
        visibilityCallback = { handler.post { updateBubble() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stoppedByUser = true
            serviceActive = false
            handler.removeCallbacks(watchdog)
            removeBubble()
            setStatus("KITT spento")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startInForeground()
        } catch (e: Exception) {
            setStatus("Concedi il permesso del microfono a KITT")
            stopSelf()
            return START_NOT_STICKY
        }
        stoppedByUser = false
        serviceActive = true
        acquireLocks()
        if (!watchdogStarted) {
            watchdogStarted = true
            handler.postDelayed(watchdog, 20000)
        }
        updateBubble()
        when (action) {
            ACTION_WAKE -> resumeKitt()
            ACTION_TEST -> testVoice()
            ACTION_TEXT -> {
                val t = intent?.getStringExtra(EXTRA_TEXT)
                if (!t.isNullOrBlank()) handleText(t)
            }
            ACTION_BOOT -> maybeGreet(true)
            else -> maybeGreet(false)
        }
        begin()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!stoppedByUser) {
            try {
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getForegroundService(
                    this, 2, Intent(this, KittService::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000, pi)
            } catch (e: Exception) {
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startInForeground() {
        val channel = NotificationChannel(CHANNEL, "KITT", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KittService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("K.I.T.T. attivo")
            .setContentText("Di' \"Ciao KITT\" per parlare")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(0, "Spegni", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- tenere KITT sveglio ----------

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kitt:listen")
                wl.setReferenceCounted(false)
                wakeLock = wl
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (e: Exception) {
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val wl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kitt:wifi")
                wl.setReferenceCounted(false)
                wifiLock = wl
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
    }

    private fun checkHealth() {
        if (!serviceActive) return
        acquireLocks()
        val now = System.currentTimeMillis()
        if (busy && now - busySince > 45000) {
            busy = false
            abandonFocus()
            scan(false)
            resumeListening()
        }
        if (!busy && voice.paused) {
            voice.paused = false
        }
        if (voiceStarted && !voice.isAlive()) {
            voice.start()
            setStatus(idleStatus())
        }
    }

    // ---------- chat ----------

    private fun addChat(fromUser: Boolean, text: String) {
        synchronized(chat) {
            chat.add(Pair(fromUser, text))
            while (chat.size > 200) chat.removeAt(0)
        }
        handler.post { ui?.message(fromUser, text) }
    }

    private fun idleStatus(): String =
        if (!voiceStarted) "Pronto"
        else if (userPaused) "In pausa. Di' \"Ciao KITT\" o tocca PLAY"
        else if (voice.wake != null) "In attesa: di' \"Ciao KITT\""
        else "Ti ascolto..."

    private fun handleText(text: String) {
        addChat(true, text)
        if (!brain.hasKey()) {
            addChat(false, "Manca la chiave Groq: inseriscila in Impostazioni.")
            return
        }
        setStatus("KITT sta pensando...")
        scan(true)
        Thread {
            try {
                val r = brain.think(text)
                handler.post {
                    val failure = commands.run(r.action, r.arg)
                    val said = failure ?: r.reply
                    if (said.isNotBlank()) addChat(false, said)
                    if (KittTheme.speakTyped(this) && ttsReady && said.isNotBlank()) {
                        speak(said)
                    } else {
                        scan(false)
                        setStatus(idleStatus())
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    scan(false)
                    setStatus("Errore: " + e.message)
                }
            }
        }.start()
    }

    // ---------- saluto di benvenuto ----------

    private fun maybeGreet(force: Boolean) {
        if (!KittTheme.greet(this)) return
        val prefs = getSharedPreferences("kitt", MODE_PRIVATE)
        val last = prefs.getLong("last_greet", 0L)
        val now = System.currentTimeMillis()
        if (!force && now - last < 6 * 3600 * 1000L) return
        prefs.edit().putLong("last_greet", now).apply()
        val text = "Ciao " + KittTheme.userName(this) + ", benvenuto a bordo."
        dismissed = false
        updateBubble()
        addChat(false, text)
        greetText = text
        trySpeakGreeting()
    }

    private fun trySpeakGreeting() {
        val g = greetText ?: return
        if (!ttsReady) return
        greetText = null
        speak(g)
    }

    // ---------- pausa e ripresa ----------

    private fun pauseKitt() {
        userPaused = true
        handler.removeCallbacks(speakGuard)
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        abandonFocus()
        busy = false
        voice.keepAwake(0)
        voice.paused = false
        setStatus("In pausa. Di' \"Ciao KITT\" o tocca PLAY per riattivarmi")
        handler.post {
            refreshBubble()
            bubbleScannerState()
        }
    }

    private fun resumeKitt() {
        userPaused = false
        voice.keepAwake(10000)
        handler.post {
            refreshBubble()
            bubbleScannerState()
        }
        onWake()
    }

    // ---------- bolla sullo schermo ----------

    private fun updateBubble() {
        val should = serviceActive && !dismissed && !appVisible && Settings.canDrawOverlays(this)
        if (should) showBubble() else removeBubble()
        applyBubbleTheme()
        refreshBubble()
    }

    private fun applyBubbleTheme() {
        val c = KittTheme.scanner(this)
        bubbleScanner?.color = c
        bubbleBg?.setStroke(dp(1), c)
        bubblePause?.setTextColor(c)
    }

    private fun refreshBubble() {
        bubblePause?.text = if (userPaused) "PLAY" else "STOP"
        bubblePause?.visibility = if (controlsVisible || userPaused) View.VISIBLE else View.GONE
        bubbleClose?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
    }

    private fun bubbleScannerState() {
        val s = bubbleScanner ?: return
        if (userPaused) s.stop() else s.start(if (busy) 700L else 2800L)
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val accent = KittTheme.scanner(this)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(10), dp(8), dp(10), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#E6000000"))
        bg.cornerRadius = dp(16).toFloat()
        bg.setStroke(dp(1), accent)
        box.background = bg

        val sc = ScannerView(this)
        sc.color = accent
        box.addView(sc, LinearLayout.LayoutParams(dp(120), dp(22)))

        val pauseBtn = TextView(this)
        pauseBtn.text = "STOP"
        pauseBtn.setTextColor(accent)
        pauseBtn.textSize = 12f
        pauseBtn.typeface = Typeface.DEFAULT_BOLD
        pauseBtn.setPadding(dp(14), dp(6), dp(6), dp(6))
        pauseBtn.visibility = View.GONE
        box.addView(pauseBtn)
        pauseBtn.setOnClickListener {
            if (userPaused) resumeKitt() else pauseKitt()
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, 4000)
        }

        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 18f
        close.setPadding(dp(10), 0, dp(4), 0)
        close.visibility = View.GONE
        box.addView(close)
        close.setOnClickListener {
            dismissed = true
            controlsVisible = false
            updateBubble()
            setStatus("Bolla chiusa. Di' \"Ciao KITT\" per riaprirla")
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = bubbleX
        lp.y = bubbleY

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        bubbleX = lp.x
                        bubbleY = lp.y
                        try {
                            wm.updateViewLayout(box, lp)
                        } catch (ex: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        controlsVisible = !controlsVisible
                        refreshBubble()
                        handler.removeCallbacks(hideControls)
                        if (controlsVisible) handler.postDelayed(hideControls, 4000)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(box, lp)
            bubble = box
            bubbleScanner = sc
            bubbleBg = bg
            bubblePause = pauseBtn
            bubbleClose = close
            bubbleScannerState()
        } catch (e: Exception) {
            bubble = null
            bubbleScanner = null
            bubbleBg = null
            bubblePause = null
            bubbleClose = null
        }
    }

    private fun removeBubble() {
        val b = bubble ?: return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(b)
        } catch (e: Exception) {
        }
        bubble = null
        bubbleScanner = null
        bubbleBg = null
        bubblePause = null
        bubbleClose = null
    }

    // ---------- stato e scanner ----------

    private fun setStatus(text: String) {
        val full = if (ttsProblem.isNotBlank()) text + "\n" + ttsProblem else text
        lastStatus = full
        handler.post { ui?.status(full) }
    }

    private fun scan(on: Boolean) {
        handler.post {
            ui?.scan(on)
            val s = bubbleScanner
            if (s != null) {
                if (on) s.start(700L) else if (userPaused) s.stop() else s.start(2800L)
            }
        }
    }

    // ---------- ascolto ----------

    private fun begin() {
        if (voiceStarted) return
        if (!brain.hasKey()) {
            setStatus("Apri Impostazioni e inserisci la chiave Groq")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Concedi il permesso del microfono a KITT")
            return
        }
        voiceStarted = true
        setStatus("Preparo l'ascolto...")
        Thread {
            prepareWake()
            voice.paused = false
            voice.start()
            if (voice.wake != null) {
                setStatus("In attesa: di' \"Ciao KITT\"")
            } else {
                setStatus("Ascolto sempre attivo. " + wakeError)
            }
        }.start()
    }

    private fun prepareWake() {
        val wakeDir = File(filesDir, "wake-it-0.22")
        val modelDir = File(wakeDir, "vosk-model-small-it-0.22")
        val okFile = File(wakeDir, "ok")

        try {
            if (!okFile.exists() || !File(modelDir, "am").exists()) {
                wakeDir.deleteRecursively()
                wakeDir.mkdirs()

                setStatus("Scarico il modello vocale italiano (circa 48 MB)...")

                val url = URL(
                    "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
                )
                val conn = url.openConnection()
                conn.connectTimeout = 20000
                conn.readTimeout = 60000

                ZipInputStream(conn.getInputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(wakeDir, entry.name)
                        val root = wakeDir.canonicalFile
                        val target = out.canonicalFile
                        if (!target.path.startsWith(root.path + File.separator) && target != root) {
                            throw SecurityException("Percorso non valido nel modello vocale")
                        }

                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { output ->
                                zip.copyTo(output)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                if (!File(modelDir, "am").exists()) {
                    throw Exception("Il modello italiano scaricato è incompleto")
                }
                okFile.writeText("ok")
            }

            val model = Model(modelDir.absolutePath)
            wakeModel = model
            voice.wake = Recognizer(model, 16000.0f)
            wakeError = ""
            setStatus("Modello vocale italiano caricato")
        } catch (e: Exception) {
            wakeError = "Modello vocale italiano non disponibile: ${e.message}"
            setStatus(wakeError)
        }
    }

    private fun onWake() {
        if (userPaused) {
            userPaused = false
            handler.post {
                refreshBubble()
                bubbleScannerState()
            }
        }
        setStatus("Ti ascolto...")
        handler.post {
            dismissed = false
            updateBubble()
        }
        scan(true)
        handler.postDelayed({ if (!busy) scan(false) }, 1500)
    }

    private fun onSleep() {
        setStatus(idleStatus())
    }

    private fun resumeListening() {
        handler.postDelayed({
            if (!userPaused) voice.keepAwake(10000)
            voice.paused = false
        }, 500)
    }

    private fun finish(message: String?) {
        busy = false
        scan(false)
        setStatus(message ?: "Ti ascolto...")
        resumeListening()
    }

    private fun falseAlarm() {
        busy = false
        scan(false)
        setStatus(idleStatus())
        handler.postDelayed({
            voice.keepAwake(0)
            voice.paused = false
        }, 300)
    }

    private fun looksLikeWake(text: String): Boolean {
        val t = text.lowercase()
        return listOf("kit", "chit", "ciao", "buongiorno", "buonasera", "hey", "ehi").any { t.contains(it) }
    }

    private fun onUtterance(pcm: ByteArray, fromWake: Boolean) {
        busy = true
        voice.paused = true
        setStatus("Elaboro...")
        scan(true)
        Thread {
            try {
                val (text, error) = brain.transcribe(pcm)
                if (error.isNotBlank()) {
                    finish(error)
                    return@Thread
                }
                if (text.length < 2 || text.contains("assistente vocale dell", ignoreCase = true)) {
                    if (fromWake) falseAlarm() else finish(null)
                    return@Thread
                }
                if (fromWake && !looksLikeWake(text)) {
                    falseAlarm()
                    return@Thread
                }
                addChat(true, text)
                setStatus("Hai detto: $text")
                val r = brain.think(text)
                handler.post {
                    if (userPaused) {
                        busy = false
                        scan(false)
                        return@post
                    }
                    val failure = commands.run(r.action, r.arg)
                    val said = failure ?: r.reply
                    setStatus(said)
                    if (said.isNotBlank()) addChat(false, said)
                    speak(said)
                }
            } catch (e: Exception) {
                finish("Errore: " + e.message)
            }
        }.start()
    }

    // ---------- voce di KITT ----------

    private fun route(): Int =
        getSharedPreferences("kitt", MODE_PRIVATE).getInt("tts_route", 0)

    private fun attrs(route: Int): AudioAttributes {
        val usage = when (route) {
            1 -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            2 -> AudioAttributes.USAGE_ASSISTANT
            3 -> AudioAttributes.USAGE_NOTIFICATION
            4 -> AudioAttributes.USAGE_ALARM
            5 -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            else -> AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun requestFocus(a: AudioAttributes) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(a)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } catch (e: Exception) {
        }
    }

    private fun abandonFocus() {
        try {
            val req = focusRequest ?: return
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(req)
        } catch (e: Exception) {
        }
        focusRequest = null
    }

    private fun testVoice() {
        val next = (route() + 1) % routeNames.size
        getSharedPreferences("kitt", MODE_PRIVATE).edit().putInt("tts_route", next).apply()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        setStatus(
            "Canale voce: " + routeNames[next] + " (volume media " + vol + "/" + max +
                    "). Per cambiare canale premi di nuovo Prova voce in Impostazioni."
        )
        speak("Prova voce, canale " + routeNames[next])
    }

    override fun onInit(initStatus: Int) {
        val t = tts ?: return
        if (engines.isEmpty()) {
            try {
                engines = t.engines.map { it.name }
            } catch (e: Exception) {
            }
        }
        val name = if (currentEngine == "predefinito") {
            try {
                t.defaultEngine ?: "predefinito"
            } catch (e: Exception) {
                "predefinito"
            }
        } else {
            currentEngine
        }
        currentEngine = name
        triedEngines.add(name)

        if (initStatus != TextToSpeech.SUCCESS) {
            ttsLog.append("[" + name + ": non si avvia] ")
            nextEngine()
            return
        }

        var best = TextToSpeech.LANG_NOT_SUPPORTED
        for (loc in listOf(Locale.ITALIAN, Locale.ITALY, Locale("it", "IT"))) {
            val r = t.setLanguage(loc)
            if (r > best) best = r
            if (r >= TextToSpeech.LANG_AVAILABLE) break
        }
        if (best < TextToSpeech.LANG_AVAILABLE) {
            ttsLog.append("[" + name + ": italiano codice " + best + "] ")
            nextEngine()
            return
        }

        ttsReady = true
        ttsProblem = ""
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                voice.paused = true
                scan(true)
            }
            override fun onDone(utteranceId: String?) {
                handler.removeCallbacks(speakGuard)
                abandonFocus()
                busy = false
                scan(false)
                resumeListening()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.removeCallbacks(speakGuard)
                abandonFocus()
                busy = false
                scan(false)
                resumeListening()
            }
        })
        setStatus("Voce pronta (" + name + ")")
        trySpeakGreeting()
    }

    private fun nextEngine() {
        val next = engines
            .filter { it !in triedEngines }
            .sortedBy { if (it == ESPEAK) 0 else 1 }
            .firstOrNull()
        if (next == null) {
            val list = if (engines.isEmpty()) "nessuno" else engines.joinToString(", ")
            ttsProblem = "Voce: italiano non disponibile. Motori trovati: " + list + ". " + ttsLog
            setStatus("Nessuna voce italiana")
            return
        }
        currentEngine = next
        tts?.shutdown()
        tts = TextToSpeech(this, this, next)
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            busy = true
            val a = attrs(route())
            tts?.setAudioAttributes(a)
            requestFocus(a)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitt")
            handler.removeCallbacks(speakGuard)
            handler.postDelayed(speakGuard, 8000L + text.length * 100L)
        } else {
            busy = false
            scan(false)
            resumeListening()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(speakGuard)
        handler.removeCallbacks(hideControls)
        visibilityCallback = null
        removeBubble()
        abandonFocus()
        releaseLocks()
        voice.stop()
        try {
            voice.wake?.close()
            wakeModel?.close()
        } catch (e: Exception) {
        }
        tts?.shutdown()
        super.onDestroy()
    }
}