package com.lecteur.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Dernière liste de morceaux lue, partagée dans le processus : un écran qui s'ouvre (écran de verrouillage)
 * ou le service de lecture l'ont tout de suite, sans relire MediaStore.
 */
object SharedLibrary {
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    val tracks: StateFlow<List<Track>?> = _tracks

    fun publish(list: List<Track>) {
        _tracks.value = list
    }

    fun find(id: Long): Track? = _tracks.value?.firstOrNull { it.id == id }

    /** Le morceau [id], quitte à lire la bibliothèque si personne ne l'a encore fait. */
    suspend fun load(context: Context, id: Long): Track? =
        find(id) ?: runCatching { AudioLibrary(context).tracks().first() }.getOrNull()?.also(::publish)?.firstOrNull { it.id == id }
}
