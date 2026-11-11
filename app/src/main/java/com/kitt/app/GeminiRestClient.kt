package com.kitt.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiRestClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MODEL = "gemini-2.0-flash"

    fun sendMessage(apiKey: String, prompt: String, onResult: (String?) -> Unit) {
        if (apiKey.isBlank()) {
            onResult("Errore: Manca la chiave API di Gemini.")
            return
        }

        Thread {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"

                val contentsArray = JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )

                val systemInstruction = JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Sei K.I.T.T., l'assistente vocale di bordo di Simone. Parla esclusivamente in italiano, con tono calmo, professionale e sintetico, ideale per un'autoradio. Non dire di essere un modello Google o Gemini."
                        )
                    )
                )

                val requestBodyJson = JSONObject()
                    .put("contents", contentsArray)
                    .put("systemInstruction", systemInstruction)

                val body = requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onResult("Errore del server: ${response.code}")
                        return@Thread
                    }

                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        onResult("Risposta vuota da Gemini.")
                        return@Thread
                    }

                    val jsonRoot = JSONObject(responseBody)
                    val candidates = jsonRoot.optJSONArray("candidates")
                    val text = candidates?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")

                    if (!text.isNullOrBlank()) {
                        onResult(text)
                    } else {
                        onResult("Impossibile interpretare la risposta.")
                    }
                }
            } catch (e: Exception) {
                onResult("Errore di connessione: ${e.message}")
            }
        }.start()
    }
}
