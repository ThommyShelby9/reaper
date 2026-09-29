package com.lecteur.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.lecteur.player.ui.theme.LecteurTheme
import kotlin.math.max

/**
 * Image redessinée en matrice de points : la taille de chaque point suit la luminosité,
 * les zones au-dessus de [threshold] prennent [litColor].
 */
@Composable
fun DotMatrixArt(
    grid: DotGrid,
    modifier: Modifier = Modifier,
    litColor: Color = LecteurTheme.colors.displayInk,
    dimColor: Color = LecteurTheme.colors.displayMuted,
    threshold: Float = 0.8f,
) {
    Canvas(modifier.aspectRatio(1f)) {
        val cell = size.minDimension / grid.size
        for (y in 0 until grid.size) {
            for (x in 0 until grid.size) {
                val l = grid[x, y]
                drawCircle(
                    color = if (l > threshold) litColor else dimColor,
                    radius = max(cell * 0.08f, l * cell * 0.46f),
                    center = Offset((x + 0.5f) * cell, (y + 0.5f) * cell),
                )
            }
        }
    }
}

/**
 * Forme d'onde du morceau en colonnes de points, qui sert aussi de barre de progression.
 * Toucher ou glisser déplace la lecture ; les services d'accessibilité peuvent la régler aussi.
 */
@Composable
fun DotWaveform(
    amplitudes: FloatArray,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    rows: Int = 7,
    playedColor: Color = LecteurTheme.colors.accent,
    headColor: Color = LecteurTheme.colors.displayInk,
    restColor: Color = LecteurTheme.colors.dotOff,
) {
    val currentProgress by rememberUpdatedState(progress)
    val seek by rememberUpdatedState(onSeek)
    Box(
        modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(currentProgress, 0f..1f)
                setProgress { target -> seek(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .drawWithCache {
                val cell = size.height / rows
                val cols = max(1, (size.width / cell).toInt())
                val columns = resample(amplitudes, cols)
                val lit = IntArray(cols) { litDots(columns[it], rows) }
                val left = (size.width - cols * cell) / 2f
                val radius = cell * 0.34f
                onDrawBehind {
                    val head = (currentProgress * cols).toInt().coerceIn(0, cols - 1)
                    for (i in 0 until cols) {
                        val color = when {
                            i == head -> headColor
                            i < head -> playedColor
                            else -> restColor
                        }
                        for (r in 0 until lit[i]) {
                            drawCircle(color, radius, Offset(left + (i + 0.5f) * cell, size.height - (r + 0.5f) * cell))
                        }
                    }
                }
            },
    )
}

/** Vumètre en points, horizontal ou vertical, pour les niveaux des participants en jam. */
@Composable
fun DotMeter(
    level: Float,
    modifier: Modifier = Modifier,
    dots: Int = 8,
    vertical: Boolean = false,
    onColor: Color = LecteurTheme.colors.accent,
    offColor: Color = LecteurTheme.colors.line,
) {
    Canvas(modifier) {
        val lit = (level.coerceIn(0f, 1f) * dots).toInt()
        val step = (if (vertical) size.height else size.width) / dots
        val radius = minOf(step, if (vertical) size.width else size.height) * 0.34f
        for (i in 0 until dots) {
            val center = if (vertical) {
                Offset(size.width / 2f, size.height - (i + 0.5f) * step)
            } else {
                Offset((i + 0.5f) * step, size.height / 2f)
            }
            drawCircle(if (i < lit) onColor else offColor, radius, center)
        }
    }
}

/** Point orange « en direct » / « en cours ». */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(LecteurTheme.colors.accent, CircleShape))
}
