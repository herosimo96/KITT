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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

interface KittUi {
    fun status(text: String)
    fun scan(on: Boolean)
    fun message(fromUser: Boolean, text: String)
}

class KittService : Service() {

    companion object {
        @Volatile var ui: KittUi? = null
        @Volatile var lastStatus = "Avvio..."
        @Volatile var appVisible = false
        @Volatile var visibilityCallback: (() -> Unit)? = null

        val chat: MutableList<Pair<Boolean, String>> =
            java.util.Collections.synchronizedList(
                ArrayList<Pair<Boolean, String>>()
            )

        const val ACTION_STOP = "com.kitt.app.STOP"
        const val ACTION_WAKE = "com.kitt.app.WAKE"
        const val ACTION_TEST = "com.kitt.app.TEST"
        const val ACTION_BOOT = "com.kitt.app.BOOT"
        const val ACTION_TEXT = "com.kitt.app.TEXT"
        const val ACTION_TTS = "com.kitt.app.TTS"
        const val ACTION_SAMPLE = "com.kitt.app.SAMPLE"

        const val EXTRA_TEXT = "text"

        private const val CHANNEL = "kitt"
        private const val NOTIF_ID = 1
    }

    private lateinit var commands: Commands

    @Volatile
    private var gemini: GeminiLiveClient? = null

    @Volatile
    private var serviceActive = false

    @Volatile
    private var userPaused = false

    @Volatile
    private var stoppedByUser = false

    private var bubble: LinearLayout? = null
    private var scanner: ScannerView? = null
    private var bubblePause: TextView? = null
    private var bubbleClose: TextView? = null
    private var bubbleBg: GradientDrawable? = null

    private var controlsVisible = false
    private var dismissed = false
    private var bubbleX = 20
    private var bubbleY = 160

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        commands = Commands(this)
        visibilityCallback = {
            android.os.Handler(mainLooper).post {
                updateBubble()
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action = intent?.action

        if (action == ACTION_STOP) {
            stoppedByUser = true
            stopKitt()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startInForeground()
        } catch (e: Exception) {
            setStatus("Impossibile avviare KITT. Controlla i permessi.")
            stopSelf()
            return START_NOT_STICKY
        }

        stoppedByUser = false
        serviceActive = true

        updateBubble()

        when (action) {
            ACTION_WAKE -> {
                userPaused = false
                if (gemini == null) connectGemini()
                else setStatus("KITT è già in ascolto.")
            }

            ACTION_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    sendText(text)
                }
                if (gemini == null) connectGemini()
            }

            ACTION_TEST,
            ACTION_TTS,
            ACTION_SAMPLE -> {
                setStatus("La voce di KITT è gestita da Gemini Live.")
                if (gemini == null) connectGemini()
            }

            else -> {
                if (gemini == null) connectGemini()
            }
        }

        return START_STICKY
    }

    private fun startInForeground() {
        val manager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL,
            "KITT",
            NotificationManager.IMPORTANCE_LOW
        )

        manager.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KittService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("K.I.T.T. attivo")
            .setContentText("Gemini Live attivo in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(0, "Spegni", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun connectGemini() {
        if (gemini != null) return

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Concedi a KITT il permesso del microfono.")
            return
        }

        val prefs = getSharedPreferences("kitt", MODE_PRIVATE)
        val key = prefs.getString("gemini_key", "") ?: ""

        if (key.isBlank()) {
            setStatus("Inserisci la chiave API Gemini nelle Impostazioni.")
            return
        }

        userPaused = false
        setStatus("Collegamento a Gemini Live...")

        val session = GeminiLiveClient(
            apiKey = key,

            onStatus = { message ->
                setStatus(message)
            },

            onUserText = { text ->
                if (text.isNotBlank()) {
                    addChat(true, text)
                }
            },

            onAssistantText = { text ->
                if (text.isNotBlank()) {
                    addChat(false, text)
                }
            },

            onToolCall = { action, arg ->
                commands.run(action, arg)
            },

            onClosed = { message ->
                gemini = null
                setStatus(message)
                scan(false)
            }
        )

        gemini = session
        session.start()
    }

    private fun sendText(text: String) {
        if (text.isBlank()) return

        addChat(true, text)

        val current = gemini

        if (current == null) {
            setStatus("Connessione a Gemini Live...")
            connectGemini()

            android.os.Handler(mainLooper).postDelayed({
                gemini?.sendText(text)
            }, 2500)

            return
        }

        current.sendText(text)
        setStatus("Messaggio inviato a KITT.")
    }

    private fun addChat(fromUser: Boolean, text: String) {
        synchronized(chat) {
            chat.add(Pair(fromUser, text))
            while (chat.size > 200) {
                chat.removeAt(0)
            }
        }

        android.os.Handler(mainLooper).post {
            ui?.message(fromUser, text)
        }
    }

    private fun setStatus(message: String) {
        lastStatus = message

        android.os.Handler(mainLooper).post {
            ui?.status(message)
        }
    }

    private fun scan(on: Boolean) {
        android.os.Handler(mainLooper).post {
            ui?.scan(on)

            if (on) scanner?.start()
            else scanner?.stop()
        }
    }

    private fun stopKitt() {
        serviceActive = false

        gemini?.close()
        gemini = null

        removeBubble()
        scan(false)

        setStatus("KITT spento")

        stopForeground(true)
    }

    private fun pauseKitt() {
        userPaused = true

        gemini?.close()
        gemini = null

        scan(false)
        refreshBubble()

        setStatus("KITT in pausa.")
    }

    private fun resumeKitt() {
        userPaused = false
        refreshBubble()
        connectGemini()
    }

    private fun updateBubble() {
        if (!serviceActive || dismissed ||
            appVisible || !Settings.canDrawOverlays(this)
        ) {
            removeBubble()
            return
        }

        showBubble()
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

        box.addView(
            sc,
            LinearLayout.LayoutParams(dp(110), dp(22))
        )

        val pause = TextView(this)
        pause.text = "STOP"
        pause.setTextColor(accent)
        pause.textSize = 12f
        pause.typeface = Typeface.DEFAULT_BOLD
        pause.setPadding(dp(12), dp(6), dp(6), dp(6))

        box.addView(pause)

        pause.setOnClickListener {
            if (userPaused) resumeKitt()
            else pauseKitt()
        }

        val close = TextView(this)
        close.text = "✕"
        close.textSize = 18f
        close.setTextColor(Color.WHITE)
        close.setPadding(dp(10), 0, dp(4), 0)
        close.visibility = View.GONE

        box.addView(close)

        close.setOnClickListener {
            dismissed = true
            removeBubble()
            setStatus("Bolla chiusa. KITT continua a funzionare.")
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

        box.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (kotlin.math.abs(dx) > dp(6) ||
                        kotlin.math.abs(dy) > dp(6)
                    ) {
                        moved = true
                    }

                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        bubbleX = lp.x
                        bubbleY = lp.y

                        try {
                            wm.updateViewLayout(box, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        controlsVisible = !controlsVisible
                        close.visibility =
                            if (controlsVisible) View.VISIBLE else View.GONE
                    }
                    true
                }

                else -> false
            }
        }

        try {
            wm.addView(box, lp)

            bubble = box
            scanner = sc
            bubblePause = pause
            bubbleClose = close
            bubbleBg = bg

            if (userPaused) sc.stop()
            else sc.start()

            refreshBubble()
        } catch (_: Exception) {
            bubble = null
        }
    }

    private fun refreshBubble() {
        bubblePause?.text = if (userPaused) "PLAY" else "STOP"

        bubbleClose?.visibility =
            if (controlsVisible) View.VISIBLE else View.GONE
    }

    private fun removeBubble() {
        val old = bubble ?: return

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(old)
        } catch (_: Exception) {
        }

        scanner?.stop()
        bubble = null
        scanner = null
        bubblePause = null
        bubbleClose = null
        bubbleBg = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        if (!stoppedByUser && serviceActive) {
            val restart = Intent(this, KittService::class.java)

            try {
                val pending = PendingIntent.getForegroundService(
                    this,
                    2,
                    restart,
                    PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT
                )

                val alarm =
                    getSystemService(ALARM_SERVICE) as android.app.AlarmManager

                alarm.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 2000,
                    pending
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        gemini?.close()
        gemini = null

        visibilityCallback = null
        removeBubble()
        scan(false)

        super.onDestroy()
    }
}
