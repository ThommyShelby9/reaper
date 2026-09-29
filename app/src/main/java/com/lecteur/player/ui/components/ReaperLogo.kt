package com.lecteur.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lecteur.player.ui.theme.LecteurTheme

/** Grille du logo « Capuche » : X = point allumé, O = le regard orange. Même dessin que l'icône de l'app. */
private val LogoRows = listOf(
    "...XXX...",
    "..XXXXX..",
    ".XXX.XXX.",
    ".XX...XX.",
    "XX..O..XX",
    "XX.....XX",
    "XXX...XXX",
    "XXXX.XXXX",
    "XXXXXXXXX",
)

/** Le logo Reaper en matrice de points. */
@Composable
fun ReaperLogo(
    modifier: Modifier = Modifier,
    dotColor: Color = LecteurTheme.colors.displayInk,
    eyeColor: Color = LecteurTheme.colors.accent,
) {
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "Logo Reaper" }) {
        val cell = size.minDimension / LogoRows.size
        val radius = cell * 0.39f
        LogoRows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val color = when (c) {
                    'X' -> dotColor
                    'O' -> eyeColor
                    else -> return@forEachIndexed
                }
                drawCircle(color, radius, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
            }
        }
    }
}
