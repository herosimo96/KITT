package com.kitt.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var scanner: ScannerView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "K.I.T.T."
            setTextColor(Color.RED)
            textSize = 40f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        scanner = ScannerView(this)
        val scannerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ).apply { topMargin = 60 }

        val hint = TextView(this).apply {
            text = "Tocca lo schermo per far parlare KITT"
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }

        root.addView(title)
        root.addView(scanner, scannerParams)
        root.addView(hint)
        setContentView(root)

        tts = TextToSpeech(this, this)

        root.setOnClickListener {
            speak("Buonasera. Sono KITT, il tuo assistente. Come posso aiutarti?")
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Nessun motore vocale trovato", Toast.LENGTH_LONG).show()
            return
        }
        val result = tts?.setLanguage(Locale.ITALIAN)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            Toast.makeText(this, "Voce italiana non disponibile", Toast.LENGTH_LONG).show()
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { scanner.start() }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread { scanner.stop() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { scanner.stop() }
            }
        })
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitt")
        } else {
            Toast.makeText(this, "La voce non è pronta", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}