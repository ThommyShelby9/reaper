package com.lecteur.player.share

import com.lecteur.player.lyrics.LyricLine
import com.lecteur.player.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCardTest {

    private val synced = Lyrics(
        synced = true,
        lines = listOf(
            LyricLine(0, "un"),
            LyricLine(5_000, ""),
            LyricLine(10_000, "deux"),
            LyricLine(20_000, "trois"),
            LyricLine(30_000, "quatre"),
            LyricLine(40_000, "cinq"),
            LyricLine(50_000, "six"),
        ),
    )

    @Test
    fun `la ligne en cours ouvre l'extrait et les lignes vides sont ignorées`() {
        val excerpt = lyricExcerpt(synced, positionMs = 21_000, shift = 0)
        assertEquals(listOf("trois", "quatre", "cinq", "six"), excerpt.lines)
        assertEquals(0, excerpt.highlight)
        assertTrue(excerpt.canGoBack)
        assertFalse(excerpt.canGoForward)
    }

    @Test
    fun `reculer d'une ligne garde la ligne en cours en évidence`() {
        val excerpt = lyricExcerpt(synced, positionMs = 21_000, shift = -1)
        assertEquals(listOf("deux", "trois", "quatre", "cinq"), excerpt.lines)
        assertEquals(1, excerpt.highlight)
    }

    @Test
    fun `la fenêtre reste dans les paroles`() {
        assertEquals(listOf("un", "deux", "trois", "quatre"), lyricExcerpt(synced, 0, shift = -10).lines)
        assertEquals(listOf("trois", "quatre", "cinq", "six"), lyricExcerpt(synced, 0, shift = 10).lines)
    }

    @Test
    fun `paroles non synchronisées, début du texte sans ligne en évidence`() {
        val plain = Lyrics(synced = false, lines = listOf("a", "b", "c").map { LyricLine(-1, it) })
        val excerpt = lyricExcerpt(plain, positionMs = 99_000, shift = 0)
        assertEquals(listOf("a", "b", "c"), excerpt.lines)
        assertEquals(-1, excerpt.highlight)
        assertFalse(excerpt.canGoBack)
        assertFalse(excerpt.canGoForward)
    }
}
