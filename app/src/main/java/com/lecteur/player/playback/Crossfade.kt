package com.lecteur.player.playback

import androidx.media3.common.Player
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Gains appliqués pendant un fondu : le titre qui part et celui qui arrive. */
data class FadeGains(val outgoing: Float, val incoming: Float)

/**
 * Courbe à puissance constante : à mi-parcours, les deux titres sont à ~71 %,
 * ce qui évite le « creux » de volume d'un fondu linéaire.
 */
fun fadeGains(elapsedMs: Long, durationMs: Long): FadeGains {
    if (durationMs <= 0) return FadeGains(0f, 1f)
    val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    return FadeGains(outgoing = cos(t * PI / 2).toFloat(), incoming = sin(t * PI / 2).toFloat())
}

/**
 * Faut-il lancer le fondu maintenant ? Seulement en lecture, vers la fin d'un titre suivi d'un autre,
 * et pas en répétition d'un seul titre (on reste sur le même morceau).
 */
fun shouldStartCrossfade(
    crossfadeMs: Long,
    remainingMs: Long,
    hasNextItem: Boolean,
    repeatMode: Int,
    isPlaying: Boolean,
    alreadyFading: Boolean,
): Boolean =
    crossfadeMs > 0 &&
        isPlaying &&
        !alreadyFading &&
        hasNextItem &&
        repeatMode != Player.REPEAT_MODE_ONE &&
        remainingMs in 1..crossfadeMs
