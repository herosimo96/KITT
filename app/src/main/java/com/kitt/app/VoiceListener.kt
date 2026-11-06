package com.kitt.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class VoiceListener(
    private val onUtterance: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile var paused = false
    @Volatile private var running = false
    private var thread: Thread? = null

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

        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue

                if (paused) {
                    inSpeech = false
                    speech.reset()
                    pre.clear()
                    voiced = 0
                    silent = 0
                    continue
                }

                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i].toDouble()
                    sum += v * v
                }
                val rms = Math.sqrt(sum / n)
                val threshold = noise * 2.5 + 250.0
                val isVoice = rms > threshold

                if (!inSpeech) {
                    if (!isVoice) {
                        noise = (noise * 0.97 + rms * 0.03).coerceAtLeast(100.0)
                    }
                    pre.addLast(buf.copyOf(n))
                    if (pre.size > 15) pre.removeFirst()
                    if (isVoice) voiced++ else voiced = 0
                    if (voiced >= 4) {
                        inSpeech = true
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
                        if (voicedFrames >= 15) {
                            onUtterance(speech.toByteArray())
                        }
                        speech.reset()
                    }
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
        }
    }
}