package com.lecteur.player.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {

    private fun track(
        id: Long,
        title: String,
        artist: String = "Lune Basse",
        album: String = "Quai Nord",
        albumId: Long = 1,
        trackNumber: Int = 0,
        discNumber: Int = 1,
        folder: String = "Music",
    ) = Track(
        id = id, title = title, artist = artist, album = album, albumId = albumId,
        durationMs = 1_000, trackNumber = trackNumber, discNumber = discNumber, year = null,
        mimeType = "audio/flac", folder = folder, dateAddedSec = 0,
    )

    @Test
    fun `cleanTag remplace les étiquettes vides ou inconnues`() {
        assertEquals(UNKNOWN_ARTIST, cleanTag("<unknown>", UNKNOWN_ARTIST))
        assertEquals(UNKNOWN_ARTIST, cleanTag("  ", UNKNOWN_ARTIST))
        assertEquals(UNKNOWN_ARTIST, cleanTag(null, UNKNOWN_ARTIST))
        assertEquals("Kaelo", cleanTag(" Kaelo ", UNKNOWN_ARTIST))
    }

    @Test
    fun `splitTrackNumber sépare disque et plage`() {
        assertEquals(1 to 3, splitTrackNumber(1003))
        assertEquals(0 to 7, splitTrackNumber(7))
        assertEquals(0 to 0, splitTrackNumber(0))
    }

    @Test
    fun `folderFromData retire le préfixe du stockage`() {
        assertEquals("Music/Kaelo", folderFromData("/storage/emulated/0/Music/Kaelo/Neons.flac"))
        assertEquals("Music", folderFromData("/storage/1A2B-3C4D/Music/a.mp3"))
        assertEquals("", folderFromData(null))
    }

    @Test
    fun `formatLabel donne un libellé court`() {
        assertEquals("FLAC", formatLabel("audio/flac"))
        assertEquals("MP3", formatLabel("audio/mpeg"))
        assertEquals("AAC", formatLabel("audio/mp4"))
        assertEquals("DSF", formatLabel("audio/x-dsf"))
        assertEquals("Audio", formatLabel(null))
    }

    @Test
    fun `sortedByTitle trie à la française, accents compris`() {
        val sorted = sortedByTitle(listOf(track(1, "Zèbre"), track(2, "écoute"), track(3, "Avion"), track(4, "Faux")))
        assertEquals(listOf("Avion", "écoute", "Faux", "Zèbre"), sorted.map { it.title })
    }

    @Test
    fun `les albums gardent l'ordre des plages et des disques`() {
        val tracks = listOf(
            track(1, "C", trackNumber = 1, discNumber = 2),
            track(2, "A", trackNumber = 2, discNumber = 1),
            track(3, "B", trackNumber = 1, discNumber = 1),
        )
        val albums = groupTracks(tracks, LibraryTab.Albums)
        assertEquals(1, albums.size)
        assertEquals(listOf("B", "A", "C"), albums.single().tracks.map { it.title })
        assertEquals("Lune Basse", albums.single().subtitle)
    }

    @Test
    fun `un album à plusieurs artistes est marqué comme tel`() {
        val tracks = listOf(track(1, "A", artist = "Kaelo"), track(2, "B", artist = "Vesper Club"))
        assertEquals("Artistes variés", groupTracks(tracks, LibraryTab.Albums).single().subtitle)
    }

    @Test
    fun `les artistes comptent leurs albums et sont triés`() {
        val tracks = listOf(
            track(1, "A", artist = "Vesper Club", albumId = 1),
            track(2, "B", artist = "Kaelo", albumId = 2),
            track(3, "C", artist = "Kaelo", albumId = 3, album = "Autre"),
        )
        val artists = groupTracks(tracks, LibraryTab.Artistes)
        assertEquals(listOf("Kaelo", "Vesper Club"), artists.map { it.title })
        assertEquals("2 albums", artists.first().subtitle)
        assertEquals("1 album", artists.last().subtitle)
    }

    @Test
    fun `les dossiers portent le nom du dernier segment`() {
        val folders = groupTracks(listOf(track(1, "A", folder = "Music/Kaelo/Neons")), LibraryTab.Dossiers)
        assertEquals("Neons", folders.single().title)
        assertEquals("Music/Kaelo/Neons", folders.single().subtitle)
    }

    @Test
    fun `l'onglet Titres ne produit pas de groupes`() {
        assertEquals(emptyList<TrackGroup>(), groupTracks(listOf(track(1, "A")), LibraryTab.Titres))
    }

    @Test
    fun `LoudnessAccumulator normalise sur la tranche la plus forte`() {
        val acc = LoudnessAccumulator(buckets = 2, durationUs = 1_000)
        acc.add(0.5f, 100)
        acc.add(0.5f, 200)
        acc.add(1f, 600)
        assertArrayEquals(floatArrayOf(0.5f, 1f), acc.result(), 0.0001f)
    }

    @Test
    fun `LoudnessAccumulator comble les tranches vides avec la précédente`() {
        val acc = LoudnessAccumulator(buckets = 3, durationUs = 300)
        acc.add(1f, 0)
        acc.add(0.5f, 250)
        assertArrayEquals(floatArrayOf(1f, 1f, 0.5f), acc.result(), 0.0001f)
    }

    @Test
    fun `LoudnessAccumulator silencieux donne des zéros`() {
        assertArrayEquals(FloatArray(4), LoudnessAccumulator(4, 100).result(), 0f)
    }
}
