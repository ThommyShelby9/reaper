package com.lecteur.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lecteur.player.ui.theme.LecteurTheme

/**
 * Potentiomètre rotatif (0..1, de -135° à +135°). On le règle en glissant vers le haut ou le bas.
 * [label] est affiché dessous, [valueText] est lu par les services d'accessibilité.
 */
@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    accentMark: Boolean = false,
) {
    val colors = LecteurTheme.colors
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val markColor = if (accentMark) colors.accent else colors.knobMark

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            Modifier
                .size(52.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
                    setProgress { change(it.coerceIn(0f, 1f)); true }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { pointer, dragAmount ->
                        pointer.consume()
                        change((current - dragAmount / (size.height * 3f)).coerceIn(0f, 1f))
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            drawCircle(Color.Black.copy(alpha = 0.25f), radius, center + Offset(0f, 3.dp.toPx()))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.knobLight, colors.knobShade),
                    center = Offset(size.width * 0.4f, size.height * 0.35f),
                    radius = size.width * 0.75f,
                ),
                radius = radius,
            )
            drawCircle(colors.line, radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            rotate(degrees = -135f + current * 270f) {
                drawLine(
                    color = markColor,
                    start = Offset(center.x, 6.dp.toPx()),
                    end = Offset(center.x, 19.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Text(label, style = LecteurTheme.type.label, color = colors.inkMuted)
    }
}
