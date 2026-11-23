package com.chavix.seskesici

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

const val RATE = 48000

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun secs(samples: Int): String = String.format(Locale.US, "%.1f", samples / RATE.toDouble())

fun ShortArray.cut(from: Int, to: Int): ShortArray {
    val a = from.coerceIn(0, size)
    val b = to.coerceIn(a, size)
    val out = ShortArray(size - (b - a))
    System.arraycopy(this, 0, out, 0, a)
    System.arraycopy(this, b, out, a, size - b)
    return out
}

fun ShortArray.insertAt(pos: Int, part: ShortArray): ShortArray {
    val p = pos.coerceIn(0, size)
    val out = ShortArray(size + part.size)
    System.arraycopy(this, 0, out, 0, p)
    System.arraycopy(part, 0, out, p, part.size)
    System.arraycopy(this, p, out, p + part.size, size - p)
    return out
}

/** Process-wide current audio (48 kHz mono PCM16) plus a small undo stack. */
object Store {
    @Volatile var pcm: ShortArray = ShortArray(0)
    private val history = ArrayList<ShortArray>()

    fun pushUndo(prev: ShortArray) {
        history.add(prev)
        if (history.size > 10) history.removeAt(0)
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        pcm = history.removeAt(history.size - 1)
        return true
    }

    fun clearUndo() = history.clear()
}

class Recorder {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val lock = Any()
    private var buf = ShortArray(RATE * 30)
    private var count = 0

    val size: Int get() = synchronized(lock) { count }
    val isRunning: Boolean get() = running

    fun reset() = synchronized(lock) { count = 0 }
    fun snapshot(): ShortArray = synchronized(lock) { buf.copyOf(count) }

    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, RATE / 5 * 2)
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            return false
        }
        rec = r
        running = true
        thread = Thread {
            val tmp = ShortArray(RATE / 10)
            while (running) {
                val n = r.read(tmp, 0, tmp.size)
                if (n < 0) break
                if (n > 0) synchronized(lock) {
                    if (count + n > buf.size) buf = buf.copyOf(max(buf.size * 2, count + n))
                    System.arraycopy(tmp, 0, buf, count, n)
                    count += n
                }
            }
        }.also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        rec?.let {
            try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        rec = null
    }
}

class Player {
    @Volatile private var token: Any? = null
    val isPlaying: Boolean get() = token != null

    fun play(pcm: ShortArray, from: Int, onPos: (Int) -> Unit, onDone: () -> Unit) {
        val me = Any()
        token = me
        if (from >= pcm.size) {
            token = null
            onDone()
            return
        }
        Thread {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuf, RATE / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                null
            }
            if (track == null) {
                if (token === me) token = null
                onDone()
                return@Thread
            }
            track.play()
            var pos = from
            while (token === me && pos < pcm.size) {
                val w = track.write(pcm, pos, min(4800, pcm.size - pos))
                if (w <= 0) break
                pos += w
                if (token === me) onPos(from + track.playbackHeadPosition)
            }
            val total = pcm.size - from
            var guard = 0
            while (token === me && track.playbackHeadPosition < total && guard++ < 150) {
                onPos(from + track.playbackHeadPosition)
                Thread.sleep(30)
            }
            try { track.stop() } catch (e: Exception) {}
            track.release()
            if (token === me) {
                token = null
                onPos(pcm.size)
                onDone()
            }
        }.start()
    }

    fun stop() {
        token = null
    }
}
