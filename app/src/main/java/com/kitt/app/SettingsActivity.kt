package com.kitt.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

    private fun launchKitt(action: String?) {
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

        fun bgGradient(c: Int): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mixColors(c, accent, 0.16f), c)
        )

        val scroll = ScrollView(this)
        scroll.background = bgGradient(KittTheme.bg(this))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(16), dp(18), dp(32))
        scroll.addView(col)
        setContentView(scroll)

        var cur: LinearLayout = col

        fun card(t: String) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.setPadding(dp(16), dp(12), dp(16), dp(16))
            val g = GradientDrawable()
            g.setColor(withAlpha(Color.WHITE, 16))
            g.cornerRadius = dp(18).toFloat()
            g.setStroke(dp(1), withAlpha(accent, 80))
            c.background = g
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            col.addView(c, lp)
            cur = c
            val h = TextView(this)
            h.text = t
            h.setTextColor(accent)
            h.textSize = 13f
            h.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            h.letterSpacing = 0.12f
            h.setPadding(0, 0, 0, dp(8))
            c.addView(h)
        }

        fun body(t: String): TextView {
            val b = TextView(this)
            b.text = t
            b.setTextColor(Color.LTGRAY)
            b.textSize = 14f
            cur.addView(b)
            return b
        }

        fun chip(label: String, textColor: Int, fill: Int, stroke: Int, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.minHeight = 0
            b.minimumHeight = 0
            b.setPadding(dp(14), dp(9), dp(14), dp(9))
            b.setTextColor(textColor)
            val g = GradientDrawable()
            g.setColor(fill)
            g.cornerRadius = dp(22).toFloat()
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
            cur.addView(r)
        }

        fun field(hint: String, text: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(text)
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(Color.GRAY)
            e.setSingleLine(true)
            val g = GradientDrawable()
            g.setColor(withAlpha(Color.WHITE, 18))
            g.cornerRadius = dp(14).toFloat()
            g.setStroke(dp(1), withAlpha(accent, 130))
            e.background = g
            e.setPadding(dp(14), dp(10), dp(14), dp(10))
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

        val dark = withAlpha(Color.WHITE, 16)
        val prefs = getSharedPreferences("kitt", MODE_PRIVATE)

        fun slider(label: String, key: String) {
            val saved = prefs.getInt(key, 100)
            val lab = TextView(this)
            lab.setTextColor(Color.WHITE)
            lab.text = label + ": " + saved + "%"
            lab.setPadding(0, dp(8), 0, 0)
            cur.addView(lab)
            val bar = SeekBar(this)
            bar.max = 100
            bar.progress = (saved - 50).coerceIn(0, 100)
            bar.progressTintList = ColorStateList.valueOf(accent)
            bar.thumbTintList = ColorStateList.valueOf(accent)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + 50
                    lab.text = label + ": " + v + "%"
                    if (fromUser) prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            cur.addView(bar)
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
            scroll.background = bgGradient(c)
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
        titleView.textSize = 22f
        titleView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        titleView.letterSpacing = 0.1f
        top.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("Indietro", accent, dark, accent) { finish() })
        col.addView(top)

        // Tema
        card("TEMA")
        body("Scegli un tema: cambia scanner e sfondo insieme.")
        for (group in KittTheme.presets.chunked(3)) {
            chipRow(group.map { t ->
                chip(t.name, t.scanner, dark, t.scanner) {
                    applyScanner(t.scanner)
                    scannerPicker.set(t.scanner)
                    applyBg(t.bg)
                    bgPicker.set(t.bg)
                }
            })
        }

        // Scanner
        card("COLORE DELLO SCANNER")
        cur.addView(scanPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        cur.addView(scannerPicker.view)

        // Sfondo
        card("SFONDO")
        cur.addView(bgPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        cur.addView(bgPicker.view)
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
        card("TU E KITT")
        body("Come ti deve chiamare KITT?")
        val nameField = field("Il tuo nome", KittTheme.userName(this))
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                KittTheme.setUserName(this@SettingsActivity, s.toString().trim())
            }
        })
        cur.addView(nameField)
        cur.addView(checkbox("Saluto di benvenuto all'avvio", KittTheme.greet(this)) {
            KittTheme.setGreet(this, it)
        })
        cur.addView(checkbox("KITT risponde a voce anche quando scrivo", KittTheme.speakTyped(this)) {
            KittTheme.setSpeakTyped(this, it)
        })

        // Chiave Groq
        card("CHIAVE GROQ")
        val hasKey = !prefs.getString("groq_key", "").isNullOrBlank()
        val keyField = field(
            if (hasKey) "Chiave già salvata: incolla per cambiarla" else "Incolla qui la chiave Groq",
            ""
        )
        cur.addView(keyField)
        cur.addView(chip("Salva chiave", accent, dark, accent) {
            val k = keyField.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("groq_key", k).apply()
                keyField.setText("")
                Toast.makeText(this, "Chiave salvata", Toast.LENGTH_SHORT).show()
                launchKitt(null)
            }
        })

        // Funzionamento in background
        card("FUNZIONAMENTO IN BACKGROUND")
        body("Se KITT smette di rispondere dopo un po', consenti all'app di restare attiva. Su molti stereo serve anche un'impostazione dello stereo stesso: cerca KITT in Avvio automatico, Gestione app o Risparmio energetico e consentilo.")
        cur.addView(chip("Non limitare KITT (risparmio energetico)", accent, dark, accent) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + packageName)
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Non disponibile su questo dispositivo: cerca KITT in Impostazioni > App > Batteria",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        // Voce online
        card("VOCE ONLINE (GEMINI)")
        body("Voce molto più naturale, senza usare i motori dello stereo. Serve internet e una chiave gratuita: aistudio.google.com/apikey. Se non funziona o la quota finisce, KITT usa la voce del sistema.")
        val gHas = !prefs.getString("gemini_key", "").isNullOrBlank()
        val gField = field(
            if (gHas) "Chiave già salvata: incolla per cambiarla" else "Incolla qui la chiave Gemini",
            ""
        )
        cur.addView(gField)
        cur.addView(chip("Salva chiave Gemini", accent, dark, accent) {
            val k = gField.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("gemini_key", k).apply()
                gField.setText("")
                Toast.makeText(this, "Chiave Gemini salvata", Toast.LENGTH_SHORT).show()
            }
        })
        cur.addView(checkbox("Usa la voce online", prefs.getBoolean("cloud_on", false)) {
            prefs.edit().putBoolean("cloud_on", it).apply()
        })
        body("Voce (provale: Charon, Orus, Puck e Fenrir di solito suonano maschili):")
        val cloudVoices = listOf("Charon", "Orus", "Puck", "Fenrir", "Iapetus", "Algieba", "Kore", "Aoede")
        val cvText = body("Voce scelta: " + (prefs.getString("cloud_voice", "Charon") ?: "Charon"))
        for (group in cloudVoices.chunked(4)) {
            chipRow(group.map { n ->
                chip(n, accent, dark, accent) {
                    prefs.edit().putString("cloud_voice", n).apply()
                    cvText.text = "Voce scelta: " + n
                    launchKitt(KittService.ACTION_SAMPLE)
                }
            })
        }
        body("Modello (lascia vuoto per quello predefinito, gemini-3.1-flash-tts-preview):")
        val modelField = field("gemini-3.1-flash-tts-preview", prefs.getString("cloud_model", "") ?: "")
        modelField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("cloud_model", s.toString().trim()).apply()
            }
        })
        cur.addView(modelField)
        cur.addView(chip("Ascolta anteprima", accent, dark, accent) {
            launchKitt(KittService.ACTION_SAMPLE)
        })
        body("I modelli vocali sono in anteprima e hanno limiti gratuiti ridotti. Nel piano gratuito Google può usare i testi inviati per migliorare i suoi prodotti.")

        // Voce di sistema
        card("VOCE DI SISTEMA")
        body("Scegli il motore vocale di Android. Con \"Automatico\" KITT usa SherpaTTS se è installato.")
        val engineList = ArrayList<Pair<String, String>>()
        engineList.add(Pair("Automatico", ""))
        try {
            val svc = packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            for (ri in svc) {
                engineList.add(Pair(ri.loadLabel(packageManager).toString(), ri.serviceInfo.packageName))
            }
        } catch (e: Exception) {
        }
        val savedEngine = prefs.getString("tts_engine", "") ?: ""
        val engineText = body(
            "Motore scelto: " + (engineList.firstOrNull { it.second == savedEngine }?.first ?: "Automatico")
        )
        for (group in engineList.chunked(2)) {
            chipRow(group.map { e ->
                chip(e.first, accent, dark, accent) {
                    prefs.edit()
                        .putString("tts_engine", e.second)
                        .putString("tts_voice", "")
                        .apply()
                    engineText.text = "Motore scelto: " + e.first
                    launchKitt(KittService.ACTION_TTS)
                }
            })
        }

        body("Voce (tra quelle italiane che il motore mette a disposizione):")
        val voices = KittService.voiceNames
        val savedVoice = prefs.getString("tts_voice", "") ?: ""
        val voiceText = body(
            if (voices.isEmpty()) {
                "Nessuna voce elencata dal motore: sceglie lui da solo. Dopo aver cambiato motore tocca Aggiorna elenco voci."
            } else {
                "Voce scelta: " + (if (savedVoice.isEmpty()) "predefinita del motore" else savedVoice)
            }
        )
        if (voices.isNotEmpty()) {
            val voiceList = ArrayList<Pair<String, String>>()
            voiceList.add(Pair("Predefinita", ""))
            for (n in voices) voiceList.add(Pair(n, n))
            for (group in voiceList.chunked(2)) {
                chipRow(group.map { v ->
                    chip(v.first, accent, dark, accent) {
                        prefs.edit().putString("tts_voice", v.second).apply()
                        voiceText.text = "Voce scelta: " +
                                (if (v.second.isEmpty()) "predefinita del motore" else v.second)
                        launchKitt(KittService.ACTION_TTS)
                    }
                })
            }
        }
        cur.addView(chip("Aggiorna elenco voci", accent, dark, accent) { recreate() })

        body("Velocità e tono (con eSpeak prova velocità 85-90% e tono 85%).")
        slider("Velocità", "tts_rate")
        slider("Tono", "tts_pitch")
        cur.addView(chip("Ascolta anteprima", accent, dark, accent) {
            launchKitt(KittService.ACTION_SAMPLE)
        })
        body("Se non senti KITT parlare, premi più volte: ogni tocco prova un canale audio diverso e KITT lo annuncia. Quando lo senti, fermati: resta salvato.")
        cur.addView(chip("Prova voce / cambia canale", accent, dark, accent) {
            launchKitt(KittService.ACTION_TEST)
        })

        // Crediti
        val credit = TextView(this)
        credit.text = "K.I.T.T. v1.4\nSviluppato da\nSimone Campanale Nik (Herosimo96)"
        credit.setTextColor(accent)
        credit.textSize = 16f
        credit.typeface = Typeface.MONOSPACE
        credit.gravity = Gravity.CENTER
        credit.setPadding(0, dp(32), 0, dp(8))
        col.addView(credit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}