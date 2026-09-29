package com.lecteur.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Calculs du visualiseur : FFT, découpage en bandes de fréquence, lissage. Aucune dépendance Android. */
object Spectrum {

    /** FFT en place (radix 2) ; la taille doit être une puissance de deux. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "taille ${re.size} : puissance de deux attendue" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    fun hannWindow(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }

    /** Limites de [bands] bandes réparties en échelle logarithmique, comme l'oreille. */
    fun logBandEdges(bands: Int, minHz: Float, maxHz: Float): FloatArray {
        val ratio = maxHz / minHz
        return FloatArray(bands + 1) { minHz * ratio.pow(it.toFloat() / bands) }
    }

    /**
     * Niveau (0..1) de chaque bande après une FFT de [size] échantillons fenêtrés par Hann :
     * le pic de la bande en dB, ramené de [floorDb]..0 dB sur 0..1.
     */
    fun bandLevels(re: FloatArray, im: FloatArray, size: Int, sampleRate: Int, edges: FloatArray, floorDb: Float = -60f): FloatArray {
        val binHz = sampleRate.toFloat() / size
        val maxBin = size / 2 - 1
        return FloatArray(edges.size - 1) { band ->
            val lo = (edges[band] / binHz).toInt().coerceIn(1, maxBin)
            val hi = (edges[band + 1] / binHz).toInt().coerceIn(lo, maxBin)
            var peak = 0f
            for (bin in lo..hi) peak = maxOf(peak, sqrt(re[bin] * re[bin] + im[bin] * im[bin]))
            // Amplitude d'une sinusoïde pleine échelle : n/2, divisée par 2 par la fenêtre de Hann.
            val amplitude = peak / (size / 4f)
            val db = 20 * log10(amplitude + 1e-9f)
            ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
        }
    }

    /** Monte vite, redescend doucement : les barres restent lisibles au lieu de clignoter. */
    fun smooth(previous: FloatArray, target: FloatArray, attack: Float = 0.6f, release: Float = 0.15f): FloatArray =
        FloatArray(target.size) { i ->
            val p = previous.getOrElse(i) { 0f }
            val t = target[i]
            p + (t - p) * if (t > p) attack else release
        }
}
