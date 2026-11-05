package com.kitt.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

class Commands(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun handle(raw: String): String {
        val t = raw.lowercase().trim()
        return when {
            t.startsWith("apri ") -> openApp(t.removePrefix("apri ").trim())
            "volume" in t && ("alza" in t || "aumenta" in t) -> {
                repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
                "Volume alzato"
            }
            "volume" in t && ("abbassa" in t || "diminuisci" in t) -> {
                repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
                "Volume abbassato"
            }
            "successiv" in t || "prossim" in t -> { media(KeyEvent.KEYCODE_MEDIA_NEXT); "Canzone successiva" }
            "precedent" in t -> { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Canzone precedente" }
            "pausa" in t -> { media(KeyEvent.KEYCODE_MEDIA_PAUSE); "Musica in pausa" }
            "riproduci" in t || "riprendi" in t || "play" in t -> { media(KeyEvent.KEYCODE_MEDIA_PLAY); "Riproduco" }
            else -> "Hai detto: $raw. Non conosco ancora questo comando."
        }
    }

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun openApp(name: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(name) || name.contains(label)
        }
        if (match == null) return "Non trovo l'app $name"
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Non riesco ad aprire $name"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Apro " + match.loadLabel(pm)
    }
}