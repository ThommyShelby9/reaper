package com.lecteur.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalLecteurColors = staticCompositionLocalOf { AppareilColors }
private val LocalLecteurType = staticCompositionLocalOf { AppareilType }

object LecteurTheme {
    val colors: LecteurColors
        @Composable @ReadOnlyComposable get() = LocalLecteurColors.current

    val type: LecteurType
        @Composable @ReadOnlyComposable get() = LocalLecteurType.current
}

/** Thème clair « Appareil » ou sombre « Nuit », selon le thème du téléphone. */
@Composable
fun LecteurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NuitColors else AppareilColors
    val type = if (darkTheme) NuitType else AppareilType
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.body,
            onBackground = colors.ink,
            surface = colors.body,
            onSurface = colors.ink,
            outline = colors.line,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.body,
            onBackground = colors.ink,
            surface = colors.body,
            onSurface = colors.ink,
            outline = colors.line,
        )
    }
    CompositionLocalProvider(
        LocalLecteurColors provides colors,
        LocalLecteurType provides type,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
