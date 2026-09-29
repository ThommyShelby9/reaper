package com.lecteur.player.data

import kotlin.math.sqrt

/**
 * Accumule des échantillons audio (valeurs absolues 0..1) dans [buckets] tranches de temps
 * et donne le niveau efficace (RMS) de chaque tranche, normalisé pour que la plus forte vaille 1.
 */
class LoudnessAccumulator(private val buckets: Int, private val durationUs: Long) {
    init {
        require(buckets > 0) { "buckets doit être positif" }
        require(durationUs > 0) { "durationUs doit être positif" }
    }

    private val sumSquares = DoubleArray(buckets)
    private val counts = IntArray(buckets)

    fun add(sample: Float, timeUs: Long) {
        val index = (timeUs.toDouble() / durationUs * buckets).toInt().coerceIn(0, buckets - 1)
        sumSquares[index] += (sample * sample).toDouble()
        counts[index]++
    }

    fun result(): FloatArray {
        val rms = FloatArray(buckets) { i ->
            if (counts[i] == 0) 0f else sqrt(sumSquares[i] / counts[i]).toFloat()
        }
        // Une tranche vide (fin de fichier, saut de décodage) reprend la valeur de sa voisine.
        for (i in 1 until buckets) if (counts[i] == 0) rms[i] = rms[i - 1]
        val max = rms.maxOrNull() ?: 0f
        if (max <= 0f) return FloatArray(buckets)
        return FloatArray(buckets) { rms[it] / max }
    }
}
