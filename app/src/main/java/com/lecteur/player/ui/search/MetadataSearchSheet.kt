package com.lecteur.player.ui.search

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.player.data.Track
import com.lecteur.player.online.CoverCandidate
import com.lecteur.player.online.LyricsCandidate
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.formatDuration
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Ce qu'on cherche à la main pour le morceau en cours. */
enum class SearchKind(val label: String) { Lyrics("Paroles"), Cover("Pochette") }

private sealed interface SearchState<out T> {
    data object Idle : SearchState<Nothing>
    data object Loading : SearchState<Nothing>
    data object Failed : SearchState<Nothing>
    data class Done<T>(val results: List<T>) : SearchState<T>
}

/**
 * Recherche manuelle des paroles (LRCLIB) ou de la pochette (MusicBrainz, Cover Art Archive) du morceau en cours,
 * quand le choix automatique est absent ou faux. Le choix est gardé et passe avant tout le reste.
 */
@Composable
fun MetadataSearchSheet(
    viewModel: LecteurViewModel,
    track: Track,
    initialKind: SearchKind,
    onDismiss: () -> Unit,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var title by rememberSaveable { mutableStateOf(track.title) }
    var artist by rememberSaveable { mutableStateOf(track.artist.takeUnless { it.startsWith("<") || it.isBlank() } ?: "") }
    var album by rememberSaveable { mutableStateOf(track.album.takeUnless { it.isBlank() || it.equals(track.folder, ignoreCase = true) } ?: track.title) }
    var lyricsState by remember { mutableStateOf<SearchState<LyricsCandidate>>(SearchState.Idle) }
    var coverState by remember { mutableStateOf<SearchState<CoverCandidate>>(SearchState.Idle) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun search() {
        notice = null
        scope.launch {
            when (kind) {
                SearchKind.Lyrics -> {
                    lyricsState = SearchState.Loading
                    lyricsState = viewModel.searchLyrics(title.trim(), artist.trim())?.let { SearchState.Done(it) } ?: SearchState.Failed
                }
                SearchKind.Cover -> {
                    coverState = SearchState.Loading
                    coverState = viewModel.searchCovers(artist.trim(), album.trim())?.let { SearchState.Done(it) } ?: SearchState.Failed
                }
            }
        }
    }

    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .statusBarsPadding()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.body)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Chercher pour ce titre", style = type.bodyStrong, color = colors.ink)
                    Text("${track.title}, ${track.artist}", style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HardwareKey(onClick = onDismiss, contentDescription = "Fermer la recherche", modifier = Modifier.size(width = 44.dp, height = 40.dp)) {
                    Icon(LecteurIcons.Close, contentDescription = null)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchKind.entries.forEach { entry ->
                    val selected = entry == kind
                    HardwareKey(
                        onClick = { kind = entry; notice = null },
                        contentDescription = entry.label,
                        modifier = Modifier.weight(1f).height(42.dp).semantics { this.selected = selected },
                        style = if (selected) KeyStyle.Selected else KeyStyle.Standard,
                    ) { Text(entry.label, style = type.label.copy(fontSize = 12.sp)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (kind) {
                SearchKind.Lyrics -> {
                    Field("Titre", title, { title = it }, ::search)
                    Spacer(Modifier.height(8.dp))
                    Field("Artiste", artist, { artist = it }, ::search)
                }
                SearchKind.Cover -> {
                    Field("Artiste", artist, { artist = it }, ::search)
                    Spacer(Modifier.height(8.dp))
                    Field("Album (ou titre)", album, { album = it }, ::search)
                }
            }
            Spacer(Modifier.height(10.dp))
            HardwareKey(onClick = ::search, contentDescription = "Lancer la recherche", modifier = Modifier.fillMaxWidth().height(46.dp), style = KeyStyle.Accent) {
                Text("Chercher", style = type.bodyStrong)
            }
            notice?.let {
                Text(it, style = type.body, color = colors.ink, modifier = Modifier.padding(4.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.height(10.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (kind) {
                    SearchKind.Lyrics -> Results(lyricsState) { results ->
                        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(results) { candidate ->
                                LyricsRow(candidate, track) {
                                    scope.launch {
                                        viewModel.chooseLyrics(candidate)
                                        notice = "Paroles enregistrées pour ce titre."
                                        onDismiss()
                                    }
                                }
                            }
                        }
                    }
                    SearchKind.Cover -> Results(coverState) { results ->
                        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(results) { candidate ->
                                CoverTile(candidate) {
                                    notice = "Enregistrement de la pochette…"
                                    scope.launch {
                                        if (viewModel.chooseCover(candidate)) onDismiss() else notice = "La pochette n'a pas pu être téléchargée. Réessayez."
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Results(state: SearchState<T>, content: @Composable (List<T>) -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    when (state) {
        SearchState.Idle -> Text("Corrigez le titre ou l'artiste si besoin, puis lancez la recherche.", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(4.dp))
        SearchState.Loading -> Text("Recherche…", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(4.dp))
        SearchState.Failed -> Text("Recherche impossible : vérifiez la connexion internet, puis réessayez.", style = type.body, color = colors.ink, modifier = Modifier.padding(4.dp))
        is SearchState.Done -> if (state.results.isEmpty()) {
            Text("Aucun résultat. Essayez une orthographe plus simple, sans « feat. » ni parenthèses.", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(4.dp))
        } else {
            content(state.results)
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column {
        Text(label, style = type.label, color = colors.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = type.bodyStrong.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.tile)
                .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun LyricsRow(candidate: LyricsCandidate, track: Track, onChoose: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val sameLength = track.durationMs > 0 && abs(candidate.durationSec - track.durationMs / 1000) <= 3
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Choisir ces paroles", onClick = onChoose)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${candidate.title}, ${candidate.artist}",
                style = type.bodyStrong,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(formatDuration(candidate.durationSec * 1000L), style = type.label, color = if (sameLength) colors.accent else colors.inkMuted)
        }
        Text(
            listOfNotNull(
                if (candidate.synced) "Synchronisées" else "Texte simple",
                "même durée que votre fichier".takeIf { sameLength },
                candidate.album,
            ).joinToString(", "),
            style = type.label,
            color = if (candidate.synced) colors.accent else colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val preview = candidate.text.lineSequence()
            .map { it.replace(Regex("""\[[^\]]*\]"""), "").trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(" / ")
        if (preview.isNotEmpty()) {
            Text(preview, style = type.body, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun CoverTile(candidate: CoverCandidate, onChoose: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val image = remember(candidate) { runCatching { BitmapFactory.decodeByteArray(candidate.thumbnail, 0, candidate.thumbnail.size)?.asImageBitmap() }.getOrNull() }
    Column(Modifier.clickable(onClickLabel = "Choisir cette pochette", onClick = onChoose)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(colors.display)) {
            if (image != null) {
                Image(image, contentDescription = "Pochette de ${candidate.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(candidate.title, style = type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(
            listOfNotNull(candidate.artist.takeIf { it.isNotBlank() }, candidate.year).joinToString(", "),
            style = type.label.copy(fontSize = 10.sp),
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
