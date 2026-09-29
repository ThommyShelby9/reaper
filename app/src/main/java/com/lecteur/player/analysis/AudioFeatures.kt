package com.lecteur.player.analysis

import com.lecteur.player.audio.Spectrum
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Analyse d'un extrait audio mono, entièrement sur le téléphone : tempo, tonalité, énergie, brillance.
 * Méthodes classiques et légères (pas d'apprentissage automatique) : elles se trompent parfois,
 * notamment sur le tempo (moitié ou double) et sur les morceaux sans pulsation nette.
 */

/** Caractéristiques d'un morceau. [key] : 0 = do … 11 = si ; [minor] : mode mineur. */
data class AudioFeatures(
    val bpm: Float,
    /** Décalage du premier temps par rapport au début du morceau, en ms (0 ≤ x < durée d'un temps). */
    val beatOffsetMs: Long,
    /** Netteté de la pulsation, 0..1. */
    val beatStrength: Float,
    val key: Int,
    val minor: Boolean,
    /** Énergie (niveau moyen), 0..1. */
    val energy: Float,
    /** Brillance (centre de gravité du spectre), 0..1. */
    val brightness: Float,
)

// --- Tempo ----------------------------------------------------------------------------------------

/**
 * Courbe d'attaques (« flux spectral ») : à chaque trame, la hausse d'énergie par fréquence.
 * Les pics correspondent aux coups de batterie et aux attaques de notes.
 */
fun onsetEnvelope(samples: FloatArray, frameSize: Int = 1024, hop: Int = 256): FloatArray {
    if (samples.size < frameSize) return FloatArray(0)
    val window = Spectrum.hannWindow(frameSize)
    val frames = (samples.size - frameSize) / hop + 1
    val re = FloatArray(frameSize)
    val im = FloatArray(frameSize)
    var previous = FloatArray(frameSize / 2)
    val flux = FloatArray(frames)
    for (f in 0 until frames) {
        val start = f * hop
        for (i in 0 until frameSize) {
            re[i] = samples[start + i] * window[i]
            im[i] = 0f
        }
        Spectrum.fft(re, im)
        val magnitudes = FloatArray(frameSize / 2) { ln(1f + 10f * sqrt(re[it] * re[it] + im[it] * im[it])) }
        var sum = 0f
        for (b in magnitudes.indices) sum += maxOf(0f, magnitudes[b] - previous[b])
        flux[f] = sum
        previous = magnitudes
    }
    // On retire la tendance locale (moyenne sur ~0,5 s) pour ne garder que les attaques.
    val half = 10
    return FloatArray(frames) { i ->
        var mean = 0f
        var n = 0
        for (j in maxOf(0, i - half)..minOf(frames - 1, i + half)) {
            mean += flux[j]
            n++
        }
        maxOf(0f, flux[i] - mean / n)
    }
}

/** Tempo et phase estimés à partir de la courbe d'attaques. */
data class TempoEstimate(val bpm: Float, val phaseFrames: Float, val strength: Float)

/**
 * Autocorrélation de la courbe d'attaques entre 60 et 200 BPM, pondérée autour de 120 BPM
 * (préférence perceptive) pour trancher entre un tempo et son double ou sa moitié.
 */
fun estimateTempo(envelope: FloatArray, frameRate: Float): TempoEstimate? {
    if (envelope.size < frameRate * 6) return null
    val minLag = (60f * frameRate / 200f).toInt().coerceAtLeast(1)
    val maxLag = (60f * frameRate / 60f).toInt().coerceAtMost(envelope.size / 2)
    var energy = 0f
    for (v in envelope) energy += v * v
    if (energy <= 0f) return null
    val scores = FloatArray(maxLag + 2)
    val raw = FloatArray(maxLag + 2)
    for (lag in minLag..maxLag + 1) {
        if (lag >= envelope.size) break
        var sum = 0f
        for (i in 0 until envelope.size - lag) sum += envelope[i] * envelope[i + lag]
        raw[lag] = sum / energy
        val bpm = 60f * frameRate / lag
        val octaves = log2(bpm / 120f)
        scores[lag] = raw[lag] * exp(-0.5f * (octaves / 0.9f) * (octaves / 0.9f))
    }
    val best = (minLag..maxLag).maxByOrNull { scores[it] } ?: return null
    if (raw[best] <= 0f) return null
    // Interpolation parabolique pour une valeur entre deux décalages entiers.
    val a = scores.getOrElse(best - 1) { 0f }
    val b = scores[best]
    val c = scores.getOrElse(best + 1) { 0f }
    val denominator = a - 2 * b + c
    val shift = if (denominator != 0f) (0.5f * (a - c) / denominator).coerceIn(-0.5f, 0.5f) else 0f
    val period = best + shift
    // Phase : le décalage qui aligne le mieux une grille de temps sur les attaques.
    val phase = (0 until best).maxByOrNull { p ->
        var sum = 0f
        var i = p.toFloat()
        while (i < envelope.size) {
            sum += envelope[i.toInt()]
            i += period
        }
        sum
    } ?: 0
    return TempoEstimate(bpm = 60f * frameRate / period, phaseFrames = phase.toFloat(), strength = raw[best].coerceIn(0f, 1f))
}

// --- Tonalité ---------------------------------------------------------------------------------------

private val MajorProfile = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
private val MinorProfile = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

/** Énergie de chacune des 12 classes de hauteur (do, do#, …, si), cumulée sur l'extrait. */
fun chroma(samples: FloatArray, sampleRate: Int, frameSize: Int = 4096, hop: Int = 2048): FloatArray {
    val result = FloatArray(12)
    if (samples.size < frameSize) return result
    val window = Spectrum.hannWindow(frameSize)
    val re = FloatArray(frameSize)
    val im = FloatArray(frameSize)
    val binHz = sampleRate.toFloat() / frameSize
    var start = 0
    while (start + frameSize <= samples.size) {
        for (i in 0 until frameSize) {
            re[i] = samples[start + i] * window[i]
            im[i] = 0f
        }
        Spectrum.fft(re, im)
        for (bin in 1 until frameSize / 2) {
            val hz = bin * binHz
            if (hz < 60f || hz > 2_000f) continue
            val midi = 12f * log2(hz / 440f) + 69f
            val pitchClass = ((midi.roundToInt() % 12) + 12) % 12
            result[pitchClass] += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
        }
        start += hop
    }
    return result
}

private fun correlation(a: FloatArray, b: FloatArray, rotation: Int): Float {
    val meanA = a.average().toFloat()
    val meanB = b.average().toFloat()
    var num = 0f
    var da = 0f
    var db = 0f
    for (i in 0 until 12) {
        val x = a[(i + rotation) % 12] - meanA
        val y = b[i] - meanB
        num += x * y
        da += x * x
        db += y * y
    }
    return if (da == 0f || db == 0f) 0f else num / sqrt(da * db)
}

/** Tonalité la plus probable (profils de Krumhansl-Kessler) : (tonique 0..11, mineur). */
fun estimateKey(chroma: FloatArray): Pair<Int, Boolean>? {
    if (chroma.all { it == 0f }) return null
    var best = 0 to false
    var bestScore = Float.NEGATIVE_INFINITY
    for (tonic in 0 until 12) {
        val major = correlation(chroma, MajorProfile, tonic)
        if (major > bestScore) { bestScore = major; best = tonic to false }
        val minor = correlation(chroma, MinorProfile, tonic)
        if (minor > bestScore) { bestScore = minor; best = tonic to true }
    }
    return best
}

// --- Roue de Camelot (notation des DJ) ----------------------------------------------------------------

data class Camelot(val number: Int, val minor: Boolean) {
    override fun toString() = "$number${if (minor) "A" else "B"}"
}

/** Do majeur = 8B, la mineur = 8A ; un pas sur la roue = une quinte. */
fun camelot(key: Int, minor: Boolean): Camelot {
    val majorTonic = if (minor) (key + 3) % 12 else key
    val fifths = (majorTonic * 7) % 12
    return Camelot(number = (7 + fifths) % 12 + 1, minor = minor)
}

/**
 * Distance harmonique : 0 même tonalité, 1 voisins (quinte ou relatif majeur/mineur), plus au-delà.
 * Deux tonalités à distance ≤ 1 se mixent sans fausse note.
 */
fun camelotDistance(a: Camelot, b: Camelot): Int {
    val diff = abs(a.number - b.number)
    val steps = minOf(diff, 12 - diff)
    return steps + if (a.minor != b.minor) 1 else 0
}

private val NoteNames = listOf("Do", "Do♯", "Ré", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")

fun keyName(key: Int, minor: Boolean): String = "${NoteNames[key]} ${if (minor) "mineur" else "majeur"}"

// --- Énergie et brillance -----------------------------------------------------------------------------

/** Niveau moyen ramené sur 0..1 : −30 dBFS → 0, −6 dBFS → 1. */
fun energyOf(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s * s
    val rms = sqrt(sum / samples.size).toFloat()
    val db = 20f * log10(rms + 1e-9f)
    return ((db + 30f) / 24f).coerceIn(0f, 1f)
}

/** Centre de gravité moyen du spectre, ramené sur 0..1 (800 Hz → 0, 3 000 Hz → 1). */
fun brightnessOf(samples: FloatArray, sampleRate: Int, frameSize: Int = 2048): Float {
    if (samples.size < frameSize) return 0f
    val window = Spectrum.hannWindow(frameSize)
    val re = FloatArray(frameSize)
    val im = FloatArray(frameSize)
    val binHz = sampleRate.toFloat() / frameSize
    var total = 0.0
    var frames = 0
    var start = 0
    while (start + frameSize <= samples.size) {
        for (i in 0 until frameSize) {
            re[i] = samples[start + i] * window[i]
            im[i] = 0f
        }
        Spectrum.fft(re, im)
        var weighted = 0.0
        var magnitude = 0.0
        for (bin in 1 until frameSize / 2) {
            val m = sqrt(re[bin] * re[bin] + im[bin] * im[bin]).toDouble()
            weighted += m * bin * binHz
            magnitude += m
        }
        if (magnitude > 0) {
            total += weighted / magnitude
            frames++
        }
        start += frameSize
    }
    if (frames == 0) return 0f
    return (((total / frames) - 800.0) / 2_200.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Analyse complète d'un extrait mono de [samples] à [sampleRate] Hz, qui commence à [segmentStartMs]
 * dans le morceau. Renvoie null si l'extrait est trop court ou silencieux.
 */
fun analyze(samples: FloatArray, sampleRate: Int, segmentStartMs: Long): AudioFeatures? {
    val hop = 256
    val frameRate = sampleRate.toFloat() / hop
    val tempo = estimateTempo(onsetEnvelope(samples, 1024, hop), frameRate) ?: return null
    val (key, minor) = estimateKey(chroma(samples, sampleRate)) ?: return null
    val beatMs = 60_000f / tempo.bpm
    val phaseMs = tempo.phaseFrames * 1000f / frameRate
    val offset = ((segmentStartMs + phaseMs) % beatMs).toLong()
    return AudioFeatures(
        bpm = tempo.bpm,
        beatOffsetMs = offset,
        beatStrength = tempo.strength,
        key = key,
        minor = minor,
        energy = energyOf(samples),
        brightness = brightnessOf(samples, sampleRate),
    )
}
