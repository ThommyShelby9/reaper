package com.lecteur.player.ui.nowplaying

import java.util.Locale

/*
 * Le potentiomètre de vitesse va de 0 à 1 et vaut 1× au milieu :
 * la moitié gauche couvre 0,5× → 1×, la moitié droite 1× → 2×.
 */

fun knobToSpeed(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return if (v <= 0.5f) 0.5f + v else 1f + (v - 0.5f) * 2f
}

fun speedToKnob(speed: Float): Float {
    val s = speed.coerceIn(0.5f, 2f)
    return if (s <= 1f) s - 0.5f else 0.5f + (s - 1f) / 2f
}

/** « 1,00× », « 1,25× » : format français. */
fun formatSpeed(speed: Float): String = String.format(Locale.FRANCE, "%.2f×", speed)
