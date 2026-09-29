package com.lecteur.player.ui.lockscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.playback.formatClock
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.components.DotWaveform
import com.lecteur.player.ui.components.LyricTicker
import com.lecteur.player.ui.LyricsUiState
import androidx.compose.ui.text.style.TextAlign
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.components.formatDuration
import com.lecteur.player.ui.nowplaying.CoverOrSpectrum
import com.lecteur.player.ui.nowplaying.FlatWaveform
import com.lecteur.player.ui.nowplaying.NowPlayingActions
import com.lecteur.player.ui.nowplaying.TransportKeys
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/**
 * Lecteur plein écran affiché par-dessus l'écran de verrouillage :
 * grande horloge en points, morceau en cours, commandes, et « glisser vers le haut » pour déverrouiller.
 */
@Composable
fun LockScreen(viewModel: LecteurViewModel, onUnlock: () -> Unit) {
    val track by viewModel.currentTrack.collectAsStateWithLifecycle()
    val cover by viewModel.currentCover.collectAsStateWithLifecycle()
    val waveform by viewModel.currentWaveform.collectAsStateWithLifecycle()
    val lyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val player = viewModel.player
    val state = player.state
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            player.refreshPosition()
            now = System.currentTimeMillis()
            delay(if (state.isPlaying) 250 else 1_000)
        }
    }
    val actions = remember(player) {
        NowPlayingActions(
            onClose = {}, onOpenQueue = {}, onOpenEqualizer = {}, onOpenLyrics = {}, onBassChange = {}, onCrossfadeChange = {},
            onPlayPause = player::playPause,
            onPrevious = player::previous,
            onNext = player::next,
            onSeek = { player.seekTo(it, viewModel.currentTrack.value?.durationMs ?: 0L) },
            onCycleRepeat = player::cycleRepeat,
            onToggleShuffle = player::toggleShuffle,
            onSpeedChange = {},
            onVolumeChange = {},
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(formatClock(now), style = type.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp), color = colors.ink)
        Text(
            DateFormat.format(LocalDate.now()).replaceFirstChar { it.titlecase(Locale.FRENCH) },
            style = type.body,
            color = colors.inkMuted,
        )
        Spacer(Modifier.weight(1f))

        val current = track
        if (current == null) {
            // Le lecteur connaît déjà le morceau pendant que la bibliothèque le retrouve : on l'affiche tout de suite.
            val title = state.title
            if (title != null) {
                Text(title, style = type.displayLarge.copy(fontSize = 24.sp), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.artist?.let { Text(it, style = type.body, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else if (state.connected) {
                Text("Rien en lecture", style = type.displayTitle, color = colors.inkMuted)
            }
        } else {
            CoverOrSpectrum(current, cover, state.isPlaying, Modifier.fillMaxWidth(0.66f))
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isPlaying) {
                    LiveDot()
                    Spacer(Modifier.width(8.dp))
                }
                Text(current.title, style = type.displayLarge.copy(fontSize = 24.sp), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(current.artist, style = type.body, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            LyricTicker(
                (lyrics as? LyricsUiState.Found)?.result?.lyrics,
                state.positionMs,
                style = type.displayTitle.copy(fontSize = 18.sp, lineHeight = 23.sp),
                color = colors.accent,
                height = 48.dp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            val duration = state.durationMs.takeIf { it > 0 } ?: current.durationMs
            DotWaveform(
                amplitudes = waveform ?: FlatWaveform,
                progress = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onSeek = actions.onSeek,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                playedColor = colors.ink,
                headColor = colors.accent,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(state.positionMs), style = type.label, color = colors.inkMuted)
                Text(formatDuration(duration), style = type.label, color = colors.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
            TransportKeys(state, actions)
        }

        Spacer(Modifier.weight(1f))
        UnlockHandle(onUnlock)
    }
}

/** Zone du bas : glisser vers le haut déverrouille ; les lecteurs d'écran ont une action « Déverrouiller ». */
@Composable
private fun UnlockHandle(onUnlock: () -> Unit) {
    val colors = LecteurTheme.colors
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { onClick(label = "Déverrouiller") { onUnlock(); true } }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (-dragged > threshold) onUnlock() },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(LecteurIcons.ChevronDown, contentDescription = null, tint = colors.accent, modifier = Modifier.rotate(180f))
        Text("Glisser vers le haut pour déverrouiller", style = LecteurTheme.type.label, color = colors.inkMuted)
    }
}
