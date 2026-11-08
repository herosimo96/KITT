
package com.kitt.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

class VoiceListener(
    private val onUtterance: (ByteArray, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onWake: () -> Unit,
    private val onSleep: () -> Unit
) {
    @Volatile var paused = false
    @Volatile var wake: Recognizer? = null
    @Volatile private var awakeUntil = 0L
    @Volatile private var running = false
    private var thread: Thread? = null
    private val wakeWord = Regex("\\b(?:kitt|kit)\\b", RegexOption.IGNORE_CASE)

    fun isAlive(): Boolean = running && thread?.isAlive == true

    fun keepAwake(ms: Long) {
        awakeUntil = System.currentTimeMillis() + ms
    }

    fun start() {
        if (running) return
        thread?.join(300)
        running = true
        thread = Thread { loop() }
        thread?.start()
    }

    fun stop() {
        running = false
    }

    private fun write(out: ByteArrayOutputStream, data: ShortArray, n: Int) {
        for (i in 0 until n) {
            val v = data[i].toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = 16000
        val frame = 320
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frame * 2 * 10)
            )
        } catch (e: Exception) {
            running = false
            onError("Errore microfono: " + e.message)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            onError("Microfono non disponibile")
            return
        }

        val buf = ShortArray(frame)
        val pre = ArrayDeque<ShortArray>()
        val speech = ByteArrayOutputStream()
        var noise = 300.0
        var inSpeech = false
        var voiced = 0
        var silent = 0
        var frames = 0
        var lastAwake = true
        var wasPaused = false
        var fromWake = false
        var errors = 0
        var zeroFrames = 0

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, frame)
                if (n < 0) {
                    errors++
                    if (errors > 50) break
                    Thread.sleep(40)
                    continue
                }
                if (n == 0) continue
                errors = 0

                if (paused) {
                    inSpeech = false
                    speech.reset()
                    pre.clear()
                    voiced = 0
                    silent = 0
                    zeroFrames = 0
                    fromWake = false
                    wasPaused = true
                    continue
                }

                val w = wake
                if (w == null) {
                    // Senza modello di attivazione non inviare audio ambientale a Groq.
                    Thread.sleep(100)
                    continue
                }
                if (wasPaused) {
                    wasPaused = false
                    w.reset()
                }
                val now = System.currentTimeMillis()

                var sum = 0.0
                var peak = 0
                for (i in 0 until n) {
                    val v = buf[i].toDouble()
                    sum += v * v
                    val a = Math.abs(buf[i].toInt())
                    if (a > peak) peak = a
                }
                if (peak == 0) zeroFrames++ else zeroFrames = 0
                if (zeroFrames > 750) {
                    onError("Microfono silenzioso: lo riavvio")
                    break
                }

                val rms = Math.sqrt(sum / n)
                val threshold = noise * 2.5 + 250.0
                val isVoice = rms > threshold

                val awake = inSpeech || now < awakeUntil
                if (awake != lastAwake) {
                    lastAwake = awake
                    if (!awake) {
                        w.reset()
                        voiced = 0
                        onSleep()
                    }
                }

                // In attesa ascolta solo la parola KITT, offline.
                if (!awake) {
                    if (!isVoice) {
                        noise = (noise * 0.97 + rms * 0.03).coerceAtLeast(100.0)
                    }
                    pre.addLast(buf.copyOf(n))
                    if (pre.size > 80) pre.removeFirst()
                    val text = if (w.acceptWaveForm(buf, n)) w.result else w.partialResult
                    if (wakeWord.containsMatchIn(text)) {
                        w.reset()
                        awakeUntil = now + 8000
                        lastAwake = true
                        inSpeech = true
                        fromWake = true
                        silent = 0
                        voiced = 0
                        frames = pre.size
                        speech.reset()
                        for (f in pre) write(speech, f, f.size)
                        pre.clear()
                        onWake()
                    }
                    continue
                }

                // Attivo: registra la frase e la invia.
                if (!inSpeech) {
                    if (!isVoice) {
                        noise = (noise * 0.97 + rms * 0.03).coerceAtLeast(100.0)
                    }
                    pre.addLast(buf.copyOf(n))
                    while (pre.size > 15) pre.removeFirst()
                    if (isVoice) voiced++ else voiced = 0
                    if (voiced >= 4) {
                        inSpeech = true
                        fromWake = false
                        silent = 0
                        frames = 0
                        speech.reset()
                        for (f in pre) write(speech, f, f.size)
                        pre.clear()
                    }
                } else {
                    write(speech, buf, n)
                    frames++
                    if (isVoice) silent = 0 else silent++
                    if (silent >= 40 || frames >= 750) {
                        inSpeech = false
                        voiced = 0
                        val voicedFrames = frames - silent
                        val wasWake = fromWake
                        fromWake = false
                        if (voicedFrames >= 15) {
                            onUtterance(speech.toByteArray(), wasWake)
                        }
                        speech.reset()
                    }
                }
            }
        } catch (e: Exception) {
            onError("Ascolto interrotto: " + e.message)
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
            running = false
        }
    }
}
