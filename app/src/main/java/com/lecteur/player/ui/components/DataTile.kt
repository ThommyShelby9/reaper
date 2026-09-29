package com.lecteur.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.player.ui.theme.LecteurTheme

private val TileShape = RoundedCornerShape(8.dp)

/** Tuile de donnée : une valeur en points (124, 8A…) et son libellé. */
@Composable
fun DataTile(value: String, label: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LecteurTheme.colors
    Column(
        modifier
            .clip(TileShape)
            .background(colors.tile)
            .border(1.dp, colors.line, TileShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        // [compact] : valeur en toutes lettres (« Mélancolique »), trop longue pour la police à points.
        Text(value, style = if (compact) LecteurTheme.type.bodyStrong.copy(fontSize = 15.sp, lineHeight = 24.sp) else LecteurTheme.type.displayValue, color = colors.ink, maxLines = 1)
        Text(label, style = LecteurTheme.type.label, color = colors.inkMuted, maxLines = 1)
    }
}
