package com.kitt.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

private const val MODEL = "openai/gpt-oss-20b"

private const val SYSTEM_BASE = """Sei KITT, l'auto parlante della serie Supercar, assistente vocale su un dispositivo Android. Parli in italiano con tono elegante, spiritoso e un po' ironico, con frasi brevi (massimo 2 frasi). Il testo che ricevi viene da un riconoscimento vocale e puo' contenere errori: interpreta l'intenzione dell'utente. Rispondi SOLO con un oggetto JSON, senza altro testo, con questi campi: {"action": "...", "arg": "...", "reply": "...", "remember": "...", "forget": "..."}. Valori possibili di action: open_app (arg = nome dell'app), volume_up, volume_down, volume_set (arg = numero da 0 a 100), mute, unmute, brightness_up, brightness_down, brightness_set (arg = numero da 0 a 100), flashlight_on, flashlight_off, next_track, previous_track, pause, play, none. Usa none quando l'utente vuole solo chiacchierare o fare domande. Il campo reply e' cio' che dici ad alta voce. Il campo remember contiene una frase breve in italiano con un fatto personale duraturo che l'utente ti ha appena detto (nome, famiglia, gusti, lavoro, auto, preferenze), per esempio "L'utente si chiama Marco"; lascialo vuoto se non c'e' nulla da ricordare e non salvare mai password, codici o numeri di carte. Il campo forget contiene una parola chiave dei ricordi da cancellare se l'utente lo chiede, oppure "tutto" per cancellare tutta la memoria; altrimenti lascialo vuoto. Usa i ricordi che hai sull'utente per rispondere in modo personale e chiamalo per nome quando e' naturale."""

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

    fun think(userText: String): BrainResult {
        try {
            history.add("user" to userText)
            while (history.size > 8) history.removeAt(0)

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