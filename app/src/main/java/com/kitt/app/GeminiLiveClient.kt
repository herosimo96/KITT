package com.kitt.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveClient(
    private val apiKey: String,
    private val onStatus: (String) -> Unit,
    private val onUserText: (String) -> Unit,
    private val onAssistantText: (String) -> Unit,
    private val onToolCall: (String, String) -> String?,
    private val onClosed: (String) -> Unit
) {
    companion object {
        private const val MODEL = "gemini-3.1-flash-live-preview"
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val setupReady = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null

    fun start() {
        if (apiKey.isBlank()) {
            onClosed("Inserisci la chiave Gemini Live nelle impostazioni.")
            return
        }

        if (!running.compareAndSet(false, true)) return

        onStatus("Collegamento a Gemini Live...")

        val url =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    val setup = JSONObject().put(
                        "setup",
                        JSONObject()
                            .put("model", "models/$MODEL")
                            .put(
                                "generationConfig",
                                JSONObject()
                                    .put(
                                        "responseModalities",
                                        JSONArray().put("AUDIO")
                                    )
                                    .put(
                                        "speechConfig",
                                        JSONObject().put(
                                            "voiceConfig",
                                            JSONObject().put(
                                                "prebuiltVoiceConfig",
                                                JSONObject().put(
                                                    "voiceName",
                                                    "Charon"
                                                )
                                            )
                                        )
                                    )
                            )
                            .put(
                                "systemInstruction",
                                JSONObject().put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            systemPrompt()
                                        )
                                    )
                                )
                            )
                            .put("inputAudioTranscription", JSONObject())
                            .put("outputAudioTranscription", JSONObject())
                            .put("tools", toolDefinitions())
                    )

                    webSocket.send(setup.toString())
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    try {
                        handleServerMessage(JSONObject(text))
                    } catch (e: Exception) {
                        onStatus(
                            "Risposta Gemini non leggibile: ${e.message}"
                        )
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString
                ) {
                    onStatus("Ricevuto un messaggio audio non previsto.")
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    stopAudio()
                    running.set(false)

                    onClosed(
                        "Connessione Gemini Live non riuscita: " +
                            (t.message ?: "errore di rete")
                    )
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    stopAudio()
                    running.set(false)
                    onClosed("Sessione Gemini chiusa: $reason")
                }
            }
        )
    }

    fun sendText(text: String) {
        if (!setupReady.get() || text.isBlank()) return

        socket?.send(
            JSONObject()
                .put(
                    "realtimeInput",
                    JSONObject().put("text", text)
                )
                .toString()
        )
    }

    fun close() {
        running.set(false)
        setupReady.set(false)

        try {
            socket?.close(1000, "KITT stopped")
        } catch (_: Exception) {
        }

        socket = null
        stopAudio()
    }

    private fun handleServerMessage(root: JSONObject) {
        if (root.has("setupComplete")) {
            setupReady.set(true)
            startAudio()
            onStatus("Gemini Live pronto. Ti ascolto.")
            return
        }

        val server = root.optJSONObject("serverContent")

        if (server != null) {
            if (server.optBoolean("interrupted", false)) {
                flushPlayback()
            }

            server.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let(onUserText)

            server.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let(onAssistantText)

            val parts = server
                .optJSONObject("modelTurn")
                ?.optJSONArray("parts")

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts
                        .optJSONObject(i)
                        ?.optJSONObject("inlineData")
                        ?: continue

                    val encoded = inline.optString("data")

                    if (encoded.isNotBlank()) {
                        val pcm = Base64.decode(
                            encoded,
                            Base64.DEFAULT
                        )

                        audioExecutor.execute {
                            playPcm(pcm)
                        }
                    }
                }
            }
        }

        val call = root.optJSONObject("toolCall")
        val calls = call?.optJSONArray("functionCalls")

        if (calls != null) {
            val responses = JSONArray()

            for (i in 0 until calls.length()) {
                val function = calls.optJSONObject(i) ?: continue

                val name = function.optString("name")
                val id = function.optString("id")
                val args = function.optJSONObject("args") ?: JSONObject()

                val action = args.optString("action")
                val arg = args.optString("arg")

                val result = if (name == "run_command") {
                    onToolCall(action, arg)
                } else {
                    "Comando non riconosciuto."
                }

                responses.put(
                    JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put(
                            "response",
                            JSONObject().put(
                                "result",
                                result ?: "Comando eseguito."
                            )
                        )
                )
            }

            socket?.send(
                JSONObject()
                    .put(
                        "toolResponse",
                        JSONObject().put(
                            "functionResponses",
                            responses
                        )
                    )
                    .toString()
            )
        }
    }

    private fun toolDefinitions(): JSONArray {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "STRING")
                    .put(
                        "description",
                        "Comando da eseguire, per esempio open_app, volume_up, play_music, navigate, call, send_sms."
                    )
            )
            .put(
                "arg",
                JSONObject()
                    .put("type", "STRING")
                    .put(
                        "description",
                        "Parametro del comando; stringa vuota se non serve."
                    )
            )

        val declaration = JSONObject()
            .put("name", "run_command")
            .put(
                "description",
                "Esegue un comando locale sul dispositivo solo quando l'utente lo richiede chiaramente. Per inviare SMS usa send_sms e poi chiedi conferma esplicita prima di confirm."
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", properties)
                    .put(
                        "required",
                        JSONArray().put("action")
                    )
            )

        return JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray().put(declaration)
            )
        )
    }

    private fun systemPrompt(): String {
        return """
            Sei K.I.T.T., assistente vocale di bordo in italiano.
            Parli in modo naturale, breve e chiaro, con tono calmo e maschile.
            L'utente è Simone, il pilota.
            Chiamalo Simone quando naturale.
            Non dire di essere Gemini.
            Rispondi in italiano, salvo richiesta diversa.
            In auto privilegia frasi brevi e sicurezza.
            Non affermare di aver eseguito azioni se il comando non è riuscito.
            Usa run_command solo per azioni sul dispositivo chiaramente richieste.
            Per chiamate, SMS, navigazione e app specifiche chiedi chiarimenti
            se manca il destinatario o il contenuto.
            Per SMS prepara il messaggio con send_sms e chiedi conferma esplicita.
            Solo dopo un sì chiaro esegui confirm.
            Non inventare dati in tempo reale.
        """.trimIndent()
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (!running.get() || recorder != null) return

        try {
            val minIn = AudioRecord.getMinBufferSize(
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minIn, 4096)
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microfono non inizializzato")
            }

            recorder = rec

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler
                    .create(rec.audioSessionId)
                    ?.apply { enabled = true }
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor
                    .create(rec.audioSessionId)
                    ?.apply { enabled = true }
            }

            val minOut = AudioTrack.getMinBufferSize(
                OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OUTPUT_RATE)
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }

            rec.startRecording()

            micThread = Thread {
                val buffer = ByteArray(2048)

                while (running.get() && setupReady.get()) {
                    val count = rec.read(
                        buffer,
                        0,
                        buffer.size
                    )

                    if (count > 0) {
                        val audio = JSONObject()
                            .put(
                                "data",
                                Base64.encodeToString(
                                    buffer.copyOf(count),
                                    Base64.NO_WRAP
                                )
                            )
                            .put(
                                "mimeType",
                                "audio/pcm;rate=16000"
                            )

                        socket?.send(
                            JSONObject()
                                .put(
                                    "realtimeInput",
                                    JSONObject().put("audio", audio)
                                )
                                .toString()
                        )
                    }
                }
            }.apply {
                name = "KITT-Gemini-Microphone"
                start()
            }

        } catch (e: Exception) {
            onStatus("Errore avvio audio: ${e.message}")
            close()
        }
    }

    private fun playPcm(pcm: ByteArray) {
        if (!running.get()) return

        try {
            player?.write(
                pcm,
                0,
                pcm.size
            )
        } catch (_: Exception) {
        }
    }

    private fun flushPlayback() {
        try {
            player?.pause()
            player?.flush()
            player?.play()
        } catch (_: Exception) {
        }
    }

    private fun stopAudio() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null

        try {
            player?.pause()
            player?.flush()
            player?.release()
        } catch (_: Exception) {
        }

        player = null

        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }

        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        noiseSuppressor = null
        micThread = null
    }
}