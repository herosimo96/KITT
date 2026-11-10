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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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
        private const val MODEL = "gemini-3.8-live"
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
    private val closedByUser = AtomicBoolean(false)

    private val pendingLock = Any()
    private val pendingTexts = ArrayList<String>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null

    fun start() {
        if (apiKey.isBlank()) {
            onClosed("Manca la chiave API di Gemini.")
            return
        }

        if (!running.compareAndSet(false, true)) return

        closedByUser.set(false)
        setupReady.set(false)
        onStatus("Connessione a Gemini Live...")

        try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val url =
                "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService." +
                "BidiGenerateContent?key=$encodedKey"

            val request = Request.Builder().url(url).build()

            socket = client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {
                        onStatus("Configurazione Gemini Live...")

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
                                .put(
                                    "inputAudioTranscription",
                                    JSONObject()
                                )
                                .put(
                                    "outputAudioTranscription",
                                    JSONObject()
                                )
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
                                "Risposta Gemini non valida: ${e.message}"
                            )
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        stopAudio()
                        setupReady.set(false)
                        running.set(false)

                        val detail = response?.let {
                            "HTTP ${it.code}: ${it.message}"
                        } ?: (t.message ?: "errore di rete")

                        if (!closedByUser.get()) {
                            onClosed("Errore Gemini Live: $detail")
                        }
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        stopAudio()
                        setupReady.set(false)
                        running.set(false)

                        if (!closedByUser.get()) {
                            onClosed(
                                "Gemini Live ha chiuso la sessione: $reason"
                            )
                        }
                    }
                }
            )
        } catch (e: Exception) {
            running.set(false)
            onClosed("Errore di connessione: ${e.message}")
        }
    }

    fun sendText(text: String) {
        if (text.isBlank() || !running.get()) return

        if (!setupReady.get()) {
            synchronized(pendingLock) {
                pendingTexts.add(text)
            }
            return
        }

        sendTextNow(text)
    }

    private fun sendTextNow(text: String) {
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("text", text)
        )
        socket?.send(message.toString())
    }

    private fun flushPendingTexts() {
        val messages = synchronized(pendingLock) {
            val copy = pendingTexts.toList()
            pendingTexts.clear()
            copy
        }

        messages.forEach { sendTextNow(it) }
    }

    fun close() {
        closedByUser.set(true)
        running.set(false)
        setupReady.set(false)

        synchronized(pendingLock) {
            pendingTexts.clear()
        }

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
            onStatus("Gemini Live collegato. Microfono attivo.")
            startAudio()
            flushPendingTexts()
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
                ?.let { onUserText(it) }

            server.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { onAssistantText(it) }

            val parts = server.optJSONObject("modelTurn")
                ?.optJSONArray("parts")

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: continue
                    val encoded = inline.optString("data")

                    if (encoded.isNotBlank()) {
                        val pcm = Base64.decode(encoded, Base64.DEFAULT)
                        audioExecutor.execute { playPcm(pcm) }
                    }
                }
            }
        }

        handleToolCalls(root)
    }

    private fun handleToolCalls(root: JSONObject) {
        val call = root.optJSONObject("toolCall") ?: return
        val calls = call.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()

        for (i in 0 until calls.length()) {
            val function = calls.optJSONObject(i) ?: continue
            val name = function.optString("name")
            val id = function.optString("id")
            val args = function.optJSONObject("args") ?: JSONObject()

            val action = args.optString("action")
            val arg = args.optString("arg")

            val result = try {
                if (name == "run_command") {
                    onToolCall(action, arg)
                } else {
                    "Comando non riconosciuto."
                }
            } catch (_: Exception) {
                "Errore durante l'esecuzione del comando."
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
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", responses)
            ).toString()
        )
    }

    private fun toolDefinitions(): JSONArray {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "STRING")
                    .put(
                        "description",
                        "Comando locale da eseguire sul dispositivo."
                    )
            )
            .put(
                "arg",
                JSONObject()
                    .put("type", "STRING")
                    .put(
                        "description",
                        "Parametro del comando."
                    )
            )

        val declaration = JSONObject()
            .put("name", "run_command")
            .put(
                "description",
                "Esegue un comando locale solo quando Simone lo richiede chiaramente."
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", properties)
                    .put("required", JSONArray().put("action"))
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
            Sei K.I.T.T., l'assistente vocale di bordo di Simone.

            Parli esclusivamente in italiano, con voce maschile,
            naturale, calma e chiara.

            L'utente è Simone, il pilota.

            Il microfono può restare attivo anche quando Simone
            non sta parlando direttamente con te.

            Non intervenire sulle conversazioni ambientali.
            Rispondi quando riconosci un richiamo rivolto a te,
            come:
            - Ciao KITT
            - Ehi KITT
            - Hey KITT
            - Buongiorno KITT
            - Buonasera KITT
            - KITT
            oppure quando Simone ti rivolge chiaramente una domanda
            o un comando.

            Dopo un saluto, rispondi brevemente e resta disponibile
            per il comando successivo.

            Mantieni il dialogo naturale senza richiedere ogni volta
            di ripetere il tuo nome.

            In automobile privilegia risposte brevi e la sicurezza.

            Non dire di essere Gemini.
            Non affermare di aver eseguito un'azione se non è riuscita.

            Usa run_command solo per azioni sul dispositivo
            chiaramente richieste da Simone.

            Per chiamate, SMS e azioni importanti chiedi conferma
            quando necessario.

            Per inviare SMS prepara prima il messaggio e chiedi
            conferma esplicita prima di inviare.

            Non inventare informazioni in tempo reale.
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

            if (minIn <= 0) {
                throw IllegalStateException(
                    "Configurazione microfono non supportata"
                )
            }

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minIn, 8192)
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
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

            if (minOut <= 0) {
                throw IllegalStateException(
                    "Configurazione audio in uscita non supportata"
                )
            }

            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OUTPUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }

            rec.startRecording()

            micThread = Thread {
                val buffer = ByteArray(2048)

                try {
                    while (running.get() && setupReady.get()) {
                        val count = rec.read(buffer, 0, buffer.size)

                        if (count > 0) {
                            val audio = JSONObject()
                                .put(
                                    "data",
                                    Base64.encodeToString(
                                        buffer.copyOf(count),
                                        Base64.NO_WRAP
                                    )
                                )
                                .put("mimeType", "audio/pcm;rate=16000")

                            val message = JSONObject().put(
                                "realtimeInput",
                                JSONObject().put("audio", audio)
                            )

                            socket?.send(message.toString())
                        } else if (count < 0) {
                            throw IllegalStateException(
                                "Errore lettura microfono: $count"
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        onStatus("Problema microfono: ${e.message}")
                    }
                }
            }.apply {
                name = "KITT-Gemini-Microphone"
                start()
            }
        } catch (e: Exception) {
            onStatus("Errore avvio audio: ${e.message}")
            running.set(false)
            stopAudio()
            socket?.close(1011, "Audio initialization failed")
            onClosed("Impossibile avviare il microfono: ${e.message}")
        }
    }

    private fun playPcm(pcm: ByteArray) {
        if (!running.get()) return

        try {
            player?.write(pcm, 0, pcm.size)
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
        val oldRecorder = recorder
        recorder = null

        try {
            oldRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            oldRecorder?.release()
        } catch (_: Exception) {
        }

        val oldPlayer = player
        player = null

        try {
            oldPlayer?.pause()
            oldPlayer?.flush()
            oldPlayer?.release()
        } catch (_: Exception) {
        }

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

        micThread?.interrupt()
        micThread = null
    }
}package com.kitt.app

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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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
        private const val MODEL = "gemini-3.8-live"
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
    private val closedByUser = AtomicBoolean(false)

    private val pendingLock = Any()
    private val pendingTexts = ArrayList<String>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null

    fun start() {
        if (apiKey.isBlank()) {
            onClosed("Manca la chiave API di Gemini.")
            return
        }

        if (!running.compareAndSet(false, true)) return

        closedByUser.set(false)
        setupReady.set(false)
        onStatus("Connessione a Gemini Live...")

        try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val url =
                "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService." +
                "BidiGenerateContent?key=$encodedKey"

            val request = Request.Builder().url(url).build()

            socket = client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {
                        onStatus("Configurazione Gemini Live...")

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
                                .put(
                                    "inputAudioTranscription",
                                    JSONObject()
                                )
                                .put(
                                    "outputAudioTranscription",
                                    JSONObject()
                                )
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
                                "Risposta Gemini non valida: ${e.message}"
                            )
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        stopAudio()
                        setupReady.set(false)
                        running.set(false)

                        val detail = response?.let {
                            "HTTP ${it.code}: ${it.message}"
                        } ?: (t.message ?: "errore di rete")

                        if (!closedByUser.get()) {
                            onClosed("Errore Gemini Live: $detail")
                        }
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        stopAudio()
                        setupReady.set(false)
                        running.set(false)

                        if (!closedByUser.get()) {
                            onClosed(
                                "Gemini Live ha chiuso la sessione: $reason"
                            )
                        }
                    }
                }
            )
        } catch (e: Exception) {
            running.set(false)
            onClosed("Errore di connessione: ${e.message}")
        }
    }

    fun sendText(text: String) {
        if (text.isBlank() || !running.get()) return

        if (!setupReady.get()) {
            synchronized(pendingLock) {
                pendingTexts.add(text)
            }
            return
        }

        sendTextNow(text)
    }

    private fun sendTextNow(text: String) {
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("text", text)
        )
        socket?.send(message.toString())
    }

    private fun flushPendingTexts() {
        val messages = synchronized(pendingLock) {
            val copy = pendingTexts.toList()
            pendingTexts.clear()
            copy
        }

        messages.forEach { sendTextNow(it) }
    }

    fun close() {
        closedByUser.set(true)
        running.set(false)
        setupReady.set(false)

        synchronized(pendingLock) {
            pendingTexts.clear()
        }

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
            onStatus("Gemini Live collegato. Microfono attivo.")
            startAudio()
            flushPendingTexts()
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
                ?.let { onUserText(it) }

            server.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { onAssistantText(it) }

            val parts = server.optJSONObject("modelTurn")
                ?.optJSONArray("parts")

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: continue
                    val encoded = inline.optString("data")

                    if (encoded.isNotBlank()) {
                        val pcm = Base64.decode(encoded, Base64.DEFAULT)
                        audioExecutor.execute { playPcm(pcm) }
                    }
                }
            }
        }

        handleToolCalls(root)
    }

    private fun handleToolCalls(root: JSONObject) {
        val call = root.optJSONObject("toolCall") ?: return
        val calls = call.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()

        for (i in 0 until calls.length()) {
            val function = calls.optJSONObject(i) ?: continue
            val name = function.optString("name")
            val id = function.optString("id")
            val args = function.optJSONObject("args") ?: JSONObject()

            val action = args.optString("action")
            val arg = args.optString("arg")

            val result = try {
                if (name == "run_command") {
                    onToolCall(action, arg)
                } else {
                    "Comando non riconosciuto."
                }
            } catch (_: Exception) {
                "Errore durante l'esecuzione del comando."
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
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", responses)
            ).toString()
        )
    }

    private fun toolDefinitions(): JSONArray {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "STRING")
                    .put(
                        "description",
                        "Comando locale da eseguire sul dispositivo."
                    )
            )
            .put(
                "arg",
                JSONObject()
                    .put("type", "STRING")
                    .put(
                        "description",
                        "Parametro del comando."
                    )
            )

        val declaration = JSONObject()
            .put("name", "run_command")
            .put(
                "description",
                "Esegue un comando locale solo quando Simone lo richiede chiaramente."
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", properties)
                    .put("required", JSONArray().put("action"))
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
            Sei K.I.T.T., l'assistente vocale di bordo di Simone.

            Parli esclusivamente in italiano, con voce maschile,
            naturale, calma e chiara.

            L'utente è Simone, il pilota.

            Il microfono può restare attivo anche quando Simone
            non sta parlando direttamente con te.

            Non intervenire sulle conversazioni ambientali.
            Rispondi quando riconosci un richiamo rivolto a te,
            come:
            - Ciao KITT
            - Ehi KITT
            - Hey KITT
            - Buongiorno KITT
            - Buonasera KITT
            - KITT
            oppure quando Simone ti rivolge chiaramente una domanda
            o un comando.

            Dopo un saluto, rispondi brevemente e resta disponibile
            per il comando successivo.

            Mantieni il dialogo naturale senza richiedere ogni volta
            di ripetere il tuo nome.

            In automobile privilegia risposte brevi e la sicurezza.

            Non dire di essere Gemini.
            Non affermare di aver eseguito un'azione se non è riuscita.

            Usa run_command solo per azioni sul dispositivo
            chiaramente richieste da Simone.

            Per chiamate, SMS e azioni importanti chiedi conferma
            quando necessario.

            Per inviare SMS prepara prima il messaggio e chiedi
            conferma esplicita prima di inviare.

            Non inventare informazioni in tempo reale.
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

            if (minIn <= 0) {
                throw IllegalStateException(
                    "Configurazione microfono non supportata"
                )
            }

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minIn, 8192)
            )

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
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

            if (minOut <= 0) {
                throw IllegalStateException(
                    "Configurazione audio in uscita non supportata"
                )
            }

            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OUTPUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }

            rec.startRecording()

            micThread = Thread {
                val buffer = ByteArray(2048)

                try {
                    while (running.get() && setupReady.get()) {
                        val count = rec.read(buffer, 0, buffer.size)

                        if (count > 0) {
                            val audio = JSONObject()
                                .put(
                                    "data",
                                    Base64.encodeToString(
                                        buffer.copyOf(count),
                                        Base64.NO_WRAP
                                    )
                                )
                                .put("mimeType", "audio/pcm;rate=16000")

                            val message = JSONObject().put(
                                "realtimeInput",
                                JSONObject().put("audio", audio)
                            )

                            socket?.send(message.toString())
                        } else if (count < 0) {
                            throw IllegalStateException(
                                "Errore lettura microfono: $count"
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        onStatus("Problema microfono: ${e.message}")
                    }
                }
            }.apply {
                name = "KITT-Gemini-Microphone"
                start()
            }
        } catch (e: Exception) {
            onStatus("Errore avvio audio: ${e.message}")
            running.set(false)
            stopAudio()
            socket?.close(1011, "Audio initialization failed")
            onClosed("Impossibile avviare il microfono: ${e.message}")
        }
    }

    private fun playPcm(pcm: ByteArray) {
        if (!running.get()) return

        try {
            player?.write(pcm, 0, pcm.size)
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
        val oldRecorder = recorder
        recorder = null

        try {
            oldRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            oldRecorder?.release()
        } catch (_: Exception) {
        }

        val oldPlayer = player
        player = null

        try {
            oldPlayer?.pause()
            oldPlayer?.flush()
            oldPlayer?.release()
        } catch (_: Exception) {
        }

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

        micThread?.interrupt()
        micThread = null
    }
}