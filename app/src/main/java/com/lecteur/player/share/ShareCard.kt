package com.lecteur.player.share

import com.lecteur.player.lyrics.Lyrics

/** Modèles de carte à partager (format story 9:16). */
enum class ShareTemplate(val label: String, val needsLyrics: Boolean) {
    Cover("Pochette", false),
    CoverLyrics("Pochette + paroles", true),
    Lyrics("Paroles", true),
}

/** Extrait de paroles pour une carte : [lines] à afficher, [highlight] = index de la ligne en cours (ou -1). */
data class LyricExcerpt(val lines: List<String>, val highlight: Int, val canGoBack: Boolean, val canGoForward: Boolean)

/**
 * Choisit [count] lignes de paroles non vides autour du moment écouté : la ligne en cours en tête
 * (paroles synchronisées), sinon le début. [shift] déplace la fenêtre d'autant de lignes.
 */
fun lyricExcerpt(lyrics: Lyrics, positionMs: Long, shift: Int, count: Int = 4): LyricExcerpt {
    val lines = lyrics.lines.filter { it.text.isNotBlank() }
    if (lines.isEmpty()) return LyricExcerpt(emptyList(), -1, canGoBack = false, canGoForward = false)
    val current = if (lyrics.synced) lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0) else 0
    val maxStart = (lines.size - count).coerceAtLeast(0)
    val start = (current + shift).coerceIn(0, maxStart)
    val window = lines.subList(start, minOf(lines.size, start + count)).map { it.text.trim() }
    val highlight = if (lyrics.synced && current in start until start + window.size) current - start else -1
    return LyricExcerpt(window, highlight, canGoBack = start > 0, canGoForward = start < maxStart)
}
