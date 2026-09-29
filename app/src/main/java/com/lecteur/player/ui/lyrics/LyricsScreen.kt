package com.lecteur.player.ui.lyrics

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.data.Track
import com.lecteur.player.lyrics.FoundLyrics
import com.lecteur.player.lyrics.LyricsSource
import com.lecteur.player.lyrics.currentLineIndex
import com.lecteur.player.lyrics.parseLrc
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.LyricsUiState
import com.lecteur.player.ui.PreviewData
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.theme.LecteurTheme
import com.lecteur.player.ui.search.MetadataSearchSheet
import com.lecteur.player.ui.search.SearchKind
import kotlinx.coroutines.delay

@Composable
fun LyricsRoute(viewModel: LecteurViewModel, onClose: () -> Unit) {
    val track by viewModel.currentTrack.collectAsStateWithLifecycle()
    val lyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val folder by viewModel.lyricsFolder.collectAsStateWithLifecycle()
    val player = viewModel.player
    val state = player.state

    BackHandler(onBack = onClose)
    LaunchedEffect(state.isPlaying) {
        player.refreshPosition()
        while (state.isPlaying) {
            delay(200)
            player.refreshPosition()
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::setLyricsFolder)
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    LyricsScreen(
        track = track,
        lyrics = lyrics,
        positionMs = state.positionMs,
        folderLabel = folder?.let(viewModel::lyricsFolderLabel),
        onClose = onClose,
        onSeek = player::seekToMs,
        onPickFolder = { pickFolder.launch(folder) },
        durationMs = state.durationMs.takeIf { it > 0 } ?: (track?.durationMs ?: 0L),
        onSearch = { searching = true },
    )
    val current = track
    if (searching && current != null) {
        MetadataSearchSheet(viewModel, current, SearchKind.Lyrics, onDismiss = { searching = false })
    }
}

/**
 * Paroles dans l'écran noir : la ligne chantée s'allume en points orange, les autres restent en texte.
 * Toucher une ligne synchronisée y amène la lecture.
 */
@Composable
fun LyricsScreen(
    track: Track?,
    lyrics: LyricsUiState,
    positionMs: Long,
    folderLabel: String?,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 0L,
    onSearch: () -> Unit = {},
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
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
            Column(Modifier.weight(1f)) {
                Text("Paroles", style = type.displayLarge, color = colors.ink)
                if (track != null) {
                    Text("${track.title}, ${track.artist}", style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (track != null) {
                Spacer(Modifier.width(8.dp))
                // Paroles absentes ou fausses : recherche manuelle (paroles et pochette).
                HardwareKey(
                    onClick = onSearch,
                    contentDescription = "Chercher les paroles ou la pochette de ce titre",
                    modifier = Modifier.size(width = 96.dp, height = 40.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LecteurIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Chercher", style = type.label.copy(fontSize = 12.sp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.display),
        ) {
            when (lyrics) {
                LyricsUiState.Loading -> DisplayMessage("Recherche des paroles…", null)
                LyricsUiState.None -> DisplayMessage(
                    "Pas de paroles pour ce titre",
                    if (folderLabel == null) {
                        "Les paroles viennent du fichier audio lui-même, ou d'un fichier .lrc du même nom. Pour utiliser vos .lrc, choisissez le dossier qui les contient ci-dessous."
                    } else {
                        "Aucune parole intégrée au fichier, et aucun .lrc du même nom dans « $folderLabel »."
                    },
                )
                is LyricsUiState.Found -> LyricsLines(lyrics.result, positionMs, durationMs, onSeek)
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Dossier des fichiers .lrc", style = type.label, color = colors.inkMuted)
                Text(folderLabel ?: "Aucun dossier choisi", style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val label = if (folderLabel == null) "Choisir" else "Changer"
            HardwareKey(
                onClick = onPickFolder,
                contentDescription = "$label le dossier des fichiers .lrc",
                modifier = Modifier.size(width = 96.dp, height = 44.dp),
                style = if (folderLabel == null) KeyStyle.Accent else KeyStyle.Standard,
            ) { Text(label, style = type.label.copy(fontSize = 12.sp)) }
        }
    }
}

@Composable
private fun ColumnScope.DisplayMessage(title: String, detail: String?) {
    val colors = LecteurTheme.colors
    Column(Modifier.padding(16.dp)) {
        Text(title, style = LecteurTheme.type.displayTitle, color = colors.displayInk)
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(detail, style = LecteurTheme.type.body, color = colors.displayMuted)
        }
    }
}

@Composable
private fun LyricsLines(found: FoundLyrics, positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val lines = found.lyrics.lines
    val synced = found.lyrics.synced
    val current = if (synced) currentLineIndex(lines, positionMs) else -1
    val listState = rememberLazyListState()

    // Garde la ligne chantée vers le haut de l'écran, sauf pendant que l'utilisateur fait défiler.
    LaunchedEffect(current) {
        if (current >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    // Paroles sans horodatage : on suit quand même le morceau, en défilant au prorata du temps écoulé.
    val estimated = if (!synced && durationMs > 0 && lines.isNotEmpty()) {
        ((positionMs.toFloat() / durationMs) * lines.size).toInt().coerceIn(0, lines.size - 1)
    } else {
        -1
    }
    LaunchedEffect(estimated) {
        if (estimated >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((estimated - 3).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines) { index, line ->
                val isCurrent = index == current
                val text = line.text.ifEmpty { "…" }
                val rowModifier = if (synced) {
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Aller à cette ligne") { onSeek(line.timeMs) }
                } else {
                    Modifier.fillMaxWidth()
                }
                Text(
                    text,
                    style = if (isCurrent) type.displayTitle.copy(fontSize = 22.sp, lineHeight = 26.sp) else type.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = when {
                        isCurrent -> colors.accent
                        synced && index < current -> colors.displayMuted
                        else -> colors.displayInk
                    },
                    modifier = rowModifier
                        .padding(vertical = if (isCurrent) 8.dp else 5.dp)
                        .then(if (isCurrent) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
                )
            }
        }
        Text(
            if (synced) "Synchronisées, ${found.source.label}" else "Non synchronisées, ${found.source.label}",
            style = type.label,
            color = colors.displayMuted,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
        )
    }
}

private val PreviewLyrics = LyricsUiState.Found(
    FoundLyrics(
        parseLrc(
            """
            [00:10.00]On a laissé la ville
            [00:14.00]tourner sans nous ce soir
            [00:18.00]les néons font des vagues
            [00:22.00]sur le quai de la gare
            [00:26.00]et si le train s'arrête
            [00:30.00]on restera là
            """.trimIndent(),
        )!!,
        LyricsSource.File,
    ),
)

@Preview(name = "Paroles, Appareil", widthDp = 360, heightDp = 780)
@Composable
private fun LyricsAppareilPreview() {
    LecteurTheme(darkTheme = false) { LyricsScreen(PreviewData.current, PreviewLyrics, 19_000, "Music", {}, {}, {}) }
}

@Preview(name = "Paroles, Nuit", widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LyricsNuitPreview() {
    LecteurTheme(darkTheme = true) { LyricsScreen(PreviewData.current, LyricsUiState.None, 0, null, {}, {}, {}) }
}
