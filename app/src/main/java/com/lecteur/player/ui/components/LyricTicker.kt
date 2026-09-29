package com.lecteur.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.lecteur.player.lyrics.Lyrics
import com.lecteur.player.lyrics.currentLyricLine

/**
 * Paroles au rythme du morceau, une phrase à la fois : la ligne chantée remplace la précédente en glissant.
 * Hauteur fixe ([height]) pour que le reste de l'écran ne bouge pas. Rien si les paroles ne sont pas synchronisées.
 */
@Composable
fun LyricTicker(
    lyrics: Lyrics?,
    positionMs: Long,
    style: TextStyle,
    color: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    if (lyrics == null || !lyrics.synced) return
    val line = currentLyricLine(lyrics, positionMs)
    AnimatedContent(
        targetState = line,
        transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) },
        label = "ligne de paroles",
        modifier = modifier.fillMaxWidth().height(height).semantics { liveRegion = LiveRegionMode.Polite },
    ) { text ->
        Text(
            text ?: "",
            style = style,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
