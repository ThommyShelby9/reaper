package com.lecteur.player.history

import com.lecteur.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun `le tracker compte le temps de lecture et ignore les pauses`() {
        val tracker = ListenTracker()
        assertNull(tracker.onItemChanged("a", isPlaying = true, nowMs = 0, wallClockMs = 1_000))
        tracker.onPlayingChanged(false, 10_000)
        tracker.onPlayingChanged(true, 50_000)
        val listen = tracker.onItemChanged("b", isPlaying = true, nowMs = 60_000, wallClockMs = 2_000)
        assertEquals(Listen("a", 1_000, 20_000), listen)
    }

    @Test
    fun `stop termine l'écoute en cours`() {
        val tracker = ListenTracker()
        tracker.onItemChanged("a", isPlaying = true, nowMs = 0, wallClockMs = 0)
        assertEquals(35_000L, tracker.stop(35_000)?.listenedMs)
        assertNull(tracker.stop(40_000))
    }

    @Test
    fun `un morceau choisi en pause ne compte qu'à partir de la reprise`() {
        val tracker = ListenTracker()
        tracker.onItemChanged("a", isPlaying = false, nowMs = 0, wallClockMs = 0)
        tracker.onPlayingChanged(true, 5_000)
        assertEquals(5_000L, tracker.stop(10_000)?.listenedMs)
    }

    @Test
    fun `countsAsPlay applique la moitié du morceau, 4 minutes au plus, 30 secondes au moins`() {
        assertFalse(countsAsPlay(20_000, 30_000))
        assertTrue(countsAsPlay(100_000, 200_000))
        assertFalse(countsAsPlay(99_000, 200_000))
        assertTrue(countsAsPlay(240_000, 1_200_000))
        assertTrue(countsAsPlay(30_000, 0))
    }

    private fun track(id: Long, addedDaysAgo: Long) = Track(
        id = id, title = "T$id", artist = "A", album = "B", albumId = 1, durationMs = 1_000,
        trackNumber = 0, discNumber = 0, year = null, mimeType = null, folder = "",
        dateAddedSec = (NOW - addedDaysAgo * DAY) / 1000,
    )

    private val tracks = listOf(track(1, 2), track(2, 40), track(3, 400), track(4, 10))
    private val stats = mapOf(
        2L to TrackStats(2, plays = 5, lastPlayedMs = NOW - 100 * DAY),
        3L to TrackStats(3, plays = 1, lastPlayedMs = NOW - 1 * DAY),
        4L to TrackStats(4, plays = 5, lastPlayedMs = NOW - 2 * DAY),
    )

    private fun ids(p: SmartPlaylist) = smartPlaylistTracks(p, tracks, stats, NOW).map { it.id }

    @Test
    fun `listes intelligentes`() {
        assertEquals(listOf(1L, 4L, 2L, 3L), ids(SmartPlaylist.RecentlyAdded))
        assertEquals(listOf(1L), ids(SmartPlaylist.NeverPlayed))
        // À nombre d'écoutes égal, le plus récemment joué passe devant.
        assertEquals(listOf(4L, 2L, 3L), ids(SmartPlaylist.MostPlayed))
        assertEquals(listOf(3L, 4L, 2L), ids(SmartPlaylist.RecentlyPlayed))
        assertEquals(listOf(2L), ids(SmartPlaylist.Forgotten))
    }

    @Test
    fun `Favoris passe en tête, dernier titre aimé en premier`() {
        val playlists = listOf(
            SavedPlaylist(7, "Jam du samedi", NOW, listOf(2, 3)),
            SavedPlaylist(9, FAVORITES_NAME, NOW - DAY, listOf(4, 1, 99)),
        )
        val groups = savedPlaylistGroups(playlists, tracks)
        assertEquals(listOf("Favoris", "Jam du samedi"), groups.map { it.title })
        // Le titre 99 n'est plus sur le téléphone : ignoré.
        assertEquals(listOf(1L, 4L), groups.first().tracks.map { it.id })
        assertEquals(setOf(4L, 1L, 99L), favoriteIds(playlists))
        assertEquals(emptySet<Long>(), favoriteIds(playlists.take(1)))
    }

    @Test
    fun `les groupes gardent l'ordre des listes et comptent leurs titres`() {
        val groups = smartPlaylistGroups(tracks, stats, NOW)
        assertEquals(SmartPlaylist.entries.map { it.title }, groups.map { it.title })
        assertEquals("Aucune écoute complète, 1 titre", groups[1].subtitle)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_800_000_000_000L
    }
}
