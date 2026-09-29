package com.lecteur.player.ui.components

import kotlin.math.roundToInt

/**
 * Grille carrée de luminosités (0..1) utilisée pour dessiner une image en matrice de points.
 * Les valeurs sont rangées ligne par ligne.
 */
class DotGrid(val size: Int, val values: FloatArray) {
    init {
        require(size > 0) { "size doit être positif" }
        require(values.size == size * size) { "values doit contenir size × size éléments" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * size + x]
}

/** Construit une grille en échantillonnant [luminance] au centre de chaque case (u, v dans 0..1). */
fun dotGrid(size: Int, luminance: (u: Float, v: Float) -> Float): DotGrid {
    val values = FloatArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            values[y * size + x] = luminance((x + 0.5f) / size, (y + 0.5f) / size).coerceIn(0f, 1f)
        }
    }
    return DotGrid(size, values)
}

/** Luminance relative (Rec. 709) d'une couleur ARGB, entre 0 et 1. */
fun luminanceOf(argb: Int): Float {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * Étire les valeurs sur 0..1 pour qu'une pochette très sombre ou très claire garde du relief
 * en matrice de points. Une image quasi uniforme est laissée telle quelle.
 */
fun normalizeContrast(values: FloatArray): FloatArray {
    if (values.isEmpty()) return values.copyOf()
    val min = values.min()
    val max = values.max()
    if (max - min < 0.05f) return values.copyOf()
    return FloatArray(values.size) { (values[it] - min) / (max - min) }
}

/**
 * Ramène [amplitudes] à [count] colonnes.
 * En réduction, chaque colonne garde le maximum de sa tranche pour ne pas perdre les pics.
 * En agrandissement, les valeurs sont interpolées linéairement.
 */
fun resample(amplitudes: FloatArray, count: Int): FloatArray {
    require(count > 0) { "count doit être positif" }
    if (amplitudes.isEmpty()) return FloatArray(count)
    if (amplitudes.size == count) return amplitudes.copyOf()
    val out = FloatArray(count)
    if (amplitudes.size > count) {
        for (i in 0 until count) {
            val start = i * amplitudes.size / count
            val end = maxOf(start + 1, (i + 1) * amplitudes.size / count)
            var peak = 0f
            for (j in start until end) peak = maxOf(peak, amplitudes[j])
            out[i] = peak
        }
    } else {
        val last = amplitudes.size - 1
        for (i in 0 until count) {
            val pos = if (count == 1) 0f else i * last.toFloat() / (count - 1)
            val lo = pos.toInt().coerceAtMost(last)
            val hi = (lo + 1).coerceAtMost(last)
            val t = pos - lo
            out[i] = amplitudes[lo] * (1 - t) + amplitudes[hi] * t
        }
    }
    return out
}

/** Nombre de points allumés dans une colonne de [rows] points ; toujours au moins un. */
fun litDots(amplitude: Float, rows: Int): Int =
    (amplitude.coerceIn(0f, 1f) * rows).roundToInt().coerceIn(1, rows)

/** Durée au format m:ss, ou h:mm:ss au-delà d'une heure. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
