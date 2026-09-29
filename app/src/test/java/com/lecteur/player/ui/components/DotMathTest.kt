package com.lecteur.player.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DotMathTest {

    @Test
    fun `formatDuration affiche minutes et secondes`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("2:08", formatDuration(128_000))
        assertEquals("5:37", formatDuration(337_900))
    }

    @Test
    fun `formatDuration passe aux heures au-delà de 60 minutes`() {
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun `formatDuration traite une durée négative comme zéro`() {
        assertEquals("0:00", formatDuration(-5_000))
    }

    @Test
    fun `resample garde le pic de chaque tranche en réduction`() {
        val input = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f)
        assertArrayEquals(floatArrayOf(0.9f, 0.3f), resample(input, 2), 0.0001f)
    }

    @Test
    fun `resample interpole en agrandissement`() {
        val input = floatArrayOf(0f, 1f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), resample(input, 3), 0.0001f)
    }

    @Test
    fun `resample d'un tableau vide donne des zéros`() {
        assertArrayEquals(FloatArray(4), resample(FloatArray(0), 4), 0f)
    }

    @Test
    fun `litDots allume toujours au moins un point et jamais plus que rows`() {
        assertEquals(1, litDots(0f, 7))
        assertEquals(7, litDots(1f, 7))
        assertEquals(7, litDots(3f, 7))
        assertEquals(4, litDots(0.5f, 7))
    }

    @Test
    fun `dotGrid échantillonne au centre des cases`() {
        val grid = dotGrid(2) { u, _ -> u }
        assertEquals(0.25f, grid[0, 0], 0.0001f)
        assertEquals(0.75f, grid[1, 1], 0.0001f)
    }

    @Test
    fun `normalizeContrast étire les valeurs sur 0 à 1`() {
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), normalizeContrast(floatArrayOf(0.2f, 0.3f, 0.4f)), 0.0001f)
    }

    @Test
    fun `normalizeContrast laisse une image uniforme telle quelle`() {
        assertArrayEquals(floatArrayOf(0.3f, 0.31f), normalizeContrast(floatArrayOf(0.3f, 0.31f)), 0.0001f)
    }

    @Test
    fun `luminanceOf donne 0 pour le noir et 1 pour le blanc`() {
        assertEquals(0f, luminanceOf(0xFF000000.toInt()), 0.0001f)
        assertEquals(1f, luminanceOf(0xFFFFFFFF.toInt()), 0.0001f)
    }
}
