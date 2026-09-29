package com.lecteur.player.analysis

import com.lecteur.player.data.Track
import com.lecteur.player.history.PlayEntity
import com.lecteur.player.history.TrackStats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.random.Random

/** Ce que le mix et les listes du jour savent d'un morceau. */
data class TrackProfile(
    val track: Track,
    val features: AudioFeatures?,
    val genre: String?,
) {
    val mood: Mood? get() = features?.let(::moodOf)
    val camelot: Camelot? get() = features?.let { camelot(it.key, it.minor) }
}

// --- Mode Mix : ordre et transitions ------------------------------------------------------------

/** Écart de tempo « musical » : on ramène un tempo double ou moitié à la même échelle. */
fun tempoGap(a: Float, b: Float): Float {
    var ratio = a / b
    while (ratio > 1.5f) ratio /= 2f
    while (ratio < 0.75f) ratio *= 2f
    return abs(ratio - 1f)
}

/** Coût d'un enchaînement : tonalités incompatibles, tempo éloigné, chute d'énergie. */
fun transitionCost(from: TrackProfile, to: TrackProfile): Float {
    val a = from.features
    val b = to.features
    if (a == null || b == null) return 3f
    val harmonic = minOf(camelotDistance(camelot(a.key, a.minor), camelot(b.key, b.minor)), 4)
    val tempo = tempoGap(a.bpm, b.bpm) * 20f
    val energyDrop = maxOf(0f, a.energy - b.energy) * 2f
    return harmonic * 1.5f + tempo + energyDrop
}

/**
 * Ordre de passage d'un mix : on part du titre le plus calme, puis on enchaîne à chaque fois
 * sur le titre restant le plus compatible (tonalité voisine, tempo proche, énergie qui monte).
 */
fun orderForMix(profiles: List<TrackProfile>): List<TrackProfile> {
    if (profiles.size <= 2) return profiles
    val remaining = profiles.toMutableList()
    val start = remaining.minBy { it.features?.energy ?: 1f }
    remaining.remove(start)
    val result = mutableListOf(start)
    while (remaining.isNotEmpty()) {
        val next = remaining.minBy { transitionCost(result.last(), it) }
        remaining.remove(next)
        result += next
    }
    return result
}

/**
 * Comment enchaîner deux titres en mode Mix : durée du fondu (16 temps), point d'entrée du titre suivant
 * (son premier temps), vitesse de départ pour caler son tempo sur le précédent (±8 % au plus, sinon 1).
 */
data class TransitionPlan(val fadeMs: Long, val incomingStartMs: Long, val incomingSpeed: Float)

fun planTransition(outgoing: AudioFeatures?, incoming: AudioFeatures?, defaultFadeMs: Long = 8_000): TransitionPlan {
    if (outgoing == null || incoming == null) return TransitionPlan(defaultFadeMs, 0, 1f)
    var ratio = outgoing.bpm / incoming.bpm
    while (ratio > 1.5f) ratio /= 2f
    while (ratio < 0.75f) ratio *= 2f
    val speed = if (abs(ratio - 1f) <= 0.08f) ratio else 1f
    val fade = (16 * 60_000f / outgoing.bpm).toLong().coerceIn(6_000L, 16_000L)
    val entry = incoming.beatOffsetMs.takeIf { it < 3_000 } ?: 0L
    return TransitionPlan(fade, entry, speed)
}

/** Temps restant avant le prochain temps fort du titre en cours (pour démarrer le fondu dessus). */
fun msUntilNextBeat(positionMs: Long, features: AudioFeatures): Long {
    val beat = 60_000f / features.bpm
    val sincePrevious = ((positionMs - features.beatOffsetMs) % beat + beat) % beat
    return ((beat - sincePrevious) % beat).toLong()
}

// --- Listes du jour et de la semaine ----------------------------------------------------------------

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun similarity(a: TrackProfile, b: TrackProfile): Float {
    var score = 0f
    if (a.track.artist == b.track.artist) score += 2f
    if (a.genre != null && a.genre == b.genre) score += 1.5f
    if (a.mood != null && a.mood == b.mood) score += 1f
    val fa = a.features
    val fb = b.features
    if (fa != null && fb != null && tempoGap(fa.bpm, fb.bpm) < 0.06f) score += 0.5f
    return score
}

/**
 * « Mix du jour » : moitié de titres que vous écoutez beaucoup en ce moment, moitié de titres proches
 * que vous écoutez moins, enchaînés comme un mix. Même liste toute la journée, nouvelle le lendemain.
 */
fun dailyMix(profiles: List<TrackProfile>, recentPlays: List<PlayEntity>, date: LocalDate, size: Int = 40): List<TrackProfile> {
    if (profiles.isEmpty()) return emptyList()
    val random = Random(date.toEpochDay())
    val playCounts = recentPlays.groupingBy { it.trackId }.eachCount()
    val favorites = profiles.filter { (playCounts[it.track.id] ?: 0) > 0 }
        .sortedByDescending { playCounts[it.track.id] ?: 0 }
        .take(60)
    val pickedFavorites = favorites.shuffled(random).take(size / 2)
    val seeds = pickedFavorites.ifEmpty { profiles.shuffled(random).take(5) }
    val others = profiles.filter { it !in pickedFavorites }
        // Un peu de hasard (tiré une seule fois par titre) pour que la liste change d'un jour à l'autre.
        .map { candidate ->
            candidate to seeds.maxOf { similarity(it, candidate) } - 0.2f * (playCounts[candidate.track.id] ?: 0) + random.nextFloat() * 0.8f
        }
        .sortedByDescending { it.second }
        .take(size - pickedFavorites.size)
        .map { it.first }
    return orderForMix((pickedFavorites + others).distinctBy { it.track.id })
}

/**
 * « Découvertes de la semaine » : des titres jamais ou peu écoutés, proches de ce que vous avez
 * le plus écouté ces 30 derniers jours. Nouvelle liste chaque lundi.
 */
fun weeklyDiscoveries(
    profiles: List<TrackProfile>,
    stats: Map<Long, TrackStats>,
    recentPlays: List<PlayEntity>,
    date: LocalDate,
    size: Int = 30,
): List<TrackProfile> {
    if (profiles.isEmpty()) return emptyList()
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val random = Random(monday.toEpochDay() * 31)
    val byId = profiles.associateBy { it.track.id }
    val listened = recentPlays.mapNotNull { byId[it.trackId] }
    val artistWeight = listened.groupingBy { it.track.artist }.eachCount()
    val genreWeight = listened.mapNotNull { it.genre }.groupingBy { it }.eachCount()
    val moodWeight = listened.mapNotNull { it.mood }.groupingBy { it }.eachCount()
    return profiles
        .filter { (stats[it.track.id]?.plays ?: 0) <= 1 }
        .map { p ->
            val affinity = (artistWeight[p.track.artist] ?: 0) * 1.0f +
                (p.genre?.let { genreWeight[it] } ?: 0) * 0.6f +
                (p.mood?.let { moodWeight[it] } ?: 0) * 0.3f
            p to affinity + random.nextFloat() * 2f
        }
        .sortedByDescending { it.second }
        .take(size)
        .map { it.first }
}

/** Humeurs qui vont avec le moment de la journée. */
fun moodsForHour(hour: Int): Set<Mood> = when (hour) {
    in 5..10 -> setOf(Mood.Relaxed, Mood.Happy)
    in 11..17 -> setOf(Mood.Happy, Mood.Energetic)
    in 18..22 -> setOf(Mood.Energetic, Mood.Intense, Mood.Happy)
    else -> setOf(Mood.Relaxed, Mood.Melancholic)
}

fun momentLabel(hour: Int): String = when (hour) {
    in 5..10 -> "Pour ce matin"
    in 11..17 -> "Pour cet après-midi"
    in 18..22 -> "Pour ce soir"
    else -> "Pour cette nuit"
}

/** « Pour ce soir » (etc.) : des titres dont l'humeur colle au moment, renouvelés chaque jour. */
fun momentMix(profiles: List<TrackProfile>, hour: Int, date: LocalDate, size: Int = 30): List<TrackProfile> {
    val wanted = moodsForHour(hour)
    val random = Random(date.toEpochDay() * 7 + hour / 6)
    return orderForMix(profiles.filter { it.mood in wanted }.shuffled(random).take(size))
}

/** Titres écoutés depuis [days] jours. */
fun recentPlays(plays: List<PlayEntity>, nowMs: Long, days: Int): List<PlayEntity> =
    plays.filter { nowMs - it.playedAt <= days * DAY_MS }
