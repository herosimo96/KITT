package com.kitt.app

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, RecognitionListener {
    private lateinit var scanner: ScannerView
    private lateinit var hint: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var listening = false

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
            text = "Avvio in corso..."
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }

        root.addView(title)
        root.addView(scanner, scannerParams)
        root.addView(hint)
        setContentView(root)

        tts = TextToSpeech(this, this)
        prepareModel()

        root.setOnClickListener { toggleListening() }
    }

    private fun setStatus(text: String) {
        runOnUiThread { hint.text = text }
    }

    private fun prepareModel() {
        val modelDir = File(filesDir, "model/vosk-model-small-it-0.22")
        val okFile = File(filesDir, "model/ok")
        Thread {
            try {
                if (!okFile.exists()) {
                    setStatus("Scarico il cervello vocale (circa 50 MB), attendi...")
                    File(filesDir, "model").deleteRecursively()
                    val url = URL("https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip")
                    val conn = url.openConnection()
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    ZipInputStream(conn.getInputStream().buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val out = File(filesDir, "model/" + entry.name)
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
                setStatus("Carico il modello...")
                model = Model(modelDir.absolutePath)
                setStatus("Pronto. Tocca lo schermo e parla")
            } catch (e: Exception) {
                setStatus("Errore: " + e.message)
            }
        }.start()
    }

    private fun toggleListening() {
        val m = model
        if (m == null) {
            Toast.makeText(this, "Il modello non è ancora pronto", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (listening) {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            listening = false
            setStatus("In pausa. Tocca per parlare")
        } else {
            try {
                speechService = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
                speechService?.startListening(this)
                listening = true
                setStatus("Ti ascolto...")
            } catch (e: Exception) {
                setStatus("Errore microfono: " + e.message)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleListening()
        }
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        val text = try {
            JSONObject(hypothesis ?: "{}").optString("text")
        } catch (e: Exception) {
            ""
        }
        if (text.isNotBlank()) {
            setStatus("Hai detto: $text")
            speak(Commands(this).handle(text))
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        setStatus("Errore ascolto: " + exception?.message)
    }

    override fun onTimeout() {}

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
                runOnUiThread {
                    speechService?.setPause(true)
                    scanner.start()
                }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    scanner.stop()
                    if (listening) speechService?.setPause(false)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    scanner.stop()
                    if (listening) speechService?.setPause(false)
                }
            }
        })
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kitt")
        }
    }

    override fun onDestroy() {
        speechService?.shutdown()
        model?.close()
        tts?.shutdown()
        super.onDestroy()
    }
}