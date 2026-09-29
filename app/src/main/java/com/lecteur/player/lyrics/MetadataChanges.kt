package com.lecteur.player.lyrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Paroles ou pochette choisies à la main : chaque changement augmente [version],
 * et l'app comme le service de lecture rechargent ce qu'ils affichent.
 */
object MetadataChanges {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun notifyChanged() {
        _version.value++
    }
}
