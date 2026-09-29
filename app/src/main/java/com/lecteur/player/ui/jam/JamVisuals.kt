package com.lecteur.player.ui.jam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lecteur.player.jam.JamManager
import com.lecteur.player.jam.JamReaction
import com.lecteur.player.jam.qrMatrix
import com.lecteur.player.ui.components.animationsDisabled
import com.lecteur.player.ui.theme.LecteurTheme
import kotlin.random.Random

/**
 * QR code dessiné en points, dans le style de l'app. Points foncés sur fond clair, avec la marge
 * réglementaire de 4 modules : c'est ce que les appareils photo lisent le plus sûrement.
 */
@Composable
fun QrDots(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    val ink = Color(0xFF151515)
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF7F6F2))
            .semantics { contentDescription = description },
    ) {
        val quiet = 4
        val modules = matrix.size + quiet * 2
        val cell = size.minDimension / modules
        for (y in matrix.indices) {
            for (x in matrix[y].indices) {
                if (!matrix[y][x]) continue
                val finder = (x < 7 && y < 7) || (x >= matrix.size - 7 && y < 7) || (x < 7 && y >= matrix.size - 7)
                // Les trois repères d'angle restent pleins (points plus gros) : ils guident la lecture.
                drawCircle(ink, cell * if (finder) 0.52f else 0.44f, Offset((x + quiet + 0.5f) * cell, (y + quiet + 0.5f) * cell))
            }
        }
    }
}

private class Burst(val reaction: JamReaction, val seed: Int)

/**
 * Réactions de la jam, par-dessus toute l'app : une salve de points orange qui monte et s'efface,
 * avec le prénom. Si les animations sont coupées, une simple ligne de texte.
 */
@Composable
fun BoxScope.JamReactionsOverlay() {
    val bursts = remember { mutableStateListOf<Burst>() }
    val still = animationsDisabled()
    LaunchedEffect(Unit) {
        JamManager.reactions.collect { reaction ->
            bursts += Burst(reaction, Random.nextInt())
            if (bursts.size > 6) bursts.removeAt(0)
        }
    }
    bursts.toList().forEach { burst ->
        androidx.compose.runtime.key(burst.reaction.id) {
            ReactionBurst(burst, still, onDone = { bursts.remove(burst) })
        }
    }
}

@Composable
private fun BoxScope.ReactionBurst(burst: Burst, still: Boolean, onDone: () -> Unit) {
    val colors = LecteurTheme.colors
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(if (still) 2_500 else 1_800, easing = LinearEasing))
        onDone()
    }
    val name = burst.reaction.name.substringBefore(' ')
    if (!still) {
        val random = remember { Random(burst.seed) }
        val dots = remember { List(14) { Triple(random.nextFloat() * 2f - 1f, 0.6f + random.nextFloat() * 0.6f, 3f + random.nextFloat() * 5f) } }
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value
            dots.forEach { (dx, speed, radius) ->
                val x = size.width * (0.5f + dx * 0.18f * (0.4f + t))
                val y = size.height * (0.92f - t * speed * 0.55f)
                drawCircle(colors.accent.copy(alpha = (1f - t).coerceIn(0f, 1f)), radius.dp.toPx(), Offset(x, y))
            }
        }
    }
    Text(
        "$name réagit",
        style = LecteurTheme.type.label,
        color = colors.onAccent,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 96.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent.copy(alpha = (1f - progress.value * 0.8f).coerceIn(0f, 1f)))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
