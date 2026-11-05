package com.kitt.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BrainResult(val action: String, val arg: String, val reply: String)

private const val MODEL = "openai/gpt-oss-20b"

private const val SYSTEM = """Sei KITT, l'auto parlante della serie Supercar, assistente vocale su un dispositivo Android. Parli in italiano con tono elegante, spiritoso e un po' ironico, con frasi brevi (massimo 2 frasi). Il testo che ricevi viene da un riconoscimento vocale e puo' contenere errori: interpreta l'intenzione dell'utente. Rispondi SOLO con un oggetto JSON, senza altro testo, con questi campi: {"action": "...", "arg": "...", "reply": "..."}. Valori possibili di action: open_app (arg = nome dell'app), volume_up, volume_down, volume_set (arg = numero da 0 a 100), next_track, previous_track, pause, play, none. Usa none quando l'utente vuole solo chiacchierare o fare domande. Il campo reply e' cio' che dici ad alta voce."""

class Brain(private val context: Context) {
    private val history = mutableListOf<Pair<String, String>>()

    private fun apiKey(): String =
        context.getSharedPreferences("kitt", Context.MODE_PRIVATE)
            .getString("groq_key", "") ?: ""

    fun hasKey(): Boolean = apiKey().isNotBlank()

    fun think(userText: String): BrainResult {
        try {
            history.add("user" to userText)
            while (history.size > 8) history.removeAt(0)

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM))
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
            return BrainResult(
                j.optString("action", "none"),
                j.optString("arg", ""),
                j.optString("reply", "")
            )
        } catch (e: Exception) {
            return BrainResult("none", "", "Errore: " + e.message)
        }
    }
}