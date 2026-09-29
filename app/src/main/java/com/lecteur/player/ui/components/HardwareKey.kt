package com.lecteur.player.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lecteur.player.ui.theme.LecteurTheme

enum class KeyStyle { Standard, Accent, Dark, Selected }

private val KeyShape = RoundedCornerShape(10.dp)
private val KeyDepth = 4.dp

/**
 * Touche physique en relief : la face s'enfonce dans sa tranche à l'appui,
 * avec un petit retour haptique de clavier.
 */
@Composable
fun HardwareKey(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    style: KeyStyle = KeyStyle.Standard,
    content: @Composable () -> Unit,
) {
    val colors = LecteurTheme.colors
    val face: Color
    val edge: Color
    val ink: Color
    when (style) {
        KeyStyle.Standard -> { face = colors.keyFace; edge = colors.keyEdge; ink = colors.keyInk }
        KeyStyle.Accent -> { face = colors.accent; edge = colors.accentEdge; ink = colors.onAccent }
        KeyStyle.Dark -> { face = colors.keyDarkFace; edge = colors.keyDarkEdge; ink = colors.keyDarkInk }
        KeyStyle.Selected -> { face = colors.keySelectedFace; edge = colors.keySelectedEdge; ink = colors.keySelectedInk }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel by animateDpAsState(
        targetValue = if (pressed) KeyDepth else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "key-travel",
    )
    val view = LocalView.current

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(top = KeyDepth)
                .background(edge, KeyShape),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = KeyDepth)
                .offset { IntOffset(0, travel.roundToPx()) }
                .background(face, KeyShape)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(0f to colors.keyHighlight.copy(alpha = 0.6f), 0.18f to Color.Transparent),
                    shape = KeyShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink) { content() }
        }
    }
}
