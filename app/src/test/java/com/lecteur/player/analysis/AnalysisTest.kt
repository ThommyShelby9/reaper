package com.lecteur.player.analysis

import com.lecteur.player.data.Track
import com.lecteur.player.history.PlayEntity
import com.lecteur.player.history.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class AnalysisTest {

    private val rate = 11_025

    /** Métronome : un petit bruit bref à chaque temps. */
    private fun clickTrack(bpm: Float, seconds: Int): FloatArray {
        val random = Random(3)
        val out = FloatArray(rate * seconds)
        val period = (rate * 60f / bpm).toInt()
        var start = 0
        while (start < out.size) {
            for (i in 0 until 300) if (start + i < out.size) out[start + i] = (random.nextFloat() * 2 - 1) * exp(-i / 60f)
            start += period
        }
        return out
    }

    private fun tone(hz: Float, seconds: Float, amplitude: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun midiHz(midi: Int) = (440.0 * Math.pow(2.0, (midi - 69) / 12.0)).toFloat()

    @Test
    fun `le tempo d'un métronome à 120 et à 90 BPM est retrouvé`() {
        for (bpm in listOf(120f, 90f)) {
            val estimate = estimateTempo(onsetEnvelope(clickTrack(bpm, 20)), rate / 256f)
            assertTrue("attendu $bpm, obtenu ${estimate?.bpm}", estimate != null && abs(estimate.bpm - bpm) < 2f)
        }
    }

    @Test
    fun `un silence ne donne pas de tempo`() {
        assertNull(estimateTempo(onsetEnvelope(FloatArray(rate * 10)), rate / 256f))
    }

    @Test
    fun `une gamme de do majeur appuyée sur do et sol donne do majeur`() {
        // Do (appuyé), ré, mi, fa, sol (appuyé), la, si.
        val weights = mapOf(60 to 2f, 62 to 0.8f, 64 to 1.2f, 65 to 0.8f, 67 to 1.6f, 69 to 0.8f, 71 to 0.7f)
        val samples = weights.flatMap { (midi, w) -> tone(midiHz(midi), 1.5f * w).toList() }.toFloatArray()
        assertEquals(0 to false, estimateKey(chroma(samples, rate)))
    }

    @Test
    fun `roue de Camelot`() {
        assertEquals("8B", camelot(0, minor = false).toString())
        assertEquals("8A", camelot(9, minor = true).toString())
        assertEquals("9B", camelot(7, minor = false).toString())
        assertEquals("9A", camelot(4, minor = true).toString())
        assertEquals("2B", camelot(6, minor = false).toString())
        assertEquals("7A", camelot(2, minor = true).toString())
        assertEquals("Fa♯ majeur", keyName(6, false))
    }

    @Test
    fun `distance harmonique`() {
        assertEquals(0, camelotDistance(Camelot(8, false), Camelot(8, false)))
        assertEquals(1, camelotDistance(Camelot(8, false), Camelot(8, true)))
        assertEquals(1, camelotDistance(Camelot(12, true), Camelot(1, true)))
        assertEquals(3, camelotDistance(Camelot(8, false), Camelot(10, true)))
    }

    @Test
    fun `énergie et brillance`() {
        assertEquals(0.875f, energyOf(tone(440f, 1f, amplitude = 0.5f)), 0.02f)
        assertEquals(0f, energyOf(FloatArray(100)), 0f)
        assertEquals(0f, brightnessOf(tone(440f, 1f), rate), 0.01f)
        assertEquals(1f, brightnessOf(tone(4_000f, 1f), rate), 0.01f)
    }

    private fun features(bpm: Float = 120f, key: Int = 0, minor: Boolean = false, energy: Float = 0.5f, brightness: Float = 0.5f, offset: Long = 0) =
        AudioFeatures(bpm, offset, 0.5f, key, minor, energy, brightness)

    @Test
    fun `humeurs`() {
        assertEquals(Mood.Energetic, moodOf(features(bpm = 150f, energy = 0.9f, brightness = 0.7f)))
        assertEquals(Mood.Intense, moodOf(features(bpm = 150f, energy = 0.9f, minor = true, brightness = 0.1f)))
        assertEquals(Mood.Happy, moodOf(features(bpm = 115f, energy = 0.5f, brightness = 0.6f)))
        assertEquals(Mood.Relaxed, moodOf(features(bpm = 75f, energy = 0.2f, brightness = 0.5f)))
        assertEquals(Mood.Melancholic, moodOf(features(bpm = 80f, energy = 0.3f, minor = true, brightness = 0.2f)))
    }

    @Test
    fun `familles de genres`() {
        assertEquals("Hip-hop", genreFamily("Hip-Hop/Rap"))
        assertEquals("Électro", genreFamily("Deep House"))
        assertEquals("Latino", genreFamily("Reggaeton"))
        assertEquals("Reggae", genreFamily("Roots Reggae"))
        assertEquals("Chanson", genreFamily("Variété française"))
        assertEquals("Musiques afro", genreFamily("Afrobeats"))
        assertEquals("Rock", genreFamily("Post-Rock"))
        assertEquals("Soul & R'n'B", genreFamily("R&B"))
        assertEquals("Zydeco", genreFamily("zydeco"))
        assertNull(genreFamily("  "))
        assertNull(genreFamily("(13)"))
    }

    private fun track(id: Long, artist: String = "A") = Track(
        id = id, title = "T$id", artist = artist, album = "", albumId = 0, durationMs = 200_000,
        trackNumber = 0, discNumber = 0, year = null, mimeType = null, folder = "", dateAddedSec = 0,
    )

    private fun profile(id: Long, f: AudioFeatures?, genre: String? = null, artist: String = "A") = TrackProfile(track(id, artist), f, genre)

    @Test
    fun `le mix part du plus calme et suit les tonalités compatibles`() {
        val calm = profile(1, features(energy = 0.2f, key = 0))           // 8B
        val neighbour = profile(2, features(energy = 0.5f, key = 7))      // 9B, voisin
        val far = profile(3, features(energy = 0.6f, key = 6))            // 2B, éloigné
        val order = orderForMix(listOf(far, neighbour, calm)).map { it.track.id }
        assertEquals(listOf(1L, 2L, 3L), order)
    }

    @Test
    fun `mix aléatoire, tous les titres une fois et des ordres différents`() {
        val profiles = (1L..12L).map { profile(it, features(bpm = 90f + it * 3, key = (it % 12).toInt(), energy = it / 12f)) }
        val orders = (1..20).map { seed -> orderForMix(profiles, kotlin.random.Random(seed)).map { it.track.id } }
        orders.forEach { assertEquals((1L..12L).toSet(), it.toSet()); assertEquals(12, it.size) }
        assertTrue(orders.map { it.first() }.toSet().size > 3)
        assertTrue(orders.toSet().size > 10)
        // Même graine, même mix.
        assertEquals(orders[0], orderForMix(profiles, kotlin.random.Random(1)).map { it.track.id })
    }

    @Test
    fun `plan de transition`() {
        val plan = planTransition(features(bpm = 124f), features(bpm = 120f, offset = 250))
        assertEquals(124f / 120f, plan.incomingSpeed, 0.001f)
        assertEquals(7_741L, plan.fadeMs)
        assertEquals(250L, plan.incomingStartMs)
        // Tempo trop éloigné : on ne force pas la vitesse. Tempo double : on le ramène.
        assertEquals(1f, planTransition(features(bpm = 140f), features(bpm = 100f)).incomingSpeed, 0f)
        assertEquals(1f, planTransition(features(bpm = 120f), features(bpm = 60f)).incomingSpeed, 0.001f)
        assertEquals(TransitionPlan(8_000, 0, 1f), planTransition(null, features()))
    }

    @Test
    fun `temps avant le prochain temps fort`() {
        val f = features(bpm = 120f, offset = 100) // un temps toutes les 500 ms, le premier à 100 ms
        assertEquals(400L, msUntilNextBeat(200, f))
        assertEquals(0L, msUntilNextBeat(600, f))
        assertEquals(50L, msUntilNextBeat(50, f))
    }

    private val library = (1L..120L).map { id ->
        profile(id, features(energy = (id % 10) / 10f, bpm = 80f + id % 60, minor = id % 3 == 0L), genre = if (id % 2 == 0L) "Pop" else "Rock", artist = "Artiste ${id % 12}")
    }

    @Test
    fun `le mix du jour est stable dans la journée et change le lendemain`() {
        val plays = (1L..30L).map { PlayEntity(trackId = it, playedAt = 0, listenedMs = 1) }
        val day = LocalDate.of(2026, 9, 28)
        val a = dailyMix(library, plays, day)
        assertEquals(40, a.size)
        assertEquals(a.size, a.map { it.track.id }.distinct().size)
        assertEquals(a.map { it.track.id }, dailyMix(library, plays, day).map { it.track.id })
        assertNotEquals(a.map { it.track.id }, dailyMix(library, plays, day.plusDays(1)).map { it.track.id })
    }

    @Test
    fun `les découvertes de la semaine ne proposent que des titres peu écoutés`() {
        val stats = (1L..60L).associateWith { TrackStats(it, plays = 5, lastPlayedMs = 0) }
        val plays = (1L..60L).map { PlayEntity(trackId = it, playedAt = 0, listenedMs = 1) }
        val monday = LocalDate.of(2026, 9, 28)
        val list = weeklyDiscoveries(library, stats, plays, monday)
        assertEquals(30, list.size)
        assertTrue(list.all { it.track.id > 60 })
        // Même liste toute la semaine.
        assertEquals(list.map { it.track.id }, weeklyDiscoveries(library, stats, plays, monday.plusDays(4)).map { it.track.id })
    }

    @Test
    fun `regroupement par genre et par humeur`() {
        val tracks = (1L..5L).map { track(it) }
        val genres = genreGroups(tracks) { if (it.id <= 3) "Pop" else if (it.id == 4L) "Jazz" else null }
        assertEquals(listOf("Pop", "Jazz", "Genre inconnu"), genres.map { it.title })
        assertEquals("3 titres", genres.first().subtitle)
        val moods = moodGroups(tracks) { if (it.id == 1L) Mood.Melancholic else if (it.id == 2L) Mood.Energetic else null }
        assertEquals(listOf("Énergique", "Mélancolique", "Pas encore analysés"), moods.map { it.title })
    }

    @Test
    fun `la liste du moment respecte les humeurs de l'heure`() {
        val list = momentMix(library, hour = 21, date = LocalDate.of(2026, 9, 28))
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.mood in moodsForHour(21) })
        assertEquals("Pour ce soir", momentLabel(21))
    }
}
