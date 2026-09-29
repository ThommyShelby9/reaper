package com.lecteur.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Pochette affichée telle quelle : l'image d'origine, ni tramée ni recolorée.
 * Sans pochette, une matrice de points éteints tient la place.
 */
@Composable
fun CoverImage(cover: ImageBitmap?, description: String, modifier: Modifier = Modifier) {
    if (cover == null) {
        val empty = remember { dotGrid(22) { _, _ -> 0.12f } }
        DotMatrixArt(empty, modifier.semantics { contentDescription = "$description : aucune pochette" })
        return
    }
    Image(
        bitmap = cover,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
    )
}
