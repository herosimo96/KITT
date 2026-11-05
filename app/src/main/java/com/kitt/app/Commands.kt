package com.kitt.app

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class Commands(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pendingSms: Triple<String, String, String>? = null

    private val permMsg = "Mi servono dei permessi: concedili e ripeti il comando."

    // Esegue l'azione scelta dall'AI. Restituisce null se tutto ok
    // (KITT dice la risposta dell'AI), oppure un messaggio da dire al suo posto.
    fun run(action: String, arg: String): String? {
        return try {
            execute(action, arg.trim())
        } catch (e: Exception) {
            "Non sono riuscito a eseguire il comando."
        }
    }

    private fun execute(action: String, arg: String): String? {
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
            "stop" -> { media(KeyEvent.KEYCODE_MEDIA_STOP); null }
            "play_music" -> playMusic(arg)
            "call" -> call(arg)
            "send_sms" -> prepareSms(arg)
            "confirm" -> confirmSms()
            "cancel" -> { pendingSms = null; null }
            "whatsapp" -> whatsapp(arg)
            "navigate" -> view("geo:0,0?q=" + Uri.encode(arg))
            "web_search" -> view("https://duckduckgo.com/?q=" + Uri.encode(arg))
            "open_url" -> view(if (arg.startsWith("http")) arg else "https://$arg")
            "youtube" -> view("https://www.youtube.com/results?search_query=" + Uri.encode(arg))
            "set_alarm" -> setAlarm(arg)
            "set_timer" -> setTimer(arg)
            "open_settings" -> openSettings(arg)
            "open_camera" -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "go_home" -> start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            "battery" -> battery()
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

    // ---------- permessi ----------

    private fun ensure(vararg perms: String): Boolean {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        (context as? Activity)?.let {
            ActivityCompat.requestPermissions(it, missing.toTypedArray(), 3)
        }
        return false
    }

    // ---------- audio ----------

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

    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playMusic(query: String): String? {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        i.putExtra(SearchManager.QUERY, query)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            null
        } catch (e: Exception) {
            "Nessuna app musicale risponde a questa richiesta."
        }
    }

    // ---------- schermo e torcia ----------

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

    // ---------- contatti, chiamate e messaggi ----------

    private fun resolve(who: String): Pair<String, String>? {
        if (Regex("^[+0-9 ]{6,}$").matches(who)) {
            return Pair(who, who.replace(" ", ""))
        }
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + who + "%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return Pair(it.getString(0), it.getString(1))
            }
        }
        return null
    }

    private fun call(who: String): String? {
        if (!ensure(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) return permMsg
        val t = resolve(who) ?: return "Non trovo $who in rubrica."
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(t.second)))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return "Chiamo " + t.first
    }

    private fun prepareSms(arg: String): String? {
        if (!ensure(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)) return permMsg
        val parts = arg.split("|", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return "Non ho capito il testo del messaggio."
        val who = parts[0].trim()
        val text = parts[1].trim()
        val t = resolve(who) ?: return "Non trovo $who in rubrica."
        pendingSms = Triple(t.second, t.first, text)
        return "Ho scritto a " + t.first + ": " + text + ". Lo invio?"
    }

    @Suppress("DEPRECATION")
    private fun confirmSms(): String? {
        val p = pendingSms ?: return null
        pendingSms = null
        val sms = SmsManager.getDefault()
        sms.sendMultipartTextMessage(p.first, null, sms.divideMessage(p.third), null, null)
        return "Messaggio inviato a " + p.second
    }

    private fun whatsapp(arg: String): String? {
        if (!ensure(Manifest.permission.READ_CONTACTS)) return permMsg
        val parts = arg.split("|", limit = 2)
        val who = parts[0].trim()
        val text = if (parts.size > 1) parts[1].trim() else ""
        val t = resolve(who) ?: return "Non trovo $who in rubrica."
        var num = t.second.filter { it.isDigit() }
        if (num.startsWith("00")) num = num.removePrefix("00")
        else if (!t.second.startsWith("+") && num.length == 10 && num.startsWith("3")) num = "39$num"
        val r = view("https://wa.me/" + num + "?text=" + Uri.encode(text))
        return r ?: "Ho aperto la chat con " + t.first + ": tocca invia."
    }

    // ---------- internet, mappe, utilità ----------

    private fun start(i: Intent): String? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            null
        } catch (e: Exception) {
            "Non ho un'app per farlo."
        }
    }

    private fun view(uri: String): String? = start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))

    private fun setAlarm(arg: String): String? {
        val m = Regex("(\\d{1,2})[:.](\\d{2})").find(arg) ?: return "Non ho capito l'orario."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, m.groupValues[1].toInt())
            .putExtra(AlarmClock.EXTRA_MINUTES, m.groupValues[2].toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, "KITT")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(i)
    }

    private fun setTimer(arg: String): String? {
        val sec = arg.toIntOrNull() ?: return "Non ho capito la durata."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "KITT")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(i)
    }

    private fun openSettings(arg: String): String? {
        val action = when (arg.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return start(Intent(action))
    }

    private fun battery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "La batteria è al $pct per cento."
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