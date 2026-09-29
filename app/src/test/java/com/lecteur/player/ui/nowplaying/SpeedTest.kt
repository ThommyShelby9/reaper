package com.lecteur.player.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTest {

    @Test
    fun `le milieu du potentiomètre vaut 1x et les bords 0,5x et 2x`() {
        assertEquals(0.5f, knobToSpeed(0f), 0.0001f)
        assertEquals(1f, knobToSpeed(0.5f), 0.0001f)
        assertEquals(2f, knobToSpeed(1f), 0.0001f)
    }

    @Test
    fun `speedToKnob est l'inverse de knobToSpeed`() {
        for (v in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            assertEquals(v, speedToKnob(knobToSpeed(v)), 0.0001f)
        }
    }

    @Test
    fun `formatSpeed utilise la virgule française`() {
        assertEquals("1,25×", formatSpeed(1.25f))
    }
}
