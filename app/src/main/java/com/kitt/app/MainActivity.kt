package com.kitt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), KittUi {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var scanner: ScannerView
    private lateinit var hint: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var settingsBtn: Button

    private lateinit var intro: LinearLayout
    private lateinit var introTitle: TextView
    private lateinit var introScanner: ScannerView
    private lateinit var introSub: TextView
    private lateinit var introDev: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(10), dp(16), dp(10))

        // Intestazione
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        titleView = TextView(this)
        titleView.text = "K.I.T.T."
        titleView.textSize = 30f
        titleView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        settingsBtn = Button(this)
        settingsBtn.text = "Impostazioni"
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(settingsBtn)
        content.addView(top)

        // Scanner e stato
        scanner = ScannerView(this)
        val sp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        sp.topMargin = dp(8)
        content.addView(scanner, sp)

        hint = TextView(this)
        hint.textSize = 13f
        hint.setTextColor(Color.GRAY)
        hint.maxLines = 3
        val hp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        hp.topMargin = dp(6)
        content.addView(hint, hp)

        // Chat
        chatScroll = ScrollView(this)
        chatBox = LinearLayout(this)
        chatBox.orientation = LinearLayout.VERTICAL
        chatScroll.addView(chatBox)
        val cp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        cp.topMargin = dp(8)
        content.addView(chatScroll, cp)

        // Riga di scrittura
        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "Scrivi a KITT..."
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.GRAY)
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }
        micBtn = Button(this)
        micBtn.text = "Parla"
        micBtn.setOnClickListener { startKitt(KittService.ACTION_WAKE, null) }
        sendBtn = Button(this)
        sendBtn.text = "Invia"
        sendBtn.setOnClickListener { sendTyped() }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(micBtn)
        inputRow.addView(sendBtn)
        val ip = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ip.topMargin = dp(8)
        content.addView(inputRow, ip)

        root.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // Animazione di avvio
        intro = LinearLayout(this)
        intro.orientation = LinearLayout.VERTICAL
        intro.gravity = Gravity.CENTER
        introTitle = TextView(this)
        introTitle.textSize = 48f
        introTitle.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        introTitle.gravity = Gravity.CENTER
        introScanner = ScannerView(this)
        introSub = TextView(this)
        introSub.text = "KNIGHT INDUSTRIES TWO THOUSAND"
        introSub.textSize = 13f
        introSub.typeface = Typeface.MONOSPACE
        introSub.setTextColor(Color.GRAY)
        introSub.gravity = Gravity.CENTER
        introSub.alpha = 0f
        introDev = TextView(this)
        introDev.text = "Sviluppato da Simone Campanale Nik (Herosimo96)"
        introDev.textSize = 12f
        introDev.setTextColor(Color.DKGRAY)
        introDev.gravity = Gravity.CENTER
        introDev.alpha = 0f
        intro.addView(introTitle)
        val isp = LinearLayout.LayoutParams(dp(260), dp(18))
        isp.topMargin = dp(14)
        intro.addView(introScanner, isp)
        val subp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        subp.topMargin = dp(18)
        intro.addView(introSub, subp)
        val devp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        devp.topMargin = dp(8)
        intro.addView(introDev, devp)
        intro.isClickable = true
        intro.setOnClickListener { hideIntro() }
        root.addView(
            intro,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        setContentView(root)
        applyTheme()

        if (savedInstanceState == null) {
            runIntro()
        } else {
            intro.visibility = View.GONE
        }

        requestPerms()
    }

    // ---------- tema ----------

    private fun styleButton(b: Button, accent: Int, filled: Boolean) {
        b.isAllCaps = false
        val g = GradientDrawable()
        g.cornerRadius = dp(10).toFloat()
        g.setStroke(dp(1), accent)
        if (filled) {
            g.setColor(accent)
            b.setTextColor(Color.BLACK)
        } else {
            g.setColor(Color.parseColor("#1A1A1A"))
            b.setTextColor(accent)
        }
        b.background = g
    }

    private fun applyTheme() {
        val accent = KittTheme.scanner(this)
        val bg = KittTheme.bg(this)
        root.setBackgroundColor(bg)
        intro.setBackgroundColor(bg)
        titleView.setTextColor(accent)
        introTitle.setTextColor(accent)
        scanner.color = accent
        introScanner.color = accent
        styleButton(settingsBtn, accent, false)
        styleButton(micBtn, accent, false)
        styleButton(sendBtn, accent, true)
        val g = GradientDrawable()
        g.setColor(Color.parseColor("#1A1A1A"))
        g.cornerRadius = dp(10).toFloat()
        g.setStroke(dp(1), accent)
        input.background = g
        renderChat()
    }

    private fun runIntro() {
        val full = "K.I.T.T."
        var n = 0
        introScanner.start(650L)
        val step = object : Runnable {
            override fun run() {
                n++
                introTitle.text = full.substring(0, n)
                if (n < full.length) {
                    handler.postDelayed(this, 170)
                } else {
                    introSub.animate().alpha(1f).setDuration(500).start()
                    introDev.animate().alpha(1f).setDuration(700).setStartDelay(300).start()
                    handler.postDelayed({ hideIntro() }, 2000)
                }
            }
        }
        handler.postDelayed(step, 400)
    }

    private fun hideIntro() {
        if (intro.visibility == View.GONE) return
        handler.removeCallbacksAndMessages(null)
        intro.animate().alpha(0f).setDuration(500).withEndAction {
            intro.visibility = View.GONE
            introScanner.stop()
        }.start()
    }

    // ---------- chat ----------

    private fun bubbleView(fromUser: Boolean, text: String): View {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        if (fromUser) {
            g.setColor(Color.parseColor("#2A2A2A"))
        } else {
            g.setColor(Color.parseColor("#0D0D0D"))
            g.setStroke(dp(1), KittTheme.scanner(this))
        }
        tv.background = g
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        lp.gravity = if (fromUser) Gravity.END else Gravity.START
        tv.layoutParams = lp
        return tv
    }

    private fun renderChat() {
        chatBox.removeAllViews()
        val copy = synchronized(KittService.chat) { ArrayList(KittService.chat) }
        if (copy.isEmpty()) {
            val t = TextView(this)
            t.text = "Scrivimi qui sotto oppure di' \"Hey KITT\"."
            t.setTextColor(Color.GRAY)
            t.setPadding(dp(4), dp(8), dp(4), dp(8))
            chatBox.addView(t)
        }
        for (m in copy) {
            chatBox.addView(bubbleView(m.first, m.second))
        }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        startKitt(KittService.ACTION_TEXT, t)
    }

    // ---------- collegamento con il servizio ----------

    override fun onStart() {
        super.onStart()
        KittService.ui = this
        KittService.appVisible = true
        KittService.visibilityCallback?.invoke()
        hint.text = KittService.lastStatus
        applyTheme()
    }

    override fun onStop() {
        if (KittService.ui === this) KittService.ui = null
        KittService.appVisible = false
        KittService.visibilityCallback?.invoke()
        super.onStop()
    }

    override fun status(text: String) {
        hint.text = text
    }

    override fun scan(on: Boolean) {
        if (on) scanner.start() else scanner.stop()
    }

    override fun message(fromUser: Boolean, text: String) {
        renderChat()
    }

    // ---------- permessi ----------

    private fun requestPerms() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            afterPermissions()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        afterPermissions()
    }

    private fun afterPermissions() {
        startKitt(null, null)
        askOverlayOnce()
    }

    private fun askOverlayOnce() {
        val prefs = getSharedPreferences("kitt", MODE_PRIVATE)
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("overlay_asked", false)) {
            prefs.edit().putBoolean("overlay_asked", true).apply()
            Toast.makeText(
                this,
                "Attiva 'Mostra sopra altre app' per KITT, poi torna indietro",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }
    }

    private fun startKitt(action: String?, text: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hint.text = "Concedi il permesso del microfono a KITT"
            return
        }
        val i = Intent(this, KittService::class.java)
        if (action != null) i.action = action
        if (text != null) i.putExtra(KittService.EXTRA_TEXT, text)
        ContextCompat.startForegroundService(this, i)
    }
}