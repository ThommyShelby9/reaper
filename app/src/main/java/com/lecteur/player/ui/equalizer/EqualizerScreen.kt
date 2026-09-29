package com.lecteur.player.ui.equalizer

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.audio.AudioEffects
import com.lecteur.player.audio.EffectsState
import com.lecteur.player.audio.EqBand
import com.lecteur.player.audio.EqPreset
import com.lecteur.player.audio.OutputKind
import com.lecteur.player.audio.formatFrequency
import com.lecteur.player.audio.formatGain
import com.lecteur.player.ui.components.Fader
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.Knob
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.theme.LecteurTheme
import kotlin.math.roundToInt

class EqualizerActions(
    val onClose: () -> Unit,
    val onEnabledChange: (Boolean) -> Unit,
    val onBandChange: (band: Int, levelMb: Int) -> Unit,
    val onPreset: (EqPreset) -> Unit,
    val onBassChange: (Float) -> Unit,
)

@Composable
fun EqualizerRoute(onClose: () -> Unit) {
    val state by AudioEffects.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    EqualizerScreen(
        state = state,
        actions = EqualizerActions(
            onClose = onClose,
            onEnabledChange = AudioEffects::setEnabled,
            onBandChange = AudioEffects::setBandLevel,
            onPreset = AudioEffects::applyPreset,
            onBassChange = AudioEffects::setBass,
        ),
    )
}

@Composable
fun EqualizerScreen(state: EffectsState, actions: EqualizerActions, modifier: Modifier = Modifier) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(
        modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = actions.onClose, contentDescription = "Revenir à la lecture", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("Égaliseur", style = type.displayLarge, color = colors.ink)
        }
        Spacer(Modifier.height(14.dp))

        // L'écran indique pour quelle sortie on règle : les réglages changent quand on branche un casque.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.display)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Réglage pour la sortie", style = type.label, color = colors.displayMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.enabled) {
                        LiveDot()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(state.output.label, style = type.displayTitle, color = colors.displayInk)
                }
            }
            if (state.available) {
                HardwareKey(
                    onClick = { actions.onEnabledChange(!state.enabled) },
                    contentDescription = if (state.enabled) "Désactiver l'égaliseur" else "Activer l'égaliseur",
                    modifier = Modifier.size(width = 76.dp, height = 44.dp),
                    style = if (state.enabled) KeyStyle.Accent else KeyStyle.Standard,
                ) { Text(if (state.enabled) "Actif" else "Inactif", style = type.label.copy(fontSize = 12.sp)) }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (!state.available) {
            Text(
                if (state.bands.isEmpty() && !state.bassAvailable) {
                    "L'égaliseur sera disponible dès que la lecture aura démarré une fois. S'il n'apparaît toujours pas, cet appareil ne le permet pas."
                } else {
                    "Cet appareil ne propose pas d'égaliseur par bandes."
                },
                style = type.body,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val current = state.preset
                EqPreset.entries.forEach { preset ->
                    val selected = state.enabled && preset == current
                    HardwareKey(
                        onClick = { actions.onPreset(preset) },
                        contentDescription = "Réglage ${preset.label}",
                        modifier = Modifier.size(width = 84.dp, height = 40.dp).semantics { this.selected = selected },
                        style = if (selected) KeyStyle.Selected else KeyStyle.Standard,
                    ) { Text(preset.label, style = type.label.copy(fontSize = 12.sp), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                val span = (state.maxMb - state.minMb).coerceAtLeast(1)
                state.bands.forEachIndexed { index, band ->
                    Fader(
                        value = (band.levelMb - state.minMb).toFloat() / span,
                        onValueChange = { fraction ->
                            // Aimanté par pas de 0,5 dB pour retrouver facilement le zéro.
                            val mb = (state.minMb + fraction * span) / 50f
                            actions.onBandChange(index, mb.roundToInt() * 50)
                        },
                        label = formatFrequency(band.centerHz),
                        valueText = formatGain(band.levelMb),
                        accessibilityLabel = "Bande ${formatFrequency(band.centerHz)} hertz",
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.bassAvailable) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                val percent = (state.bass * 100).roundToInt()
                Knob(
                    value = state.bass,
                    onValueChange = actions.onBassChange,
                    label = if (percent == 0) "Grave" else "Grave $percent %",
                    valueText = "Renforcement des graves $percent pour cent",
                    accentMark = true,
                )
            }
        }
    }
}

private fun previewState() = EffectsState(
    available = true,
    output = OutputKind.Bluetooth,
    enabled = true,
    bands = listOf(EqBand(60, 500), EqBand(230, 300), EqBand(910, 0), EqBand(3_600, -150), EqBand(14_000, 200)),
    bassAvailable = true,
    bass = 0.35f,
)

private val PreviewActions = EqualizerActions({}, {}, { _, _ -> }, {}, {})

@Preview(name = "Égaliseur, Appareil", widthDp = 360, heightDp = 780)
@Composable
private fun EqualizerAppareilPreview() {
    LecteurTheme(darkTheme = false) { EqualizerScreen(previewState(), PreviewActions) }
}

@Preview(name = "Égaliseur, Nuit", widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EqualizerNuitPreview() {
    LecteurTheme(darkTheme = true) { EqualizerScreen(previewState(), PreviewActions) }
}
