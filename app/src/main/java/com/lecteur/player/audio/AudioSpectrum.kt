package com.lecteur.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reçoit le son décodé juste avant la sortie audio (via un TeeAudioProcessor du service)
 * et en tire [BANDS] niveaux de fréquence pour le visualiseur. Aucune autorisation micro nécessaire.
 * Le calcul ne tourne que si l'écran l'a demandé ([active]).
 */
@OptIn(UnstableApi::class)
object AudioSpectrum : TeeAudioProcessor.AudioBufferSink {

    const val BANDS = 16
    private const val SIZE = 1024

    /** Mis à vrai par l'interface quand le visualiseur est à l'écran. */
    @Volatile var active = false

    /** Derniers niveaux (0..1), remplacés en bloc : lecture sans verrou depuis l'interface. */
    @Volatile var levels: FloatArray = FloatArray(BANDS)
        private set

    private val window = Spectrum.hannWindow(SIZE)
    private val samples = FloatArray(SIZE)
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private var edges = Spectrum.logBandEdges(BANDS, 60f, 16_000f)
    private var fill = 0
    private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        edges = Spectrum.logBandEdges(BANDS, 60f, minOf(16_000f, sampleRateHz / 2f - 1f))
        fill = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!active) return
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = data.asShortBuffer()
                while (shorts.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += shorts.get() / 32768f }
                    push(sum / channels)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = data.asFloatBuffer()
                while (floats.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    push(sum / channels)
                }
            }
        }
    }

    private fun push(sample: Float) {
        samples[fill++] = sample
        if (fill == SIZE) {
            analyze()
            fill = 0
        }
    }

    private fun analyze() {
        for (i in 0 until SIZE) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        Spectrum.fft(re, im)
        levels = Spectrum.smooth(levels, Spectrum.bandLevels(re, im, SIZE, sampleRate, edges))
    }
}
