package com.lecteur.player.data

import java.text.Normalizer

private val CombiningMarks = Regex("\\p{Mn}+")
private val Spaces = Regex("\\s+")

/** Minuscules sans accents : « Écoute » devient « ecoute ». */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(CombiningMarks, "").lowercase()

/**
 * Vrai si chaque mot de [query] apparaît dans le titre, l'artiste ou l'album,
 * sans tenir compte des accents ni de la casse. Une recherche vide accepte tout.
 */
fun matchesQuery(track: Track, query: String): Boolean {
    val words = normalizeForSearch(query).split(Spaces).filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val haystack = normalizeForSearch("${track.title} ${track.artist} ${track.album}")
    return words.all { it in haystack }
}
