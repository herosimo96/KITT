package com.kitt.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var scanner: ScannerView
    private lateinit var hint: TextView
    private lateinit var brain: Brain
    private lateinit var commands: Commands
    private lateinit var voice: VoiceListener
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())

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

        hint = TextView(this).apply {
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }

        root.addView(title)
        root.addView(scanner, scannerParams)
        root.addView(hint)
        setContentView(root)

        brain = Brain(this)
        commands = Commands(this)
        voice = VoiceListener(
            { pcm -> onUtterance(pcm) },
            { msg -> setStatus(msg) }
        )
        tts = TextToSpeech(this, this)

        if (brain.hasKey()) {
            hint.text = "Pronto. Tocca per parlare. Tieni premuto per cambiare la chiave"
        } else {
            hint.text = "Tieni premuto lo schermo per inserire la chiave Groq"
        }

        root.setOnClickListener { toggleListening() }
        root.setOnLongClickListener {
            askKey()
            true
        }
    }

    private fun askKey() {
        val input = EditText(this)
        input.hint = "Incolla qui la chiave Groq"
        AlertDialog.Builder(this)
            .setTitle("Chiave Groq")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val k = input.text.toString().trim()
                getSharedPreferences("kitt", MODE_PRIVATE)
                    .edit().putString("groq_key", k).apply()
                Toast.makeText(this, "Chiave salvata", Toast.LENGTH_SHORT).show()
                setStatus("Pronto. Tocca per parlare")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun setStatus(text: String) {
        runOnUiThread { hint.text = text }
    }

    private fun resumeListening() {
        handler.postDelayed({ voice.paused = false }, 500)
    }

    private fun toggleListening() {
        if (!brain.hasKey()) {
            Toast.makeText(this, "Prima inserisci la chiave: tieni premuto lo schermo", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (listening) {
            voice.stop()
            listening = false
            setStatus("In pausa. Tocca per parlare")
        } else {
            voice.paused = false
            voice.start()
            listening = true
            setStatus("Ti ascolto...")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleListening()
        }
    }

    private fun onUtterance(pcm: ByteArray) {
        voice.paused = true
        setStatus("Elaboro...")
        runOnUiThread { scanner.start() }
        Thread {
            val (text, error) = brain.transcribe(pcm)
            if (error.isNotBlank()) {
                runOnUiThread { scanner.stop() }
                setStatus(error)
                resumeListening()
                return@Thread
            }
            if (text.length < 2) {
                runOnUiThread { scanner.stop() }
                setStatus("Ti ascolto...")
                resumeListening()
                return@Thread
            }
            setStatus("Hai detto: $text")
            val r = brain.think(text)
            runOnUiThread {
                val failure = commands.run(r.action, r.arg)
                val said = failure ?: r.reply
                setStatus(said)
                speak(said)
            }
        }.start()
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
                voice.paused = true
                runOnUiThread { scanner.start() }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread { scanner.stop() }
                resumeListening()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { scanner.stop() }
                resumeListening()
            }
        })
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitt")
        } else {
            scanner.stop()
            resumeListening()
        }
    }

    override fun onDestroy() {
        voice.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}