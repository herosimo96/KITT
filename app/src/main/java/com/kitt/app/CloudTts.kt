package com.kitt.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudTts {

    // Restituisce (audio PCM 16 bit mono, frequenza di campionamento)
    fun synth(key: String, model: String, voice: String, text: String): Pair<ByteArray, Int> {
        val part = JSONObject().put("text", text)
        val contents = JSONArray().put(JSONObject().put("parts", JSONArray().put(part)))
        val speech = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
        )
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speech)
            )

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code != 200) {
            val err = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            val msg = try {
                JSONObject(err).getJSONObject("error").optString("message")
            } catch (e: Exception) {
                ""
            }
            throw Exception("errore " + code + ". " + msg)
        }

        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val parts = resp.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        var data = ""
        var mime = ""
        for (i in 0 until parts.length()) {
            val inl = parts.getJSONObject(i).optJSONObject("inlineData")
            if (inl != null) {
                data = inl.optString("data")
                mime = inl.optString("mimeType")
                break
            }
        }
        if (data.isEmpty()) throw Exception("nessun audio nella risposta")
        return decode(Base64.decode(data, Base64.DEFAULT), mime)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun decode(bytes: ByteArray, mime: String): Pair<ByteArray, Int> {
        val isWav = bytes.size > 44 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        if (isWav) {
            val rate = le32(bytes, 24)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val len = le32(bytes, pos + 4)
                if (id == "data") {
                    val start = pos + 8
                    val end = if (len <= 0 || start + len > bytes.size) bytes.size else start + len
                    return Pair(bytes.copyOfRange(start, end), if (rate > 0) rate else 24000)
                }
                pos += 8 + maxOf(len, 0)
            }
            return Pair(bytes.copyOfRange(44, bytes.size), if (rate > 0) rate else 24000)
        }
        val m = Regex("rate=(\\d+)").find(mime)
        val rate = m?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        return Pair(bytes, rate)
    }
}