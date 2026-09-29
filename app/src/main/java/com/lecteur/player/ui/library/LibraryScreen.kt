package com.lecteur.player.ui.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.ui.lockscreen.LockScreenLauncher
import com.lecteur.player.data.LibraryTab
import com.lecteur.player.data.Track
import com.lecteur.player.data.TrackGroup
import com.lecteur.player.data.countLabel
import com.lecteur.player.data.groupTracks
import com.lecteur.player.data.matchesQuery
import com.lecteur.player.data.sortedByTitle
import com.lecteur.player.history.TrackStats
import com.lecteur.player.jam.JamManager
import com.lecteur.player.history.smartPlaylistGroups
import com.lecteur.player.analysis.LibraryProgress
import com.lecteur.player.analysis.TrackProfile
import com.lecteur.player.analysis.genreGroups
import com.lecteur.player.analysis.moodGroups
import com.lecteur.player.history.SavedPlaylist
import com.lecteur.player.history.savedPlaylistGroups
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.PreviewData
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.components.formatDuration
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.delay

/** Ce que l'utilisateur peut faire depuis la bibliothèque. */
class LibraryActions(
    val onTabChange: (LibraryTab) -> Unit,
    val onOpenGroup: (String) -> Unit,
    val onCloseGroup: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSearchOpenChange: (Boolean) -> Unit,
    val onPlay: (queue: List<Track>, index: Int) -> Unit,
    val onPlayNext: (Track) -> Unit,
    val onEnqueue: (Track) -> Unit,
    val onPlayPause: () -> Unit,
    val onOpenPlayer: () -> Unit,
    val onOpenRecap: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenJam: () -> Unit,
    val onDeletePlaylist: (Long) -> Unit,
    val onMix: (List<Track>) -> Unit,
    val onShuffle: (List<Track>) -> Unit,
    val onToggleFavorite: (Long) -> Unit = {},
)

@Composable
fun LibraryRoute(
    viewModel: LecteurViewModel,
    onOpenPlayer: () -> Unit,
    onOpenRecap: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenJam: () -> Unit,
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val current by viewModel.currentTrack.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val saved by viewModel.savedPlaylists.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val discovery by viewModel.discoveryGroups.collectAsStateWithLifecycle()
    val progress by viewModel.analysisProgress.collectAsStateWithLifecycle()
    val jam by JamManager.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Titres) }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val playback by PlaybackSettings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Revérifié au retour dans l'app : les autorisations se donnent dans les réglages d'Android.
    var lockScreenReady by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        lockScreenReady = LockScreenLauncher.missingRequirements(context).isEmpty()
        onPauseOrDispose { }
    }
    val lockScreenHint = playback.lockScreenPlayer && !playback.lockScreenHintDismissed && !lockScreenReady

    BackHandler(enabled = groupKey != null || searchOpen) {
        if (groupKey != null) groupKey = null else { searchOpen = false; query = "" }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }

    val player = viewModel.player
    val actions = remember(player, onOpenPlayer, onOpenRecap, onOpenSettings, onOpenJam) {
        LibraryActions(
            onTabChange = { tab = it; groupKey = null },
            onOpenGroup = { groupKey = it },
            onCloseGroup = { groupKey = null },
            onQueryChange = { query = it; groupKey = null },
            onSearchOpenChange = { open -> searchOpen = open; if (!open) query = "" },
            onPlay = { queue, index -> viewModel.play(queue, index); onOpenPlayer() },
            onPlayNext = { player.playNext(it); notice = "Lu ensuite : ${it.title}" },
            onEnqueue = { player.enqueue(it); notice = "Ajouté à la file : ${it.title}" },
            onPlayPause = player::playPause,
            onOpenPlayer = onOpenPlayer,
            onOpenRecap = onOpenRecap,
            onOpenSettings = onOpenSettings,
            onOpenJam = onOpenJam,
            onDeletePlaylist = viewModel::deletePlaylist,
            onMix = { viewModel.playMix(it); onOpenPlayer() },
            onShuffle = { viewModel.playShuffled(it); onOpenPlayer() },
            onToggleFavorite = viewModel::toggleFavorite,
        )
    }
    LibraryScreen(
        tracks = tracks,
        tab = tab,
        groupKey = groupKey,
        query = query,
        searchOpen = searchOpen,
        current = current,
        stats = stats,
        saved = saved,
        profiles = profiles,
        discovery = discovery,
        progress = progress,
        inJam = jam.inJam,
        isPlaying = player.state.isPlaying,
        notice = notice,
        actions = actions,
        onDismissLockScreenHint = PlaybackSettings::dismissLockScreenHint.takeIf { lockScreenHint },
        favorites = favorites,
    )
}

@Composable
fun LibraryScreen(
    tracks: List<Track>?,
    tab: LibraryTab,
    groupKey: String?,
    query: String,
    searchOpen: Boolean,
    current: Track?,
    stats: Map<Long, TrackStats>,
    saved: List<SavedPlaylist>,
    profiles: Map<Long, TrackProfile>,
    discovery: List<TrackGroup>,
    progress: LibraryProgress?,
    inJam: Boolean,
    isPlaying: Boolean,
    notice: String?,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    /** Non nul : le lecteur d'écran de verrouillage attend des autorisations, on le signale. */
    onDismissLockScreenHint: (() -> Unit)? = null,
    /** Titres aimés, marqués d'un cœur plein. */
    favorites: Set<Long> = emptySet(),
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val filtered = remember(tracks, query) { tracks?.filter { matchesQuery(it, query) } }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (searchOpen) {
            SearchField(query, actions.onQueryChange, onClose = { actions.onSearchOpenChange(false) })
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // La police en points est large : le titre rétrécit si les touches manquent de place.
                    var titleSize by remember { mutableFloatStateOf(26f) }
                    Text(
                        "Bibliothèque",
                        style = type.displayLarge.copy(fontSize = titleSize.sp, lineHeight = titleSize.sp),
                        color = colors.ink,
                        maxLines = 1,
                        softWrap = false,
                        onTextLayout = { if (it.hasVisualOverflow && titleSize > 16f) titleSize -= 2f },
                    )
                    if (tracks != null) {
                        Text(
                            countLabel(tracks.size, "titre", "titres") + (progress?.let { ", analyse ${it.analyzed} / ${it.total}" } ?: ""),
                            style = type.label,
                            color = colors.inkMuted,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                // Orange pendant une jam : on voit d'un coup d'œil qu'on écoute à plusieurs.
                HardwareKey(
                    onClick = actions.onOpenJam,
                    contentDescription = if (inJam) "Jam en cours" else "Jam",
                    modifier = Modifier.size(width = 44.dp, height = 40.dp),
                    style = if (inJam) KeyStyle.Accent else KeyStyle.Standard,
                ) { Icon(LecteurIcons.Jam, contentDescription = null) }
                Spacer(Modifier.width(6.dp))
                HardwareKey(
                    onClick = actions.onOpenRecap,
                    contentDescription = "Voir le bilan d'écoute",
                    modifier = Modifier.size(width = 44.dp, height = 40.dp),
                ) { Icon(LecteurIcons.Chart, contentDescription = null) }
                Spacer(Modifier.width(6.dp))
                HardwareKey(
                    onClick = actions.onOpenSettings,
                    contentDescription = "Réglages",
                    modifier = Modifier.size(width = 44.dp, height = 40.dp),
                ) { Icon(LecteurIcons.Settings, contentDescription = null) }
                Spacer(Modifier.width(6.dp))
                HardwareKey(
                    onClick = { actions.onSearchOpenChange(true) },
                    contentDescription = "Rechercher",
                    modifier = Modifier.size(width = 44.dp, height = 40.dp),
                ) { Icon(LecteurIcons.Search, contentDescription = null) }
            }
        }
        if (onDismissLockScreenHint != null && !searchOpen) {
            Spacer(Modifier.height(12.dp))
            LockScreenHint(onSettings = actions.onOpenSettings, onDismiss = onDismissLockScreenHint)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LibraryTab.entries.forEach { entry ->
                val selected = entry == tab
                HardwareKey(
                    onClick = { actions.onTabChange(entry) },
                    contentDescription = entry.label,
                    modifier = Modifier.size(width = 78.dp, height = 40.dp).semantics { this.selected = selected },
                    style = if (selected) KeyStyle.Selected else KeyStyle.Standard,
                ) {
                    Text(entry.label, style = type.label.copy(fontSize = 11.sp), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            filtered == null -> Message("Recherche des fichiers audio…", null)
            tracks.isNullOrEmpty() -> Message(
                "Aucun fichier audio",
                "Copiez de la musique sur le téléphone, par exemple dans le dossier Music : elle apparaîtra ici automatiquement.",
            )
            filtered.isEmpty() -> Message("Aucun résultat pour « $query »", "Vérifiez l'orthographe ou cherchez un autre titre, artiste ou album.")
            tab == LibraryTab.Titres -> {
                val sorted = remember(filtered) { sortedByTitle(filtered) }
                if (sorted.size >= 2) {
                    HardwareKey(
                        onClick = { actions.onShuffle(sorted) },
                        contentDescription = "Tout lire en aléatoire",
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(LecteurIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (query.isBlank()) "Tout lire en aléatoire" else "Lire ces titres en aléatoire", style = type.label.copy(fontSize = 12.sp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                TrackList(sorted, current?.id, favorites, actions)
            }
            else -> {
                val groups = remember(filtered, tab, stats, saved, profiles, discovery) {
                    when (tab) {
                        LibraryTab.Listes -> {
                            val visible = filtered.map { it.id }.toSet()
                            // « Ajoutés récemment » en premier, puis les listes du jour, les autres listes automatiques et les listes enregistrées.
                            val smart = smartPlaylistGroups(filtered, stats, System.currentTimeMillis())
                            smart.take(1) +
                                discovery.map { g -> g.copy(tracks = g.tracks.filter { it.id in visible }) } +
                                smart.drop(1) + savedPlaylistGroups(saved, filtered)
                        }
                        LibraryTab.Genres -> genreGroups(filtered) { profiles[it.id]?.genre }
                        LibraryTab.Humeurs -> moodGroups(filtered) { profiles[it.id]?.mood }
                        else -> groupTracks(filtered, tab)
                    }
                }
                val group = groups.firstOrNull { it.key == groupKey }
                val delete = group?.key?.removePrefix("saved:")?.toLongOrNull()?.takeIf { group.key.startsWith("saved:") }?.let { id ->
                    { actions.onDeletePlaylist(id); actions.onCloseGroup() }
                }
                if (group != null && group.tracks.isEmpty()) {
                    GroupHeader(group, actions.onCloseGroup, delete)
                    Message(
                        "Cette liste est vide pour l'instant",
                        when {
                            delete != null -> "Ses titres ne sont plus dans votre bibliothèque."
                            group.key.startsWith("disc:") || group.key.startsWith("mood:") ->
                                "Elle se remplit une fois vos morceaux analysés (en arrière-plan) et quelques titres écoutés."
                            else -> "Elle se remplit toute seule à mesure que vous écoutez et ajoutez de la musique."
                        },
                    )
                } else if (group != null) {
                    GroupHeader(
                        group,
                        actions.onCloseGroup,
                        delete,
                        onMix = { actions.onMix(group.tracks) }.takeIf { group.tracks.size >= 2 },
                        onShuffle = { actions.onShuffle(group.tracks) }.takeIf { group.tracks.size >= 2 },
                    )
                    TrackList(group.tracks, current?.id, favorites, actions)
                } else {
                    GroupList(groups, actions.onOpenGroup)
                }
            }
        }

        if (notice != null) {
            Text(
                notice,
                style = type.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (current != null) {
            Spacer(Modifier.height(8.dp))
            NowPlayingStrip(current, isPlaying, actions.onPlayPause, actions.onOpenPlayer)
        }
    }
}

@Composable
private fun LockScreenHint(onSettings: () -> Unit, onDismiss: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.display)
            .padding(14.dp),
    ) {
        Text("Lecteur sur l'écran de verrouillage", style = type.bodyStrong, color = colors.accent)
        Spacer(Modifier.height(4.dp))
        Text(
            "Il manque une autorisation pour que le morceau en cours prenne tout l'écran de verrouillage.",
            style = type.body,
            color = colors.displayMuted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKey(
                onClick = onSettings,
                contentDescription = "Régler l'écran de verrouillage",
                modifier = Modifier.weight(1f).height(40.dp),
                style = KeyStyle.Accent,
            ) { Text("Régler", style = type.label.copy(fontSize = 12.sp)) }
            HardwareKey(
                onClick = onDismiss,
                contentDescription = "Masquer ce rappel",
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("Plus tard", style = type.label.copy(fontSize = 12.sp)) }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.tile)
                .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) Text("Titre, artiste ou album", style = type.body, color = colors.inkMuted)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = "Rechercher dans la bibliothèque" },
            )
        }
        Spacer(Modifier.width(8.dp))
        HardwareKey(onClick = onClose, contentDescription = "Fermer la recherche", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
            Icon(LecteurIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ColumnScope.Message(title: String, detail: String?) {
    val colors = LecteurTheme.colors
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 32.dp)) {
        Text(title, style = LecteurTheme.type.bodyStrong, color = colors.ink)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, style = LecteurTheme.type.body, color = colors.inkMuted)
        }
    }
}

@Composable
private fun ColumnScope.TrackList(tracks: List<Track>, currentId: Long?, favorites: Set<Long>, actions: LibraryActions) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                isCurrent = track.id == currentId,
                onClick = { actions.onPlay(tracks, index) },
                onPlayNext = { actions.onPlayNext(track) },
                onEnqueue = { actions.onEnqueue(track) },
                liked = track.id in favorites,
                onToggleLike = { actions.onToggleFavorite(track.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    liked: Boolean,
    onToggleLike: () -> Unit,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = "Lire",
                    onLongClickLabel = "Options de file d'attente",
                    onLongClick = { menuOpen = true },
                    onClick = onClick,
                ),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(16.dp)) { if (isCurrent) LiveDot() }
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(track.durationMs), style = type.displayValue.copy(fontSize = 14.sp), color = colors.ink)
                // Un toucher : le titre va dans Favoris (ou en sort).
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(
                            onClickLabel = if (liked) "Retirer des Favoris" else "Ajouter aux Favoris",
                            role = Role.Button,
                            onClick = onToggleLike,
                        )
                        .semantics { stateDescription = if (liked) "Dans les Favoris" else "Pas dans les Favoris" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (liked) LecteurIcons.HeartFilled else LecteurIcons.Heart,
                        contentDescription = "J'aime",
                        tint = if (liked) colors.accent else colors.inkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = colors.tile,
            shape = RoundedCornerShape(10.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Lire ensuite", style = type.bodyStrong, color = colors.ink) },
                onClick = { menuOpen = false; onPlayNext() },
            )
            DropdownMenuItem(
                text = { Text("Ajouter à la file", style = type.bodyStrong, color = colors.ink) },
                onClick = { menuOpen = false; onEnqueue() },
            )
        }
    }
}

@Composable
private fun ColumnScope.GroupList(groups: List<TrackGroup>, onOpen: (String) -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(groups, key = { it.key }) { group ->
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Ouvrir") { onOpen(group.key) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(group.subtitle, style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(group.tracks.size.toString(), style = type.displayValue.copy(fontSize = 14.sp), color = colors.ink)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: TrackGroup,
    onClose: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onMix: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    // Listes et genres indiquent déjà leur nombre de titres dans leur sous-titre.
    val countInSubtitle = listOf("smart:", "saved:", "disc:", "genre:").any { group.key.startsWith(it) } && group.key != "genre:?"
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HardwareKey(onClick = onClose, contentDescription = "Retour", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
            Icon(LecteurIcons.ChevronLeft, contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(group.title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (countInSubtitle) group.subtitle else "${group.subtitle}, ${countLabel(group.tracks.size, "titre", "titres")}",
                style = type.label,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onShuffle != null) {
            Spacer(Modifier.width(8.dp))
            HardwareKey(
                onClick = onShuffle,
                contentDescription = "Lire ${group.title} en aléatoire",
                modifier = Modifier.size(width = 48.dp, height = 40.dp),
            ) { Icon(LecteurIcons.Shuffle, contentDescription = null) }
        }
        if (onMix != null) {
            Spacer(Modifier.width(8.dp))
            HardwareKey(
                onClick = onMix,
                contentDescription = "Mixer ${group.title} : enchaînement automatique calé sur le tempo",
                modifier = Modifier.size(width = 72.dp, height = 40.dp),
                style = KeyStyle.Accent,
            ) { Text("Mixer", style = type.label.copy(fontSize = 12.sp)) }
        }
        if (onDelete != null) {
            Spacer(Modifier.width(8.dp))
            HardwareKey(onClick = onDelete, contentDescription = "Supprimer la liste ${group.title}", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingStrip(track: Track, isPlaying: Boolean, onPlayPause: () -> Unit, onOpen: () -> Unit) {
    val colors = LecteurTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.display)
            .border(1.dp, colors.line, shape)
            .clickable(onClickLabel = "Ouvrir la lecture", onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPlaying) {
            LiveDot()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            "${track.title}, ${track.artist}",
            style = LecteurTheme.type.displayTitle.copy(fontSize = 16.sp),
            color = colors.displayInk,
            maxLines = 1,
            modifier = Modifier.weight(1f).basicMarquee(),
        )
        Spacer(Modifier.width(10.dp))
        HardwareKey(
            onClick = onPlayPause,
            contentDescription = if (isPlaying) "Pause" else "Lecture",
            modifier = Modifier.size(width = 52.dp, height = 44.dp),
            style = KeyStyle.Accent,
        ) { Icon(if (isPlaying) LecteurIcons.Pause else LecteurIcons.Play, contentDescription = null) }
    }
}

private val PreviewActions = LibraryActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun LibraryPreview() {
    LibraryScreen(
        tracks = PreviewData.tracks,
        tab = LibraryTab.Titres,
        groupKey = null,
        query = "",
        searchOpen = false,
        current = PreviewData.current,
        stats = emptyMap(),
        saved = emptyList(),
        profiles = emptyMap(),
        discovery = emptyList(),
        progress = null,
        inJam = false,
        isPlaying = true,
        notice = null,
        actions = PreviewActions,
    )
}

@Preview(name = "Bibliothèque, Appareil", widthDp = 360, heightDp = 780)
@Composable
private fun LibraryAppareilPreview() {
    LecteurTheme(darkTheme = false) { LibraryPreview() }
}

@Preview(name = "Bibliothèque, Nuit", widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LibraryNuitPreview() {
    LecteurTheme(darkTheme = true) { LibraryPreview() }
}
