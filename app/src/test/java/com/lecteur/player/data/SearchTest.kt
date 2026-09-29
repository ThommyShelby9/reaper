package com.lecteur.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private val track = Track(
        id = 1, title = "Pluie d'été", artist = "Odette Moreau", album = "Été indien", albumId = 1,
        durationMs = 1_000, trackNumber = 1, discNumber = 1, year = null, mimeType = null,
        folder = "", dateAddedSec = 0,
    )

    @Test
    fun `normalizeForSearch retire accents et majuscules`() {
        assertEquals("ecoute a l'ete", normalizeForSearch("Écoute À l'Été"))
    }

    @Test
    fun `une recherche vide accepte tout`() {
        assertTrue(matchesQuery(track, "   "))
    }

    @Test
    fun `la recherche ignore les accents`() {
        assertTrue(matchesQuery(track, "ete"))
        assertTrue(matchesQuery(track, "PLUIE"))
    }

    @Test
    fun `tous les mots doivent être présents, dans n'importe quel champ`() {
        assertTrue(matchesQuery(track, "moreau pluie"))
        assertFalse(matchesQuery(track, "moreau neons"))
    }
}
