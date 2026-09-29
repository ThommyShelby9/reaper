package com.lecteur.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Palette du système « Console + Tableau ».
 * Thème clair = « Appareil » (corps clair, écran noir encastré).
 * Thème sombre = « Nuit » (tout noir, touches sombres en relief).
 * L'orange est le seul accent : action principale, « en direct », position de lecture.
 */
@Immutable
data class LecteurColors(
    val isDark: Boolean,
    /** Fond de l'écran (le « boîtier »). */
    val body: Color,
    /** Tuiles de données et cartes posées sur le boîtier. */
    val tile: Color,
    /** Contour des tuiles et séparateurs. */
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    /** Écran à points encastré (noir dans les deux thèmes). */
    val display: Color,
    val displayInk: Color,
    val displayMuted: Color,
    /** Point éteint sur l'écran à points. */
    val dotOff: Color,
    /** Touche standard : face, tranche, reflet du bord supérieur. */
    val keyFace: Color,
    val keyEdge: Color,
    val keyHighlight: Color,
    val keyInk: Color,
    /** Touche secondaire foncée (répéter, aléatoire…). */
    val keyDarkFace: Color,
    val keyDarkEdge: Color,
    val keyDarkInk: Color,
    /** Touche sélectionnée (filtre actif). */
    val keySelectedFace: Color,
    val keySelectedEdge: Color,
    val keySelectedInk: Color,
    val accent: Color,
    val accentEdge: Color,
    val onAccent: Color,
    /** Potentiomètre : dégradé du capuchon et repère. */
    val knobLight: Color,
    val knobShade: Color,
    val knobMark: Color,
)

val Orange = Color(0xFFFF5A1F)
private val OrangeEdge = Color(0xFFB53C10)

val AppareilColors = LecteurColors(
    isDark = false,
    body = Color(0xFFE4E3DE),
    tile = Color(0xFFF2F1ED),
    line = Color(0xFFCFCEC8),
    ink = Color(0xFF151515),
    inkMuted = Color(0xFF55544F),
    display = Color(0xFF0C0C0C),
    displayInk = Color(0xFFFFFFFF),
    displayMuted = Color(0xFF8E8E93),
    dotOff = Color(0xFF3A3A3C),
    keyFace = Color(0xFFF7F6F2),
    keyEdge = Color(0xFFB3B2AB),
    keyHighlight = Color(0xFFFFFFFF),
    keyInk = Color(0xFF151515),
    keyDarkFace = Color(0xFF262626),
    keyDarkEdge = Color(0xFF000000),
    keyDarkInk = Color(0xFFE4E3DE),
    keySelectedFace = Color(0xFF151515),
    keySelectedEdge = Color(0xFF000000),
    keySelectedInk = Color(0xFFF7F6F2),
    accent = Orange,
    accentEdge = OrangeEdge,
    onAccent = Color(0xFF151515),
    knobLight = Color(0xFFFAFAF8),
    knobShade = Color(0xFFCFCEC8),
    knobMark = Color(0xFF151515),
)

val NuitColors = LecteurColors(
    isDark = true,
    body = Color(0xFF000000),
    tile = Color(0xFF000000),
    line = Color(0xFF2C2C2E),
    ink = Color(0xFFFFFFFF),
    inkMuted = Color(0xFF8E8E93),
    display = Color(0xFF000000),
    displayInk = Color(0xFFFFFFFF),
    displayMuted = Color(0xFF8E8E93),
    dotOff = Color(0xFF3A3A3C),
    keyFace = Color(0xFF1C1C1E),
    keyEdge = Color(0xFF0A0A0A),
    keyHighlight = Color(0xFF3A3A3C),
    keyInk = Color(0xFFFFFFFF),
    keyDarkFace = Color(0xFF1C1C1E),
    keyDarkEdge = Color(0xFF0A0A0A),
    keyDarkInk = Color(0xFF8E8E93),
    keySelectedFace = Color(0xFFFFFFFF),
    keySelectedEdge = Color(0xFF8E8E93),
    keySelectedInk = Color(0xFF000000),
    accent = Orange,
    accentEdge = OrangeEdge,
    onAccent = Color(0xFF000000),
    knobLight = Color(0xFF3A3A3C),
    knobShade = Color(0xFF111111),
    knobMark = Color(0xFFFFFFFF),
)
