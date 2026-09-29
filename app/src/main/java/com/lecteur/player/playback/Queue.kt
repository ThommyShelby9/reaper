package com.lecteur.player.playback

import com.lecteur.player.data.Track
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Un élément de la file de lecture. [track] est null si le fichier a disparu de la bibliothèque. */
data class QueueItem(val index: Int, val mediaId: String, val track: Track?)

/**
 * Heures de départ des titres à venir, comme sur un tableau de gare :
 * le premier démarre quand le titre en cours se termine, les suivants s'enchaînent.
 */
fun departureTimes(nowMs: Long, currentRemainingMs: Long, upcomingDurationsMs: List<Long>): List<Long> {
    var start = nowMs + currentRemainingMs.coerceAtLeast(0L)
    return upcomingDurationsMs.map { duration ->
        val departure = start
        start += duration.coerceAtLeast(0L)
        departure
    }
}

/** Délais avant chaque titre à venir, en millisecondes, quand la lecture est en pause. */
fun waitingTimes(currentRemainingMs: Long, upcomingDurationsMs: List<Long>): List<Long> =
    departureTimes(0L, currentRemainingMs, upcomingDurationsMs)

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Heure au format 24 h (« 22:41 »). */
fun formatClock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    ClockFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))
