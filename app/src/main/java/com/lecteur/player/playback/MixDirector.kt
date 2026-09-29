package com.lecteur.player.playback

import com.lecteur.player.analysis.AudioFeatures
import com.lecteur.player.analysis.TransitionPlan
import com.lecteur.player.analysis.planTransition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mode Mix, partagé par l'interface et le service (même processus) : l'interface l'active en lançant
 * un mix, le fondu enchaîné du service s'en sert pour caler chaque transition sur le tempo.
 */
object MixDirector {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Caractéristiques audio connues, par identifiant de morceau (tenues à jour par le service). */
    @Volatile var features: Map<Long, AudioFeatures> = emptyMap()

    fun setActive(active: Boolean) {
        _active.value = active
    }

    fun featuresOf(trackId: Long?): AudioFeatures? = trackId?.let { features[it] }

    fun plan(outgoingId: Long?, incomingId: Long?): TransitionPlan = planTransition(featuresOf(outgoingId), featuresOf(incomingId))
}
