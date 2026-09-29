package com.lecteur.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.player.ui.theme.LecteurTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Curseur vertical de console (0..1, 0,5 = neutre). La colonne de points s'allume entre
 * le milieu et le curseur. On le règle en glissant ou en touchant la piste.
 */
@Composable
fun Fader(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueText: String,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 180.dp,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val lit = if (enabled) colors.accent else colors.inkMuted

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(valueText, style = type.label.copy(fontSize = 10.sp), color = colors.inkMuted, textAlign = TextAlign.Center, maxLines = 1)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .semantics {
                    contentDescription = accessibilityLabel
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
                    setProgress { change(it.coerceIn(0f, 1f)); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures { change((1f - it.y / size.height).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { pointer, _ ->
                        pointer.consume()
                        change((1f - pointer.position.y / size.height).coerceIn(0f, 1f))
                    }
                },
        ) {
            val dots = 17
            val step = size.height / dots
            val radius = minOf(step, size.width) * 0.2f
            val cx = size.width / 2f
            val middle = dots / 2
            val valueDot = ((1f - current) * (dots - 1)).roundToInt()
            for (i in 0 until dots) {
                val between = i in minOf(middle, valueDot)..maxOf(middle, valueDot)
                val color = if (between && valueDot != middle || i == middle) lit else colors.line
                drawCircle(color, radius, Offset(cx - step * 0.6f, (i + 0.5f) * step))
            }
            // Capuchon du curseur, avec sa tranche.
            val capW = size.width * 0.52f
            val capH = 16.dp.toPx()
            val y = ((1f - current) * (size.height - capH)).coerceIn(0f, size.height - capH)
            val left = cx - capW / 2f + step * 0.25f
            drawRoundRect(colors.keyEdge, Offset(left, y + 3.dp.toPx()), Size(capW, capH), CornerRadius(4.dp.toPx()))
            drawRoundRect(colors.keyFace, Offset(left, y), Size(capW, capH), CornerRadius(4.dp.toPx()))
            drawLine(
                if (abs(current - 0.5f) < 0.01f) colors.inkMuted else lit,
                Offset(left + capW * 0.2f, y + capH / 2f),
                Offset(left + capW * 0.8f, y + capH / 2f),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Text(label, style = type.label, color = colors.ink, textAlign = TextAlign.Center, maxLines = 1)
    }
}
