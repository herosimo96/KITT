package com.kitt.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var txtStatus: TextView
    private lateinit var txtChat: TextView
    private lateinit var edtInput: EditText
    private lateinit var btnSend: Button

    // Inserisci qui la tua chiave o recuperala dalle preferenze
    private var apiKey = "TUA_CHIAVE_API_QUI"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtChat = findViewById(R.id.txtChat)
        edtInput = findViewById(R.id.edtInput)
        btnSend = findViewById(R.id.btnSend)

        // Inizializza il motore vocale integrato
        tts = TextToSpeech(this, this)

        txtStatus.text = "KITT pronto (Modalità HTTP + Audio)"

        btnSend.setOnClickListener {
            val message = edtInput.text.toString().trim()
            if (message.isNotEmpty()) {
                appendChat("Simone: $message")
                edtInput.text.clear()
                sendToKitt(message)
            }
        }
    }

    private fun sendToKitt(prompt: String) {
        txtStatus.text = "KITT sta elaborando..."
        GeminiRestClient.sendMessage(apiKey, prompt) { responseText ->
            runOnUiThread {
                if (responseText != null) {
                    appendChat("KITT: $responseText")
                    txtStatus.text = "Pronto"
                    speakOut(responseText)
                } else {
                    txtStatus.text = "Errore di risposta"
                }
            }
        }
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "KittSpeechId")
    }

    private fun appendChat(text: String) {
        val current = txtChat.text.toString()
        txtChat.text = "$current\n$text\n"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.ITALIAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.FMakeText(this, "Lingua italiana non supportata dal TTS", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Inizializzazione voce fallita", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
