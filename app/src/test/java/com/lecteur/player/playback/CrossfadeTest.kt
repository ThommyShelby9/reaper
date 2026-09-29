package com.lecteur.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeTest {

    @Test
    fun `le fondu part de l'ancien titre et finit sur le nouveau`() {
        assertEquals(FadeGains(1f, 0f), fadeGains(0, 6_000))
        val end = fadeGains(6_000, 6_000)
        assertEquals(0f, end.outgoing, 0.0001f)
        assertEquals(1f, end.incoming, 0.0001f)
    }

    @Test
    fun `à mi-parcours la puissance totale reste constante`() {
        val middle = fadeGains(3_000, 6_000)
        assertEquals(0.7071f, middle.outgoing, 0.001f)
        assertEquals(1f, middle.outgoing * middle.outgoing + middle.incoming * middle.incoming, 0.001f)
    }

    @Test
    fun `une durée nulle passe directement au nouveau titre`() {
        assertEquals(FadeGains(0f, 1f), fadeGains(0, 0))
    }

    private fun start(
        remaining: Long = 4_000,
        hasNext: Boolean = true,
        repeat: Int = Player.REPEAT_MODE_OFF,
        playing: Boolean = true,
        fading: Boolean = false,
        crossfade: Long = 6_000,
    ) = shouldStartCrossfade(crossfade, remaining, hasNext, repeat, playing, fading)

    @Test
    fun `le fondu démarre dans les dernières secondes d'un titre suivi d'un autre`() {
        assertTrue(start())
        assertTrue(start(repeat = Player.REPEAT_MODE_ALL))
    }

    @Test
    fun `pas de fondu trop tôt, sans suivant, en pause, désactivé, déjà en cours ou en répétition d'un titre`() {
        assertFalse(start(remaining = 10_000))
        assertFalse(start(hasNext = false))
        assertFalse(start(playing = false))
        assertFalse(start(crossfade = 0))
        assertFalse(start(fading = true))
        assertFalse(start(repeat = Player.REPEAT_MODE_ONE))
        assertFalse(start(remaining = 0))
    }
}
