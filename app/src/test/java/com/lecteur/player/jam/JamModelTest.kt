package com.lecteur.player.jam

import com.lecteur.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JamModelTest {

    private fun track(id: Long, title: String, artist: String, durationMs: Long) = Track(
        id = id, title = title, artist = artist, album = "", albumId = 0, durationMs = durationMs,
        trackNumber = 0, discNumber = 0, year = null, mimeType = null, folder = "", dateAddedSec = 0,
    )

    @Test
    fun `la clé ignore accents, casse, ponctuation, parenthèses et invités`() {
        val key = trackKey("Minuit sur le quai", "Lune Basse")
        assertEquals("lune basse|minuit sur le quai", key)
        assertEquals(key, trackKey("MINUIT SUR LE QUAI (Remastered 2024)", "Lune  Basse"))
        assertEquals(trackKey("Néons", "Kaelo"), trackKey("Neons [Radio Edit]", "Kaelo feat. Inès"))
        assertEquals(trackKey("Pluie d'été", "Odette"), trackKey("Pluie d’ete!", "Odette"))
    }

    @Test
    fun `matchTrack choisit la bonne version et refuse une durée trop différente`() {
        val library = listOf(
            track(1, "Néons", "Kaelo", 228_000),
            track(2, "Néons (Live)", "Kaelo", 312_000),
        )
        val ref = JamTrackRef(trackKey("Néons", "Kaelo"), "Néons", "Kaelo", "", 229_500)
        assertEquals(1L, matchTrack(ref, library)?.id)
        assertNull(matchTrack(ref.copy(durationMs = 250_000), library))
        assertNull(matchTrack(ref.copy(key = "autre|titre"), library))
        // Durée inconnue : on accepte le meilleur candidat.
        assertEquals(1L, matchTrack(ref.copy(durationMs = 0), listOf(library[0]))?.id)
    }

    @Test
    fun `le décalage d'horloge vient de la mesure la plus rapide`() {
        val samples = listOf(
            ClockSample(sentAtMs = 1_000, receivedAtMs = 1_400, serverMs = 6_300), // lente : peu fiable
            ClockSample(sentAtMs = 2_000, receivedAtMs = 2_100, serverMs = 7_050), // rapide
        )
        assertEquals(5_000L, estimateClockOffset(samples))
        assertNull(estimateClockOffset(emptyList()))
    }

    private val ref = JamTrackRef("a|b", "b", "a", "", 300_000)

    @Test
    fun `la position attendue avance avec le temps serveur, sauf en pause`() {
        val playing = JamPlayback(ref, playing = true, positionMs = 10_000, anchorServerMs = 100_000)
        assertEquals(15_000L, expectedPositionMs(playing, 105_000))
        assertEquals(10_000L, expectedPositionMs(playing.copy(playing = false), 105_000))
        assertEquals(300_000L, expectedPositionMs(playing, 10_000_000))
        assertEquals(20_000L, expectedPositionMs(playing.copy(speed = 2f), 105_000))
    }

    @Test
    fun `grand écart = saut, petit écart = vitesse, écart infime = rien`() {
        assertEquals(SyncDecision(50_000, 1f, false), syncDecision(localMs = 48_000, expectedMs = 50_000))
        assertEquals(SyncDecision(null, 1f, true), syncDecision(localMs = 50_020, expectedMs = 50_000))
        val ahead = syncDecision(localMs = 50_100, expectedMs = 50_000)
        assertNull(ahead.seekToMs)
        assertEquals(0.975f, ahead.speed, 0.0001f)
        assertEquals(1.03f, syncDecision(localMs = 48_800, expectedMs = 50_000).speed, 0.0001f)
    }

    @Test
    fun `l'hôte ne republie que sur un vrai changement`() {
        val last = JamPlayback(ref, playing = true, positionMs = 10_000, anchorServerMs = 100_000)
        assertTrue(shouldPublish(null, last, 100_000))
        assertFalse(shouldPublish(last, last.copy(positionMs = 15_100, anchorServerMs = 105_000), 105_000))
        assertTrue(shouldPublish(last, last.copy(positionMs = 60_000, anchorServerMs = 105_000), 105_000))
        assertTrue(shouldPublish(last, last.copy(playing = false), 100_000))
        assertTrue(shouldPublish(last, last.copy(track = ref.copy(key = "c|d")), 100_000))
    }

    @Test
    fun `les codes font 6 caractères sans 0, O, 1 ni I`() {
        val random = Random(42)
        repeat(200) {
            val code = generateJamCode(random)
            assertEquals(6, code.length)
            assertTrue(code.none { it in "0O1I" })
        }
        assertEquals("K7Q2X9", normalizeJamCode(" k7q-2x9 "))
    }

    @Test
    fun `la file est triée par votes puis par ancienneté`() {
        fun item(id: String, votes: Int, at: Long) = JamQueueItem(id, ref, "u", "U", at, List(votes) { "v$it" }, null)
        val sorted = sortedQueue(listOf(item("a", 1, 10), item("b", 3, 30), item("c", 1, 5)))
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `présence pendant 90 secondes`() {
        assertTrue(isPresent(lastSeenMs = 1_000, serverNowMs = 60_000))
        assertFalse(isPresent(lastSeenMs = 1_000, serverNowMs = 120_000))
    }
}
