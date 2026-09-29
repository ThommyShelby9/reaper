package com.lecteur.player.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

class SpectrumTest {

    private fun sine(n: Int, sampleRate: Int, hz: Float, amplitude: Float = 1f) =
        FloatArray(n) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }

    @Test
    fun `la FFT d'une sinusoïde a son pic sur la bonne case`() {
        val n = 256
        val re = FloatArray(n) { sin(2 * PI * 10 * it / n).toFloat() }
        val im = FloatArray(n)
        Spectrum.fft(re, im)
        val magnitudes = (0 until n / 2).map { hypot(re[it], im[it]) }
        assertEquals(10, magnitudes.indices.maxBy { magnitudes[it] })
        assertEquals(n / 2f, magnitudes[10], 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `la FFT refuse une taille qui n'est pas une puissance de deux`() {
        Spectrum.fft(FloatArray(100), FloatArray(100))
    }

    @Test
    fun `les bandes logarithmiques couvrent la plage demandée`() {
        val edges = Spectrum.logBandEdges(4, 60f, 16_000f)
        assertEquals(5, edges.size)
        assertEquals(60f, edges.first(), 0.01f)
        assertEquals(16_000f, edges.last(), 0.5f)
        assertTrue(edges.toList().zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `une sinusoïde à 1 kHz allume sa bande et laisse les autres basses`() {
        val n = 1024
        val rate = 44_100
        val window = Spectrum.hannWindow(n)
        val re = sine(n, rate, 1_000f).mapIndexed { i, v -> v * window[i] }.toFloatArray()
        val im = FloatArray(n)
        Spectrum.fft(re, im)
        val edges = Spectrum.logBandEdges(16, 60f, 16_000f)
        val levels = Spectrum.bandLevels(re, im, n, rate, edges)
        val band = (0 until 16).first { edges[it] <= 1_000f && 1_000f < edges[it + 1] }
        assertTrue("niveau de la bande : ${levels[band]}", levels[band] > 0.9f)
        assertTrue(levels.filterIndexed { i, _ -> kotlin.math.abs(i - band) > 2 }.all { it < 0.4f })
    }

    @Test
    fun `le silence donne des niveaux nuls`() {
        val n = 1024
        val levels = Spectrum.bandLevels(FloatArray(n), FloatArray(n), n, 44_100, Spectrum.logBandEdges(8, 60f, 16_000f))
        assertArrayEquals(FloatArray(8), levels, 0f)
    }

    @Test
    fun `le lissage monte vite et descend lentement`() {
        val up = Spectrum.smooth(floatArrayOf(0f), floatArrayOf(1f))
        val down = Spectrum.smooth(floatArrayOf(1f), floatArrayOf(0f))
        assertEquals(0.6f, up[0], 0.0001f)
        assertEquals(0.85f, down[0], 0.0001f)
    }
}
