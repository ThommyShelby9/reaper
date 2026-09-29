package com.lecteur.player.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectsModelTest {

    private val bands = listOf(60, 230, 910, 3_600, 14_000)

    @Test
    fun `le Bluetooth passe avant le casque filaire, qui passe avant le haut-parleur`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals(OutputKind.Speaker, outputKindOf(listOf(speaker)))
        assertEquals(OutputKind.Wired, outputKindOf(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertEquals(
            OutputKind.Bluetooth,
            outputKindOf(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
        )
    }

    @Test
    fun `curveGain renvoie les points exacts et borne aux extrémités`() {
        val points = listOf(100 to 6f, 400 to 0f)
        assertEquals(6f, curveGain(points, 100), 0.001f)
        assertEquals(6f, curveGain(points, 20), 0.001f)
        assertEquals(0f, curveGain(points, 10_000), 0.001f)
    }

    @Test
    fun `curveGain interpole en octaves`() {
        // 200 Hz est à une octave de 100 et de 400 : pile au milieu.
        assertEquals(3f, curveGain(listOf(100 to 6f, 400 to 0f), 200), 0.001f)
    }

    @Test
    fun `le réglage plat met toutes les bandes à zéro`() {
        assertEquals(listOf(0, 0, 0, 0, 0), presetLevelsMb(EqPreset.Flat, bands, -1500, 1500))
    }

    @Test
    fun `les niveaux sont bornés à la plage de l'appareil`() {
        val levels = presetLevelsMb(EqPreset.Bass, bands, -300, 300)
        assertEquals(300, levels.first())
        assertEquals(0, levels.last())
    }

    @Test
    fun `matchingPreset reconnaît un réglage prédéfini`() {
        val levels = presetLevelsMb(EqPreset.Voice, bands, -1500, 1500)
        assertEquals(EqPreset.Voice, matchingPreset(levels, bands, -1500, 1500))
        assertNull(matchingPreset(listOf(1, 2, 3, 4, 5), bands, -1500, 1500))
    }

    @Test
    fun `formatFrequency abrège les kilohertz`() {
        assertEquals("60", formatFrequency(60))
        assertEquals("3,6k", formatFrequency(3_600))
        assertEquals("1k", formatFrequency(1_000))
        assertEquals("14k", formatFrequency(14_000))
    }

    @Test
    fun `formatGain affiche le signe et les décimales utiles`() {
        assertEquals("+3 dB", formatGain(300))
        assertEquals("0 dB", formatGain(0))
        assertEquals("−2,5 dB", formatGain(-250))
    }

    @Test
    fun `decodeLevels relit encodeLevels et refuse un autre nombre de bandes`() {
        val levels = listOf(300, 0, -150, 0, 200)
        assertEquals(levels, decodeLevels(encodeLevels(levels), 5))
        assertNull(decodeLevels(encodeLevels(levels), 10))
        assertNull(decodeLevels("a,b", 2))
        assertNull(decodeLevels(null, 5))
    }
}
