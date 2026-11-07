package com.kitt.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
}

class KittService : Service(), TextToSpeech.OnInitListener {
    companion object {
        @Volatile var ui: KittUi? = null
        @Volatile var lastStatus: String = "Avvio..."
        @Volatile var appVisible = false
        @Volatile var visibilityCallback: (() -> Unit)? = null
        const val ACTION_STOP = "com.kitt.app.STOP"
        const val ACTION_WAKE = "com.kitt.app.WAKE"
        const val ACTION_TEST = "com.kitt.app.TEST"
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

    private var voiceStarted = false
    private var busy = false
    private var wakeModel: Model? = null
    private var wakeError = ""

    private var bubble: LinearLayout? = null
    private var bubbleScanner: ScannerView? = null
    private var bubbleX = 0
    private var bubbleY = 0
    private var dismissed = false
    private var serviceActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        bubbleX = dp(16)
        bubbleY = dp(140)
        brain = Brain(this)
        commands = Commands(this)
        voice = VoiceListener(
            { pcm -> onUtterance(pcm) },
            { msg -> setStatus(msg) },
            { onWake() },
            { onSleep() }
        )
        tts = TextToSpeech(this, this)
        visibilityCallback = { handler.post { updateBubble() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceActive = false
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
        serviceActive = true
        updateBubble()
        if (intent?.action == ACTION_WAKE) {
            voice.keepAwake(10000)
            onWake()
        }
        if (intent?.action == ACTION_TEST) {
            testVoice()
        }
        begin()
        return START_STICKY
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
            .setContentText("Di' \"Hey KITT\" per parlare")
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

    // ---------- bolla sullo schermo ----------

    private fun updateBubble() {
        val should = serviceActive && !dismissed && !appVisible && Settings.canDrawOverlays(this)
        if (should) showBubble() else removeBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(10), dp(8), dp(10), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#E6000000"))
        bg.cornerRadius = dp(14).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#B00000"))
        box.background = bg

        val sc = ScannerView(this)
        box.addView(sc, LinearLayout.LayoutParams(dp(130), dp(22)))

        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 18f
        close.setPadding(dp(12), 0, dp(4), 0)
        close.visibility = View.GONE
        box.addView(close)
        close.setOnClickListener {
            dismissed = true
            updateBubble()
            setStatus("Bolla chiusa. Di' \"Hey KITT\" per riaprirla")
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
                        if (close.visibility == View.VISIBLE) {
                            close.visibility = View.GONE
                        } else {
                            close.visibility = View.VISIBLE
                            handler.postDelayed({ close.visibility = View.GONE }, 4000)
                        }
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
            sc.start(2800L)
        } catch (e: Exception) {
            bubble = null
            bubbleScanner = null
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
            bubbleScanner?.start(if (on) 700L else 2800L)
        }
    }

    // ---------- ascolto ----------

    private fun begin() {
        if (voiceStarted) return
        if (!brain.hasKey()) {
            setStatus("Tieni premuto lo schermo per inserire la chiave Groq")
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
                setStatus("In attesa: di' \"Hey KITT\"")
            } else {
                setStatus("Ascolto sempre attivo. " + wakeError)
            }
        }.start()
    }

    private fun prepareWake() {
        val modelDir = File(filesDir, "wake/vosk-model-small-en-us-0.15")
        val okFile = File(filesDir, "wake/ok")
        try {
            if (!okFile.exists()) {
                setStatus("Scarico il modello per 'Hey KITT' (circa 40 MB), attendi...")
                File(filesDir, "wake").deleteRecursively()
                val url = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
                val conn = url.openConnection()
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                ZipInputStream(conn.getInputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(filesDir, "wake/" + entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
                okFile.writeText("ok")
            }
            val model = Model(modelDir.absolutePath)
            wakeModel = model
            voice.wake = Recognizer(model, 16000.0f)
        } catch (e: Exception) {
            wakeError = "Parola di attivazione non disponibile: " + e.message
        }
    }

    private fun onWake() {
        setStatus("Ti ascolto...")
        handler.post {
            dismissed = false
            updateBubble()
        }
        scan(true)
        handler.postDelayed({ if (!busy) scan(false) }, 1500)
    }

    private fun onSleep() {
        setStatus("In attesa: di' \"Hey KITT\"")
    }

    private fun resumeListening() {
        handler.postDelayed({
            voice.keepAwake(10000)
            voice.paused = false
        }, 500)
    }

    private fun finish(message: String?) {
        busy = false
        scan(false)
        setStatus(message ?: "Ti ascolto...")
        resumeListening()
    }

    private fun onUtterance(pcm: ByteArray) {
        busy = true
        voice.paused = true
        setStatus("Elaboro...")
        scan(true)
        Thread {
            val (text, error) = brain.transcribe(pcm)
            if (error.isNotBlank()) {
                finish(error)
                return@Thread
            }
            if (text.length < 2 || text.contains("assistente vocale dell", ignoreCase = true)) {
                finish(null)
                return@Thread
            }
            setStatus("Hai detto: $text")
            val r = brain.think(text)
            handler.post {
                val failure = commands.run(r.action, r.arg)
                val said = failure ?: r.reply
                setStatus(said)
                speak(said)
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
                    "). Se non senti nulla, tocca di nuovo la scritta K.I.T.T."
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
                abandonFocus()
                busy = false
                scan(false)
                resumeListening()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonFocus()
                busy = false
                scan(false)
                resumeListening()
            }
        })
        setStatus("Voce pronta (" + name + "). Tocca la scritta K.I.T.T. per provare i canali audio")
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
            val a = attrs(route())
            tts?.setAudioAttributes(a)
            requestFocus(a)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitt")
        } else {
            busy = false
            scan(false)
            resumeListening()
        }
    }

    override fun onDestroy() {
        visibilityCallback = null
        removeBubble()
        abandonFocus()
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