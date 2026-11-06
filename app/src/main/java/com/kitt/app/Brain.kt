package com.kitt.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BrainResult(
    val action: String,
    val arg: String,
    val reply: String,
    val remember: String = "",
    val forget: String = ""
)

private const val MODEL = "openai/gpt-oss-120b"

private const val SYSTEM_BASE = """Sei KITT, l'auto parlante della serie Supercar, assistente vocale su un dispositivo Android. Parli in italiano con tono elegante, spiritoso e un po' ironico, con frasi brevi (massimo 2 frasi). Il testo che ricevi viene da un riconoscimento vocale e puo' contenere errori: interpreta l'intenzione dell'utente. Rispondi SOLO con un oggetto JSON, senza altro testo, con questi campi: {"action": "...", "arg": "...", "reply": "...", "remember": "...", "forget": "..."}. Valori possibili di action e formato di arg: open_app (nome dell'app); volume_up; volume_down; volume_set (numero da 0 a 100); mute; unmute; brightness_up; brightness_down; brightness_set (numero da 0 a 100); flashlight_on; flashlight_off; next_track; previous_track; pause; play; stop; play_music (brano, artista o playlist da cercare); call (nome del contatto o numero); send_sms (formato esatto: nome|testo del messaggio); confirm (quando l'utente conferma l'invio di un messaggio, per esempio dice si' o invia); cancel (quando l'utente annulla o dice no); whatsapp (formato esatto: nome|testo del messaggio); navigate (indirizzo o luogo); web_search (testo da cercare); open_url (indirizzo del sito); youtube (testo da cercare); set_alarm (orario nel formato HH:MM a 24 ore); set_timer (durata in secondi); open_settings (una tra wifi, bluetooth, display, sound, location, oppure vuoto per le impostazioni generali); open_camera; go_home; battery; none. Usa none quando l'utente vuole solo chiacchierare o fare domande. Non puoi accendere o spegnere direttamente Wi-Fi, Bluetooth o GPS: in quel caso usa open_settings e dillo. Per send_sms il testo che pronunci in reply verra' sostituito dalla richiesta di conferma, quindi non inviare mai nulla senza che l'utente abbia detto di si'. Il campo reply e' cio' che dici ad alta voce. Il campo remember contiene una frase breve in italiano con un fatto personale duraturo che l'utente ti ha appena detto, per esempio "L'utente si chiama Marco" oppure "L'utente ha una Fiat Panda del 2015": se l'utente ti dice qualcosa su di se', sulla sua famiglia, sulla sua auto, sui suoi gusti o sul suo lavoro, riempi SEMPRE remember, anche mentre esegui un'azione o rispondi. Lascialo vuoto solo se non c'e' nulla da ricordare e non salvare mai password, codici o numeri di carte. Il campo forget contiene una parola chiave dei ricordi da cancellare se l'utente lo chiede, oppure "tutto" per cancellare tutta la memoria; altrimenti lascialo vuoto. Usa i ricordi che hai sull'utente per rispondere in modo personale e chiamalo per nome quando e' naturale."""

class Brain(private val context: Context) {
    private val history = mutableListOf<Pair<String, String>>()

    private fun prefs() = context.getSharedPreferences("kitt", Context.MODE_PRIVATE)

    private fun apiKey(): String = prefs().getString("groq_key", "") ?: ""

    fun hasKey(): Boolean = apiKey().isNotBlank()

    private fun loadMemory(): String = prefs().getString("memory", "") ?: ""

    private fun updateMemory(remember: String, forget: String) {
        val lines = loadMemory().split("\n").filter { it.isNotBlank() }.toMutableList()
        if (forget.isNotBlank()) {
            val f = forget.lowercase().trim()
            if (f == "tutto") {
                lines.clear()
            } else {
                lines.removeAll { it.lowercase().contains(f) }
            }
        }
        if (remember.isNotBlank() && lines.none { it.equals(remember.trim(), ignoreCase = true) }) {
            lines.add(remember.trim())
        }
        while (lines.size > 40) lines.removeAt(0)
        prefs().edit().putString("memory", lines.joinToString("\n")).apply()
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN).format(Date())
        val mem = loadMemory()
        val memText = if (mem.isBlank()) {
            "Non sai ancora nulla dell'utente."
        } else {
            "Cose che sai dell'utente:\n$mem"
        }
        return SYSTEM_BASE + "\nData e ora attuali: " + now + ".\n" + memText
    }

    // ---------- ascolto: trascrizione con Whisper su Groq ----------

    private fun wav(pcm: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(pcm.size + 36)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(1.toShort())
        b.putInt(16000)
        b.putInt(32000)
        b.putShort(2.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray())
        b.putInt(pcm.size)
        b.put(pcm)
        return b.array()
    }

    // Restituisce (testo, errore)
    fun transcribe(pcm: ByteArray): Pair<String, String> {
        try {
            val boundary = "kitt" + System.currentTimeMillis()
            val mem = loadMemory().replace("\n", " ")
            val tail = if (mem.length > 300) mem.takeLast(300) else mem
            val prompt = "Conversazione in italiano con KITT, assistente vocale dell'auto. " +
                    "Parole possibili: KITT, Spotify, WhatsApp, YouTube, rubrica, volume, luminosità. " + tail

            val conn = URL("https://api.groq.com/openai/v1/audio/transcriptions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + apiKey())
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)

            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(
                        ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" +
                                name + "\"\r\n\r\n" + value + "\r\n").toByteArray()
                    )
                }
                field("model", "whisper-large-v3-turbo")
                field("language", "it")
                field("response_format", "json")
                field("temperature", "0")
                field("prompt", prompt)
                out.write(
                    ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; " +
                            "filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray()
                )
                out.write(wav(pcm))
                out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }

            val code = conn.responseCode
            if (code != 200) {
                val detail = try {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    JSONObject(err).getJSONObject("error").optString("message")
                } catch (e: Exception) {
                    ""
                }
                val msg = when (code) {
                    401 -> "Chiave non valida: tieni premuto lo schermo per reinserirla."
                    429 -> "Troppe richieste, aspetta un minuto."
                    else -> "Trascrizione, errore $code. $detail"
                }
                return Pair("", msg)
            }

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val text = json.optString("text").trim()
            val low = text.lowercase()
            if (low.contains("sottotitoli") || low.contains("amara.org")) {
                return Pair("", "")
            }
            return Pair(text, "")
        } catch (e: Exception) {
            return Pair("", "Errore di rete: " + e.message)
        }
    }

    // ---------- cervello ----------

    fun think(userText: String): BrainResult {
        try {
            history.add("user" to userText)
            while (history.size > 12) history.removeAt(0)

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("temperature", 0.7)
                .put("max_tokens", 1500)
                .put("reasoning_effort", "low")
                .put("response_format", JSONObject().put("type", "json_object"))

            val conn = URL("https://api.groq.com/openai/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + apiKey())
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code != 200) {
                history.removeAt(history.size - 1)
                val detail = try {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    JSONObject(err).getJSONObject("error").optString("message")
                } catch (e: Exception) {
                    ""
                }
                val msg = when (code) {
                    401 -> "La chiave non è valida."
                    429 -> "Ho parlato troppo, riprova tra un minuto."
                    else -> "Errore $code. $detail"
                }
                return BrainResult("none", "", msg)
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val j = JSONObject(content)
            history.add("assistant" to content)

            val remember = j.optString("remember", "")
            val forget = j.optString("forget", "")
            if (remember.isNotBlank() || forget.isNotBlank()) {
                updateMemory(remember, forget)
            }

            return BrainResult(
                j.optString("action", "none"),
                j.optString("arg", ""),
                j.optString("reply", ""),
                remember,
                forget
            )
        } catch (e: Exception) {
            return BrainResult("none", "", "Errore: " + e.message)
        }
    }
}