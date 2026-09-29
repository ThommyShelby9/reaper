package com.lecteur.player.jam

import com.lecteur.player.data.Track
import com.lecteur.player.history.PlayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JamExtrasTest {

    private fun track(id: Long, title: String, artist: String) = Track(
        id = id, title = title, artist = artist, album = "", albumId = 0, durationMs = 200_000,
        trackNumber = 0, discNumber = 0, year = null, mimeType = null, folder = "", dateAddedSec = 0,
    )

    private val ref = JamTrackRef("a|b", "b", "a", "", 200_000)
    private fun item(id: String, by: String, votes: Int, at: Long) = JamQueueItem(id, ref, by, by, at, List(votes) { "v$it" }, true)

    @Test
    fun `l'empreinte est stable, courte et différente d'un titre à l'autre`() {
        val h = libraryHash("lune basse|minuit sur le quai")
        assertEquals(12, h.length)
        assertEquals(h, libraryHash("lune basse|minuit sur le quai"))
        assertTrue(h != libraryHash("kaelo|neons"))
    }

    @Test
    fun `disponibilité comptée sur les participants présents qui ont publié leur bibliothèque`() {
        val key = "kaelo|neons"
        val libraries = mapOf(
            "a" to setOf(libraryHash(key)),
            "b" to setOf(libraryHash("autre|titre")),
            "c" to setOf(libraryHash(key)),
        )
        assertEquals(Availability(2, 3), availability(key, libraries, listOf("a", "b", "c", "absent")))
        assertTrue(availability(key, libraries, listOf("a", "c")).everyone)
        assertFalse(Availability(0, 0).everyone)
    }

    @Test
    fun `passer un titre demande la majorité stricte`() {
        assertEquals(1, skipThreshold(1))
        assertEquals(2, skipThreshold(2))
        assertEquals(2, skipThreshold(3))
        assertEquals(3, skipThreshold(4))
        assertTrue(shouldSkip(2, 3))
        assertFalse(shouldSkip(1, 3))
        assertFalse(shouldSkip(0, 0))
    }

    @Test
    fun `la file équitable alterne en commençant par ceux qui n'ont rien fait passer`() {
        val items = listOf(
            item("a1", "ines", 5, 1), item("a2", "ines", 4, 2), item("a3", "ines", 0, 3),
            item("m1", "malik", 0, 4),
            item("c1", "chloe", 1, 5),
        )
        // Inès vient de faire passer un titre, Malik juste avant ; Chloé jamais.
        val order = fairQueue(items, recentContributors = listOf("malik", "ines"))
        assertEquals(listOf("c1", "m1", "a1", "a2", "a3"), order.map { it.id })
    }

    @Test
    fun `coPlays compte les titres écoutés autour du titre donné`() {
        val min = 60_000L
        val plays = listOf(
            PlayEntity(trackId = 1, playedAt = 0, listenedMs = 1),
            PlayEntity(trackId = 2, playedAt = 10 * min, listenedMs = 1),
            PlayEntity(trackId = 3, playedAt = 50 * min, listenedMs = 1),
            PlayEntity(trackId = 1, playedAt = 100 * min, listenedMs = 1),
            PlayEntity(trackId = 2, playedAt = 110 * min, listenedMs = 1),
        )
        assertEquals(mapOf(2L to 2), coPlays(plays, 1))
        assertEquals(emptyMap<Long, Int>(), coPlays(plays, 99))
    }

    @Test
    fun `le DJ maison préfère ce que tout le monde a et évite ce qui est déjà passé`() {
        val current = track(1, "Minuit", "Lune Basse")
        val library = listOf(current, track(2, "Sans toi", "Lune Basse"), track(3, "Néons", "Kaelo"), track(4, "Radio", "Les Satellites"))
        val played = setOf(trackKey("Sans toi", "Lune Basse"))
        val pick = pickDjTrack(library, current, everyoneHas = { it.id == 3L }, coPlayCounts = mapOf(4L to 1), playedKeys = played, random = Random(1))
        assertEquals(3L, pick?.id)
        assertNull(pickDjTrack(listOf(current), current, { true }, emptyMap(), emptySet()))
    }

    @Test
    fun `le calibrage prend la médiane des écarts aux bips`() {
        val period = 600_000_000L
        val clicks = List(8) { it * period }
        val taps = listOf(180L, 210L, 190L, 205L, 195L).mapIndexed { i, ms -> clicks[i + 1] + ms * 1_000_000 }
        assertEquals(195L, tapLatencyMs(taps, clicks, period))
        assertNull(tapLatencyMs(taps.take(3), clicks, period))
    }

    @Test
    fun `la latence décale la position publiée et la cible du participant`() {
        assertEquals(9_800L, audiblePosition(10_000, 200))
        assertEquals(0L, audiblePosition(100, 200))
        assertEquals(10_150L, followerTarget(10_000, 150))
    }

    @Test
    fun `le récap classe les contributeurs et trouve le titre le plus voté`() {
        val played = listOf(
            PlayedEntry(ref.copy(title = "Deux"), "Malik", 1, 2_000),
            PlayedEntry(ref.copy(title = "Un"), "Inès", 3, 1_000),
            PlayedEntry(ref.copy(title = "Trois"), "Inès", 0, 3_000),
            PlayedEntry(ref.copy(title = "DJ"), null, 0, 4_000),
        )
        val recap = buildJamRecap(played, listOf("Inès", "Malik", "Inès"), startMs = 0, endMs = 3_600_000)
        assertEquals(listOf("Un", "Deux", "Trois", "DJ"), recap.played.map { it.track.title })
        assertEquals("Un", recap.mostVoted?.track?.title)
        assertEquals(listOf("Inès" to 2, "Malik" to 1), recap.contributors)
        assertEquals(listOf("Inès", "Malik"), recap.participants)
        assertEquals(3_600_000L, recap.durationMs)
    }

    @Test
    fun `les liens d'invitation donnent le code`() {
        assertEquals("K7Q2X9", parseJamLink(jamLink("K7Q2X9")))
        assertEquals("K7Q2X9", parseJamLink("reaper://jam/k7q2x9"))
        assertNull(parseJamLink("https://exemple.com/jam/K7Q2X9"))
        assertNull(parseJamLink("https://$JamLinkHost/jam/ABC"))
        assertNull(parseJamLink("pas un lien"))
    }

    @Test
    fun `le QR code est carré avec ses trois repères d'angle`() {
        val m = qrMatrix(jamLink("K7Q2X9"))
        assertEquals(m.size, m[0].size)
        assertTrue(m.size >= 21)
        // Les repères sont des carrés 7 × 7 aux trois coins : leur coin extérieur est toujours foncé.
        assertTrue(m[0][0] && m[0][m.size - 1] && m[m.size - 1][0])
        assertFalse(m[1][1])
    }
}
