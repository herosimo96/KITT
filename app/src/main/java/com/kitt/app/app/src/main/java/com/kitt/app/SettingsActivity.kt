package com.kitt.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private class RgbPicker(
    ctx: Context,
    initial: Int,
    accent: Int,
    private val onChange: (Int) -> Unit
) {
    val view = LinearLayout(ctx)
    private val bars = ArrayList<SeekBar>()
    private val labels = ArrayList<TextView>()
    private val names = listOf("R", "G", "B")
    private var silent = false

    init {
        view.orientation = LinearLayout.VERTICAL
        val start = listOf(Color.red(initial), Color.green(initial), Color.blue(initial))
        for (i in 0..2) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val label = TextView(ctx)
            label.text = names[i] + " " + start[i]
            label.setTextColor(Color.WHITE)
            label.minWidth = (70 * ctx.resources.displayMetrics.density).toInt()
            val bar = SeekBar(ctx)
            bar.max = 255
            bar.progress = start[i]
            bar.progressTintList = ColorStateList.valueOf(accent)
            bar.thumbTintList = ColorStateList.valueOf(accent)
            labels.add(label)
            bars.add(bar)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    labels[i].text = names[i] + " " + p
                    if (!silent) onChange(current())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            row.addView(label)
            row.addView(
                bar,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            view.addView(row)
        }
    }

    fun current(): Int = Color.rgb(bars[0].progress, bars[1].progress, bars[2].progress)

    fun set(c: Int) {
        silent = true
        bars[0].progress = Color.red(c)
        bars[1].progress = Color.green(c)
        bars[2].progress = Color.blue(c)
        silent = false
    }
}

class SettingsActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun notifyService() {
        KittService.visibilityCallback?.invoke()
    }

    private fun startService(action: String?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Concedi prima il permesso del microfono a KITT", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, KittService::class.java)
        if (action != null) i.action = action
        ContextCompat.startForegroundService(this, i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accent = KittTheme.scanner(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(KittTheme.bg(this))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(16), dp(20), dp(32))
        scroll.addView(col)
        setContentView(scroll)

        fun header(t: String) {
            val h = TextView(this)
            h.text = t
            h.setTextColor(accent)
            h.textSize = 15f
            h.typeface = Typeface.MONOSPACE
            h.setPadding(0, dp(22), 0, dp(6))
            col.addView(h)
        }

        fun body(t: String): TextView {
            val b = TextView(this)
            b.text = t
            b.setTextColor(Color.LTGRAY)
            b.textSize = 14f
            col.addView(b)
            return b
        }

        fun chip(label: String, textColor: Int, fill: Int, stroke: Int, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setTextColor(textColor)
            val g = GradientDrawable()
            g.setColor(fill)
            g.cornerRadius = dp(10).toFloat()
            g.setStroke(dp(1), stroke)
            b.background = g
            b.setOnClickListener { onClick() }
            return b
        }

        fun chipRow(items: List<View>) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            for (v in items) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                r.addView(v, lp)
            }
            col.addView(r)
        }

        fun field(hint: String, text: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(text)
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(Color.GRAY)
            e.setSingleLine(true)
            val g = GradientDrawable()
            g.setColor(Color.parseColor("#1A1A1A"))
            g.cornerRadius = dp(10).toFloat()
            g.setStroke(dp(1), accent)
            e.background = g
            e.setPadding(dp(12), dp(10), dp(12), dp(10))
            return e
        }

        fun checkbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
            val c = CheckBox(this)
            c.text = label
            c.setTextColor(Color.WHITE)
            c.isChecked = checked
            c.buttonTintList = ColorStateList.valueOf(accent)
            c.setOnCheckedChangeListener { _, v -> onChange(v) }
            return c
        }

        // Anteprime
        val scanPreview = ScannerView(this)
        scanPreview.color = accent
        scanPreview.start(1100L)
        val bgPreview = View(this)
        bgPreview.setBackgroundColor(KittTheme.bg(this))

        fun applyScanner(c: Int) {
            KittTheme.setScanner(this, c)
            scanPreview.color = c
            notifyService()
        }

        fun applyBg(c: Int) {
            KittTheme.setBg(this, c)
            bgPreview.setBackgroundColor(c)
            scroll.setBackgroundColor(c)
            notifyService()
        }

        val scannerPicker = RgbPicker(this, accent, accent) { c -> applyScanner(c) }
        val bgPicker = RgbPicker(this, KittTheme.bg(this), accent) { c -> applyBg(c) }

        // Titolo e indietro
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val titleView = TextView(this)
        titleView.text = "IMPOSTAZIONI"
        titleView.setTextColor(accent)
        titleView.textSize = 24f
        titleView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        top.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("Indietro", accent, Color.parseColor("#1A1A1A"), accent) { finish() })
        col.addView(top)

        // Tema
        header("TEMA")
        body("Scegli un tema: cambia scanner e sfondo insieme.")
        for (group in KittTheme.presets.chunked(3)) {
            chipRow(group.map { t ->
                chip(t.name, t.scanner, Color.parseColor("#1A1A1A"), t.scanner) {
                    applyScanner(t.scanner)
                    scannerPicker.set(t.scanner)
                    applyBg(t.bg)
                    bgPicker.set(t.bg)
                }
            })
        }

        // Scanner
        header("COLORE DELLO SCANNER")
        col.addView(scanPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        col.addView(scannerPicker.view)

        // Sfondo
        header("SFONDO")
        col.addView(bgPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        col.addView(bgPicker.view)
        val bgOptions = listOf(
            Pair("Nero", "#000000"),
            Pair("Grafite", "#121212"),
            Pair("Blu notte", "#050A18"),
            Pair("Rosso scuro", "#120404"),
            Pair("Verde scuro", "#04120A"),
            Pair("Viola scuro", "#0C0414")
        )
        for (group in bgOptions.chunked(3)) {
            chipRow(group.map { o ->
                val c = Color.parseColor(o.second)
                chip(o.first, Color.WHITE, c, Color.GRAY) {
                    applyBg(c)
                    bgPicker.set(c)
                }
            })
        }

        // Nome e saluto
        header("TU E KITT")
        body("Come ti deve chiamare KITT?")
        val nameField = field("Il tuo nome", KittTheme.userName(this))
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                KittTheme.setUserName(this@SettingsActivity, s.toString().trim())
            }
        })
        col.addView(nameField)
        col.addView(checkbox("Saluto di benvenuto all'avvio", KittTheme.greet(this)) {
            KittTheme.setGreet(this, it)
        })
        col.addView(checkbox("KITT risponde a voce anche quando scrivo", KittTheme.speakTyped(this)) {
            KittTheme.setSpeakTyped(this, it)
        })

        // Chiave Groq
        header("CHIAVE GROQ")
        val prefs = getSharedPreferences("kitt", MODE_PRIVATE)
        val hasKey = !prefs.getString("groq_key", "").isNullOrBlank()
        val keyField = field(
            if (hasKey) "Chiave già salvata: incolla per cambiarla" else "Incolla qui la chiave Groq",
            ""
        )
        col.addView(keyField)
        col.addView(chip("Salva chiave", accent, Color.parseColor("#1A1A1A"), accent) {
            val k = keyField.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("groq_key", k).apply()
                keyField.setText("")
                Toast.makeText(this, "Chiave salvata", Toast.LENGTH_SHORT).show()
                startService(null)
            }
        })

        // Voce
        header("VOCE")
        body("Se non senti KITT parlare, premi più volte: ogni tocco prova un canale audio diverso e KITT lo annuncia. Quando lo senti, fermati: resta salvato.")
        col.addView(chip("Prova voce / cambia canale", accent, Color.parseColor("#1A1A1A"), accent) {
            startService(KittService.ACTION_TEST)
        })

        // Crediti
        val credit = TextView(this)
        credit.text = "K.I.T.T. v1.0\nSviluppato da\nSimone Campanale Nik (Herosimo96)"
        credit.setTextColor(accent)
        credit.textSize = 16f
        credit.typeface = Typeface.MONOSPACE
        credit.gravity = Gravity.CENTER
        credit.setPadding(0, dp(32), 0, dp(8))
        col.addView(credit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}