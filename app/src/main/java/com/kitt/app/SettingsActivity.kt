
package com.kitt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("kitt", MODE_PRIVATE) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun launchKitt() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Concedi a KITT il permesso del microfono",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(this, KittService::class.java)
        intent.action = KittService.ACTION_WAKE
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Rimuove le vecchie preferenze dei motori vocali.
        prefs.edit()
            .remove("groq_key")
            .remove("tts_engine")
            .remove("tts_voice")
            .remove("tts_rate")
            .remove("tts_pitch")
            .remove("cloud_voice")
            .remove("cloud_model")
            .apply()

        val accent = KittTheme.scanner(this)
        val background = KittTheme.bg(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(background)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(18), dp(18), dp(18), dp(30))
        scroll.addView(column)
        setContentView(scroll)

        fun title(text: String) {
            val view = TextView(this)
            view.text = text
            view.textSize = 14f
            view.typeface = Typeface.MONOSPACE
            view.setTextColor(accent)
            view.setPadding(0, dp(22), 0, dp(10))
            column.addView(view)
        }

        fun description(text: String) {
            val view = TextView(this)
            view.text = text
            view.textSize = 14f
            view.setTextColor(Color.LTGRAY)
            view.setPadding(0, 0, 0, dp(10))
            column.addView(view)
        }

        fun button(text: String, action: () -> Unit) {
            val view = Button(this)
            view.text = text
            view.isAllCaps = false
            view.setTextColor(accent)
            view.setOnClickListener { action() }

            val shape = GradientDrawable()
            shape.setColor(Color.rgb(25, 15, 15))
            shape.cornerRadius = dp(22).toFloat()
            shape.setStroke(dp(1), accent)
            view.background = shape

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(6)
            column.addView(view, params)
        }

        fun field(hint: String, value: String): EditText {
            val view = EditText(this)
            view.hint = hint
            view.setText(value)
            view.setSingleLine(true)
            view.setTextColor(Color.WHITE)
            view.setHintTextColor(Color.GRAY)

            val shape = GradientDrawable()
            shape.setColor(Color.rgb(25, 15, 15))
            shape.cornerRadius = dp(14).toFloat()
            shape.setStroke(dp(1), accent)
            view.background = shape
            view.setPadding(dp(14), dp(10), dp(14), dp(10))

            column.addView(view)
            return view
        }

        fun check(label: String, key: String, default: Boolean) {
            val view = CheckBox(this)
            view.text = label
            view.setTextColor(Color.WHITE)
            view.isChecked = prefs.getBoolean(key, default)
            view.buttonTintList =
                android.content.res.ColorStateList.valueOf(accent)
            view.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
            column.addView(view)
        }

        // Intestazione
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.orientation = LinearLayout.HORIZONTAL

        val heading = TextView(this)
        heading.text = "IMPOSTAZIONI K.I.T.T."
        heading.textSize = 20f
        heading.typeface = Typeface.MONOSPACE
        heading.setTextColor(accent)

        header.addView(
            heading,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val back = Button(this)
        back.text = "Indietro"
        back.isAllCaps = false
        back.setOnClickListener { finish() }
        header.addView(back)
        column.addView(header)

        // Profilo
        title("IL PILOTA")
        description("Come deve chiamarti KITT?")
        val name = field(
            "Il tuo nome",
            KittTheme.userName(this)
        )
        name.setText(KittTheme.userName(this))
        name.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                KittTheme.setUserName(
                    this,
                    name.text.toString().trim()
                )
            }
        }

        check(
            "Saluto di benvenuto all'avvio",
            "greet",
            true
        )

        check(
            "Risposta vocale ai messaggi scritti",
            "speak_typed",
            false
        )

        // Gemini
        title("INTELLIGENZA ARTIFICIALE")
        description(
            "KITT utilizza Gemini Live per conversare in tempo reale, " +
            "ascoltare il microfono e rispondere con voce naturale. " +
            "È necessaria una connessione Internet."
        )

        val hasKey = !prefs.getString("gemini_key", "").isNullOrBlank()

        val key = field(
            if (hasKey) "Chiave Gemini già salvata" else "Incolla la chiave API Gemini",
            ""
        )

        button("Salva chiave Gemini") {
            val value = key.text.toString().trim()
            if (value.isNotEmpty()) {
                prefs.edit().putString("gemini_key", value).apply()
                key.setText("")
                Toast.makeText(
                    this,
                    "Chiave Gemini salvata",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Inserisci prima la chiave",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        check(
            "Attiva la voce Gemini Live",
            "cloud_on",
            true
        )

        button("Avvia KITT e collega Gemini") {
            launchKitt()
        }

        // Background
        title("FUNZIONAMENTO IN BACKGROUND")
        description(
            "Consenti a KITT di funzionare anche quando lo schermo " +
            "è spento. Sullo stereo potrebbe essere necessario " +
            "abilitare l'avvio automatico."
        )

        button("Escludi KITT dal risparmio energetico") {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Apri le impostazioni batteria dell'app KITT",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Informazioni
        title("K.I.T.T.")
        description(
            "Knight Industries Two Thousand\n" +
            "Assistente personale del pilota."
        )
    }
}
