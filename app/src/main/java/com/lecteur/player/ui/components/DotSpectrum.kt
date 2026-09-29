package com.lecteur.player.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lecteur.player.audio.AudioSpectrum
import com.lecteur.player.ui.theme.LecteurTheme
import kotlin.math.roundToInt

/** Vrai si l'utilisateur a coupé les animations dans les réglages d'accessibilité d'Android. */
@Composable
fun animationsDisabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Niveaux et crêtes du visualiseur, rafraîchis à chaque image tant que [visible]. */
class SpectrumFrame(val levels: FloatArray, val peaks: FloatArray)

@Composable
fun rememberSpectrum(visible: Boolean, isPlaying: Boolean): SpectrumFrame {
    var frame by remember { mutableStateOf(SpectrumFrame(FloatArray(AudioSpectrum.BANDS), FloatArray(AudioSpectrum.BANDS))) }
    DisposableEffect(visible) {
        AudioSpectrum.active = visible
        onDispose { AudioSpectrum.active = false }
    }
    LaunchedEffect(visible, isPlaying) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val previous = frame
            // En pause, le son ne passe plus : les barres retombent doucement.
            val levels = if (isPlaying) AudioSpectrum.levels.copyOf() else FloatArray(previous.levels.size) { previous.levels[it] * 0.9f }
            val peaks = FloatArray(levels.size) { maxOf(levels[it], previous.peaks.getOrElse(it) { 0f } - 0.012f) }
            frame = SpectrumFrame(levels, peaks)
            if (!isPlaying && levels.all { it < 0.01f } && peaks.all { it <= 0f }) break
        }
    }
    return frame
}

/**
 * Spectre en colonnes de points, dans le même carré que la pochette :
 * une colonne par bande de fréquence, la crête de chaque colonne en orange.
 */
@Composable
fun DotSpectrum(
    frame: SpectrumFrame,
    modifier: Modifier = Modifier,
    litColor: Color = LecteurTheme.colors.displayInk,
    peakColor: Color = LecteurTheme.colors.accent,
    offColor: Color = LecteurTheme.colors.dotOff,
) {
    Canvas(modifier.aspectRatio(1f)) {
        val columns = frame.levels.size
        val rows = columns
        val cell = size.minDimension / columns
        val radius = cell * 0.34f
        for (c in 0 until columns) {
            val lit = (frame.levels[c] * rows).roundToInt().coerceIn(0, rows)
            val peak = (frame.peaks[c] * rows).roundToInt().coerceIn(0, rows) - 1
            for (r in 0 until rows) {
                val color = when {
                    r == peak && peak >= 0 -> peakColor
                    r < lit -> litColor
                    else -> offColor
                }
                drawCircle(color, radius, Offset((c + 0.5f) * cell, size.height - (r + 0.5f) * cell))
            }
        }
    }
}
