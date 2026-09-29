package com.lecteur.player.history

import com.lecteur.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class RecapTest {

    private val zone = ZoneOffset.UTC

    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 9, day, hour, 0).toInstant(zone).toEpochMilli()

    private fun track(id: Long, title: String, artist: String) = Track(
        id = id, title = title, artist = artist, album = "", albumId = 0, durationMs = 0,
        trackNumber = 0, discNumber = 0, year = null, mimeType = null, folder = "", dateAddedSec = 0,
    )

    private val tracks = listOf(
        track(1, "Minuit sur le quai", "Lune Basse"),
        track(2, "Néons", "Kaelo"),
        track(3, "Radio Nacre", "Les Satellites"),
    ).associateBy { it.id }

    private fun play(trackId: Long, day: Int, hour: Int, minutes: Long) =
        PlayEntity(trackId = trackId, playedAt = at(day, hour), listenedMs = minutes * 60_000)

    @Test
    fun `periodStart donne le début du mois, de l'année, ou zéro`() {
        val now = LocalDateTime.of(2026, 9, 28, 15, 30).toInstant(zone).toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0).toInstant(zone).toEpochMilli(), periodStart(RecapPeriod.Month, now, zone))
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0).toInstant(zone).toEpochMilli(), periodStart(RecapPeriod.Year, now, zone))
        assertEquals(0L, periodStart(RecapPeriod.AllTime, now, zone))
    }

    @Test
    fun `le bilan totalise, classe et répartit par heure`() {
        val plays = listOf(
            play(1, 1, 22, 5), play(1, 2, 22, 5), play(1, 3, 23, 5),
            play(2, 3, 8, 4), play(2, 5, 22, 4),
            play(3, 5, 9, 3),
            play(99, 6, 10, 2), // morceau supprimé de la bibliothèque
        )
        val recap = computeRecap(plays, tracks, zone)
        assertEquals(28 * 60_000L, recap.listenedMs)
        assertEquals(7, recap.plays)
        assertEquals(4, recap.distinctTracks)
        assertEquals(listOf("Lune Basse" to 3, "Kaelo" to 2, "Les Satellites" to 1), recap.topArtists.map { it.name to it.plays })
        assertEquals(listOf(1L, 2L, 3L), recap.topTracks.map { it.track.id })
        assertEquals(22, recap.peakHour)
        assertEquals(14 * 60_000L, recap.byHour[22])
        assertEquals(5, recap.activeDays)
        // Jours 1, 2, 3 d'affilée, puis 5 et 6.
        assertEquals(3, recap.longestStreakDays)
    }

    @Test
    fun `à égalité, les artistes sont classés par ordre alphabétique`() {
        val recap = computeRecap(listOf(play(3, 1, 10, 1), play(2, 1, 11, 1)), tracks, zone)
        assertEquals(listOf("Kaelo", "Les Satellites"), recap.topArtists.map { it.name })
    }

    @Test
    fun `un bilan sans écoute est vide`() {
        val recap = computeRecap(emptyList(), tracks, zone)
        assertTrue(recap.isEmpty)
        assertNull(recap.peakHour)
        assertEquals(0, recap.longestStreakDays)
    }

    @Test
    fun `formatListening affiche minutes puis heures`() {
        assertEquals("34 min", formatListening(34 * 60_000L))
        assertEquals("2 h 05", formatListening(125 * 60_000L))
        assertEquals("0 min", formatListening(-1))
    }
}
