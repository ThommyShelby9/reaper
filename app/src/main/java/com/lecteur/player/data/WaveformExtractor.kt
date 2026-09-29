package com.lecteur.player.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Calcule la vraie forme d'onde d'un morceau en le décodant entièrement (MediaCodec),
 * puis la garde en cache sur le disque. Compter une à quelques secondes par morceau.
 */
class WaveformExtractor(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "waveforms")
    private val memory = LruCache<Long, FloatArray>(32)

    /** Renvoie null si le fichier ne peut pas être décodé. */
    suspend fun waveform(track: Track, buckets: Int = BUCKETS): FloatArray? = withContext(Dispatchers.IO) {
        memory.get(track.id)?.let { return@withContext it }
        val file = File(cacheDir, "${track.id}_${track.dateAddedSec}_$buckets.bin")
        val cached = runCatching { readCache(file, buckets) }.getOrNull()
        val result = cached ?: runCatching { decode(track, buckets) }.getOrNull()?.also {
            runCatching { writeCache(file, it) }
        }
        result?.also { memory.put(track.id, it) }
    }

    private suspend fun decode(track: Track, buckets: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, track.contentUri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                track.durationMs * 1000
            }
            if (durationUs <= 0) return null

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val loudness = LoudnessAccumulator(buckets, durationUs)
            val info = MediaCodec.BufferInfo()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var inputDone = false
            var outputDone = false

            // Par lots : toutes les entrées libres, puis toutes les sorties prêtes ; on n'attend que si rien n'avance.
            var progressed = true
            while (!outputDone) {
                coroutineContext.ensureActive()
                val waitUs = if (progressed) 0L else OUTPUT_WAIT_US
                progressed = false
                while (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    progressed = true
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                while (!outputDone) {
                    val outIndex = decoder.dequeueOutputBuffer(info, if (progressed) 0L else waitUs)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    when {
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val out = decoder.getOutputBuffer(outIndex)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                accumulate(out, info.presentationTimeUs, sampleRate, channels, floatPcm, loudness)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = decoder.outputFormat
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
            }
            return loudness.result()
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private fun accumulate(
        buffer: ByteBuffer,
        startUs: Long,
        sampleRate: Int,
        channels: Int,
        floatPcm: Boolean,
        loudness: LoudnessAccumulator,
    ) {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (floatPcm) {
            val samples = ordered.asFloatBuffer()
            val frames = samples.remaining() / channels
            var frame = 0
            while (frame < frames) {
                var peak = 0f
                for (ch in 0 until channels) peak = maxOf(peak, abs(samples.get(frame * channels + ch)))
                loudness.add(peak.coerceAtMost(1f), startUs + frame * 1_000_000L / sampleRate)
                frame += FRAME_STRIDE
            }
        } else {
            val samples = ordered.asShortBuffer()
            val frames = samples.remaining() / channels
            var frame = 0
            while (frame < frames) {
                var peak = 0
                for (ch in 0 until channels) peak = maxOf(peak, abs(samples.get(frame * channels + ch).toInt()))
                loudness.add(peak / 32768f, startUs + frame * 1_000_000L / sampleRate)
                frame += FRAME_STRIDE
            }
        }
    }

    private fun readCache(file: File, buckets: Int): FloatArray? {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val count = input.readInt()
            if (count != buckets) return null
            return FloatArray(count) { input.readFloat() }
        }
    }

    private fun writeCache(file: File, values: FloatArray) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(values.size)
            values.forEach { out.writeFloat(it) }
        }
    }

    private companion object {
        const val BUCKETS = 160
        /** Attente courte : un délai de 10 ms, répété sur des milliers de trames, rendait le décodage très lent. */
        const val OUTPUT_WAIT_US = 2_000L
        /** Un échantillon sur quatre suffit pour le niveau et divise le calcul par quatre. */
        const val FRAME_STRIDE = 4
    }
}
