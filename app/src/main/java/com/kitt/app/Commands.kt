package com.kitt.app

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent

class Commands(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Esegue l'azione scelta dall'AI. Restituisce null se tutto ok,
    // oppure un messaggio da dire se qualcosa non è andato.
    fun run(action: String, arg: String): String? {
        return when (action) {
            "open_app" -> {
                val r = openApp(arg.lowercase().trim())
                if (r.startsWith("Apro ")) null else r
            }
            "volume_up" -> { changeVolume(true); null }
            "volume_down" -> { changeVolume(false); null }
            "volume_set" -> { setVolume(arg.toIntOrNull() ?: 50); null }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                null
            }
            "unmute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                null
            }
            "brightness_up" -> {
                if (!Settings.System.canWrite(context)) askWritePermission()
                else { setBrightness(getBrightness() + 50); null }
            }
            "brightness_down" -> {
                if (!Settings.System.canWrite(context)) askWritePermission()
                else { setBrightness(getBrightness() - 50); null }
            }
            "brightness_set" -> {
                if (!Settings.System.canWrite(context)) askWritePermission()
                else {
                    val p = (arg.toIntOrNull() ?: 50).coerceIn(0, 100)
                    setBrightness(255 * p / 100)
                    null
                }
            }
            "flashlight_on" -> torch(true)
            "flashlight_off" -> torch(false)
            "next_track" -> { media(KeyEvent.KEYCODE_MEDIA_NEXT); null }
            "previous_track" -> { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); null }
            "pause" -> { media(KeyEvent.KEYCODE_MEDIA_PAUSE); null }
            "play" -> { media(KeyEvent.KEYCODE_MEDIA_PLAY); null }
            else -> null
        }
    }

    // Comandi di emergenza, quando manca la chiave o internet
    fun handle(raw: String): String {
        val t = raw.lowercase().trim()
        return when {
            t.startsWith("apri ") -> openApp(t.removePrefix("apri ").trim())
            "volume" in t && ("alza" in t || "aumenta" in t) -> changeVolume(true)
            "volume" in t && ("abbassa" in t || "diminuisci" in t) -> changeVolume(false)
            "successiv" in t || "prossim" in t -> { media(KeyEvent.KEYCODE_MEDIA_NEXT); "Canzone successiva" }
            "precedent" in t -> { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Canzone precedente" }
            "pausa" in t -> { media(KeyEvent.KEYCODE_MEDIA_PAUSE); "Musica in pausa" }
            "riproduci" in t || "riprendi" in t || "play" in t -> { media(KeyEvent.KEYCODE_MEDIA_PLAY); "Riproduco" }
            else -> "Hai detto: $raw. Per capire frasi libere serve la chiave Groq."
        }
    }

    private fun changeVolume(up: Boolean): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 5)
        val target = (if (up) before + step else before - step).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Volume $after su $max"
    }

    private fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
    }

    private fun getBrightness(): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)

    private fun setBrightness(value: Int) {
        val v = value.coerceIn(5, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
    }

    private fun askWritePermission(): String {
        val i = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:" + context.packageName)
        )
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return "Per regolare la luminosità mi serve un permesso: attivalo nella schermata che si è aperta, poi ripetimi il comando."
    }

    private fun torch(on: Boolean): String? {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Questo dispositivo non ha il flash"
            cm.setTorchMode(id, on)
            null
        } catch (e: Exception) {
            "Non riesco ad usare la torcia"
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