package com.lecteur.player.history

import com.lecteur.player.data.Track
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class RecapPeriod(val label: String) {
    Month("Ce mois-ci"),
    Year("Cette année"),
    AllTime("Depuis toujours"),
}

/** Début de la période, en millisecondes depuis 1970 (minuit, heure locale). */
fun periodStart(period: RecapPeriod, nowMs: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val first = when (period) {
        RecapPeriod.Month -> today.withDayOfMonth(1)
        RecapPeriod.Year -> today.withDayOfYear(1)
        RecapPeriod.AllTime -> return 0L
    }
    return first.atStartOfDay(zone).toInstant().toEpochMilli()
}

data class RankedArtist(val name: String, val plays: Int)
data class RankedTrack(val track: Track, val plays: Int)

data class Recap(
    val listenedMs: Long,
    val plays: Int,
    val distinctTracks: Int,
    val topArtists: List<RankedArtist>,
    val topTracks: List<RankedTrack>,
    /** Temps écouté par heure de la journée (0 à 23), en millisecondes. */
    val byHour: LongArray,
    /** Heure où l'on écoute le plus, ou null sans écoute. */
    val peakHour: Int?,
    val activeDays: Int,
    /** Plus longue suite de jours consécutifs avec au moins une écoute. */
    val longestStreakDays: Int,
) {
    val isEmpty: Boolean get() = plays == 0
}

/**
 * Calcule le bilan d'une liste d'écoutes. Les écoutes de morceaux qui ne sont plus
 * dans la bibliothèque comptent dans le temps total mais pas dans les classements.
 */
fun computeRecap(plays: List<PlayEntity>, tracksById: Map<Long, Track>, zone: ZoneId, top: Int = 5): Recap {
    val byHour = LongArray(24)
    val days = sortedSetOf<LocalDate>()
    for (play in plays) {
        val time = Instant.ofEpochMilli(play.playedAt).atZone(zone)
        byHour[time.hour] += play.listenedMs
        days += time.toLocalDate()
    }

    val known = plays.mapNotNull { play -> tracksById[play.trackId] }
    val topArtists = known.groupingBy { it.artist }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(top)
        .map { RankedArtist(it.key, it.value) }
    val topTracks = known.groupingBy { it.id }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { tracksById.getValue(it.key).title })
        .take(top)
        .map { RankedTrack(tracksById.getValue(it.key), it.value) }

    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in days) {
        run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
        longest = maxOf(longest, run)
        previous = day
    }

    return Recap(
        listenedMs = plays.sumOf { it.listenedMs },
        plays = plays.size,
        distinctTracks = plays.map { it.trackId }.distinct().size,
        topArtists = topArtists,
        topTracks = topTracks,
        byHour = byHour,
        peakHour = byHour.indices.maxByOrNull { byHour[it] }?.takeIf { byHour[it] > 0 },
        activeDays = days.size,
        longestStreakDays = longest,
    )
}

/** « 34 min », « 2 h 05 », « 120 h 00 ». */
fun formatListening(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0L) "$minutes min" else "$hours h ${"%02d".format(minutes)}"
}
