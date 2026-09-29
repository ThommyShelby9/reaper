package com.lecteur.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lecteur.player.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int, width: Float? = null): Font {
    val settings = if (width != null) {
        FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width))
    } else {
        FontVariation.Settings(FontVariation.weight(weight))
    }
    return Font(res, FontWeight(weight), variationSettings = settings)
}

/** Doto : police en matrice de points, pour titres, chiffres et écrans. */
val Doto = FontFamily(
    variable(R.font.doto, 700),
    variable(R.font.doto, 900),
)

/** Archivo : texte courant du thème clair. Largeur réduite pour les titres. */
val Archivo = FontFamily(
    variable(R.font.archivo, 400),
    variable(R.font.archivo, 600),
    variable(R.font.archivo, 800, width = 72f),
)

/** Space Mono : texte courant du thème sombre. */
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

@Immutable
data class LecteurType(
    /** Grands chiffres et codes (temps, code de jam). */
    val displayLarge: TextStyle,
    /** Titre du morceau en points. */
    val displayTitle: TextStyle,
    /** Valeurs des tuiles (BPM, tonalité). */
    val displayValue: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
)

private fun typeFor(bodyFamily: FontFamily) = LecteurType(
    displayLarge = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 30.sp, lineHeight = 30.sp),
    displayTitle = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 20.sp, lineHeight = 21.sp),
    displayValue = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 22.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodyStrong = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight(600), fontSize = 13.sp, lineHeight = 18.sp),
    label = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
)

val AppareilType = typeFor(Archivo)
val NuitType = typeFor(SpaceMono)
