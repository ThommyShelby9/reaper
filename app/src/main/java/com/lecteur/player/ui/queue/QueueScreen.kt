package com.lecteur.player.ui.queue

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.data.countLabel
import com.lecteur.player.playback.QueueItem
import com.lecteur.player.playback.departureTimes
import com.lecteur.player.playback.formatClock
import com.lecteur.player.playback.waitingTimes
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.PreviewData
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.components.formatDuration
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.delay

@Composable
fun QueueRoute(viewModel: LecteurViewModel, onClose: () -> Unit) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val player = viewModel.player
    val state = player.state
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        while (true) {
            player.refreshPosition()
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    QueueScreen(
        queue = queue,
        currentIndex = state.queueIndex,
        isPlaying = state.isPlaying,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        nowMs = now,
        onClose = onClose,
        onPlay = player::playQueueItem,
        onRemove = player::removeQueueItem,
    )
}

/**
 * La file affichée comme un tableau des départs : pendant la lecture, chaque titre à venir
 * porte son heure de début ; en pause, le temps restant avant qu'il commence.
 */
@Composable
fun QueueScreen(
    queue: List<QueueItem>,
    currentIndex: Int,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    nowMs: Long,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val current = queue.getOrNull(currentIndex)
    val upcoming = queue.drop(currentIndex + 1)
    val currentDuration = durationMs.takeIf { it > 0 } ?: current?.track?.durationMs ?: 0L
    val remaining = (currentDuration - positionMs).coerceAtLeast(0L)
    val durations = upcoming.map { it.track?.durationMs ?: 0L }
    val times = if (isPlaying) {
        departureTimes(nowMs, remaining, durations).map { formatClock(it) }
    } else {
        waitingTimes(remaining, durations).map { "+${formatDuration(it)}" }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = onClose, contentDescription = "Revenir à la lecture", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("À suivre", style = type.displayLarge, color = colors.ink)
            Spacer(Modifier.weight(1f))
            Text(countLabel(upcoming.size, "titre", "titres"), style = type.label, color = colors.inkMuted)
        }
        Spacer(Modifier.height(14.dp))

        if (current != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.display)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        LiveDot()
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isPlaying) "En cours" else "En pause", style = type.label, color = colors.displayMuted)
                    Spacer(Modifier.weight(1f))
                    Text("reste ${formatDuration(remaining)}", style = type.label, color = colors.displayMuted)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    current.track?.title ?: "Titre introuvable",
                    style = type.displayTitle,
                    color = colors.displayInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                current.track?.let { Text(it.artist, style = type.label, color = colors.displayMuted, maxLines = 1) }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (upcoming.isEmpty()) {
            Text("Rien après ce titre.", style = type.bodyStrong, color = colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                "Pour ajouter des titres, faites un appui long sur un titre de la bibliothèque, puis choisissez « Ajouter à la file ».",
                style = type.body,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(if (isPlaying) "Départ" else "Dans", style = type.label, color = colors.inkMuted, modifier = Modifier.width(64.dp))
            Text("Titre, artiste", style = type.label, color = colors.inkMuted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(upcoming, key = { _, item -> "${item.index}:${item.mediaId}" }) { i, item ->
                QueueRow(item, times[i], onPlay = { onPlay(item.index) }, onRemove = { onRemove(item.index) })
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, time: String, onPlay: () -> Unit, onRemove: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val title = item.track?.title ?: "Titre introuvable"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = "Lire maintenant", onClick = onPlay)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(time, style = type.displayValue.copy(fontSize = 16.sp), color = colors.ink, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.track?.let {
                        Text(it.artist, style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HardwareKey(
                onClick = onRemove,
                contentDescription = "Retirer $title de la file",
                modifier = Modifier.size(width = 48.dp, height = 40.dp),
            ) { Icon(LecteurIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun QueuePreview() {
    val queue = PreviewData.tracks.mapIndexed { i, t -> QueueItem(i, t.id.toString(), t) }
    QueueScreen(
        queue = queue, currentIndex = 2, isPlaying = true, positionMs = 128_000, durationMs = 337_000,
        nowMs = (22 * 3600 + 36 * 60) * 1000L, onClose = {}, onPlay = {}, onRemove = {},
    )
}

@Preview(name = "File, Appareil", widthDp = 360, heightDp = 780)
@Composable
private fun QueueAppareilPreview() {
    LecteurTheme(darkTheme = false) { QueuePreview() }
}

@Preview(name = "File, Nuit", widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QueueNuitPreview() {
    LecteurTheme(darkTheme = true) { QueuePreview() }
}
