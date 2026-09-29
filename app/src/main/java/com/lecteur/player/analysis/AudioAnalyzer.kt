package com.lecteur.player.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lecteur.player.data.Track
import com.lecteur.player.data.contentUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Décode un extrait de 45 secondes au cœur du morceau (à partir de 25 % de sa durée), le réduit en mono
 * autour de 11 kHz, puis en tire tempo, tonalité, énergie et brillance ([analyze]).
 * Compter environ une à deux secondes par morceau sur un téléphone récent.
 */
class AudioAnalyzer(context: Context) {

    private val appContext = context.applicationContext

    suspend fun analyze(track: Track): AudioFeatures? = withContext(Dispatchers.Default) {
        val duration = track.durationMs
        val segmentMs = 45_000L
        val startMs = if (duration > segmentMs * 2) duration / 4 else 0L
        val started = android.os.SystemClock.elapsedRealtime()
        val (samples, rate) = decodeMono(track, startMs, segmentMs) ?: return@withContext null
        val decoded = android.os.SystemClock.elapsedRealtime()
        analyze(samples, rate, startMs).also {
            android.util.Log.d("ReaperAnalyse", "  décodage ${decoded - started} ms, calcul ${android.os.SystemClock.elapsedRealtime() - decoded} ms")
        }
    }

    private suspend fun decodeMono(track: Track, startMs: Long, lengthMs: Long): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, track.contentUri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = (startMs + lengthMs) * 1000

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            // Réduction de la fréquence d'échantillonnage par moyenne de blocs (44,1 kHz → 11 025 Hz).
            var factor = (sampleRate / 11_025f).roundToInt().coerceAtLeast(1)
            val out = FloatArray(((lengthMs / 1000) + 2).toInt() * 12_100)
            var count = 0
            var accumulator = 0f
            var accumulated = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // Par lots : on remplit toutes les entrées libres, on vide toutes les sorties prêtes,
            // et on n'attend le décodeur que si rien n'a avancé.
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
                    if (size < 0 || extractor.sampleTime > endUs) {
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
                            if (info.size > 0 && info.presentationTimeUs >= startMs * 1000) {
                                val buffer = decoder.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
                                if (floatPcm) {
                                    val floats = ordered.asFloatBuffer()
                                    while (floats.remaining() >= channels) {
                                        var sum = 0f
                                        repeat(channels) { sum += floats.get() }
                                        accumulator += sum / channels
                                        if (++accumulated == factor) { if (count < out.size) out[count++] = accumulator / factor; accumulator = 0f; accumulated = 0 }
                                    }
                                } else {
                                    val shorts = ordered.asShortBuffer()
                                    while (shorts.remaining() >= channels) {
                                        var sum = 0f
                                        repeat(channels) { sum += shorts.get() / 32768f }
                                        accumulator += sum / channels
                                        if (++accumulated == factor) { if (count < out.size) out[count++] = accumulator / factor; accumulator = 0f; accumulated = 0 }
                                    }
                                }
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
                            factor = (sampleRate / 11_025f).roundToInt().coerceAtLeast(1)
                        }
                    }
                }
            }
            if (count < 11_025 * 8) return null
            return out.copyOf(count) to sampleRate / factor
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}

/**
 * Attente d'une trame décodée, seulement quand ni l'entrée ni la sortie n'ont avancé : une attente
 * à chaque trame, répétée sur des milliers de trames, rendait le décodage très lent.
 */
private const val OUTPUT_WAIT_US = 2_000L
