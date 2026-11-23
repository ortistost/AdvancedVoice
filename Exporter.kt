package com.chavix.seskesici

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.min

object Exporter {
    class Result(val file: File, val mime: String, val label: String)

    /** Tries Opus in OGG first (WhatsApp voice-note style), falls back to AAC in M4A. */
    fun export(pcm: ShortArray, dir: File): Result {
        val stamp = System.currentTimeMillis()
        val ogg = File(dir, "ses_$stamp.ogg")
        try {
            encode(pcm, ogg, MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, 32000)
            if (ogg.length() > 0) return Result(ogg, "audio/ogg", "Opus / OGG")
        } catch (e: Exception) {
        }
        ogg.delete()
        val m4a = File(dir, "ses_$stamp.m4a")
        encode(pcm, m4a, MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, 64000)
        return Result(m4a, "audio/mp4", "AAC / M4A")
    }

    private fun encode(pcm: ShortArray, out: File, mime: String, container: Int, bitrate: Int) {
        val fmt = MediaFormat.createAudioFormat(mime, RATE, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var started = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mx = MediaMuxer(out.absolutePath, container)
            muxer = mx
            var track = -1
            var pos = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val t0 = SystemClock.elapsedRealtime()
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() - t0 > 180_000) throw IOException("encode timeout")
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!
                        ib.clear()
                        ib.order(ByteOrder.LITTLE_ENDIAN)
                        val n = min(ib.capacity() / 2, pcm.size - pos)
                        if (n > 0) ib.asShortBuffer().put(pcm, pos, n)
                        val pts = pos * 1_000_000L / RATE
                        pos += n
                        if (pos >= pcm.size) {
                            inputDone = true
                            codec.queueInputBuffer(i, 0, n * 2, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mx.addTrack(codec.outputFormat)
                    mx.start()
                    started = true
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        mx.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (!started) throw IOException("encoder produced no output")
            started = false
            mx.stop()
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            codec.release()
            try { if (started) muxer?.stop() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }
}
