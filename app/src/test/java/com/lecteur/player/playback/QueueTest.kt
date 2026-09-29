package com.lecteur.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class QueueTest {

    @Test
    fun `les départs s'enchaînent après le titre en cours`() {
        assertEquals(listOf(1_300L, 1_500L, 2_000L), departureTimes(1_000, 300, listOf(200, 500, 100)))
    }

    @Test
    fun `un reste négatif ou une durée négative comptent pour zéro`() {
        assertEquals(listOf(1_000L, 1_000L), departureTimes(1_000, -50, listOf(-10, 100)))
    }

    @Test
    fun `waitingTimes part de zéro`() {
        assertEquals(listOf(60_000L, 240_000L), waitingTimes(60_000, listOf(180_000, 200_000)))
    }

    @Test
    fun `formatClock affiche l'heure sur 24 heures`() {
        val epoch = (22 * 3600 + 41 * 60) * 1000L
        assertEquals("22:41", formatClock(epoch, ZoneOffset.UTC))
    }
}
