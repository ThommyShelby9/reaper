package com.lecteur.player.ui.nowplaying

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.lecteur.player.audio.AudioEffects
import com.lecteur.player.analysis.TrackProfile
import com.lecteur.player.analysis.camelot
import com.lecteur.player.analysis.keyName
import com.lecteur.player.analysis.moodOf
import com.lecteur.player.data.Track
import com.lecteur.player.data.formatLabel
import com.lecteur.player.lyrics.Lyrics
import com.lecteur.player.ui.LyricsUiState
import com.lecteur.player.ui.components.LyricTicker
import com.lecteur.player.ui.search.MetadataSearchSheet
import com.lecteur.player.ui.search.SearchKind
import com.lecteur.player.playback.PlaybackUiState
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.PreviewData
import com.lecteur.player.ui.components.DataTile
import com.lecteur.player.ui.components.DotGrid
import com.lecteur.player.ui.components.CoverImage
import com.lecteur.player.ui.components.DotMatrixArt
import com.lecteur.player.ui.components.DotSpectrum
import com.lecteur.player.ui.components.animationsDisabled
import com.lecteur.player.ui.components.rememberSpectrum
import com.lecteur.player.ui.components.DotWaveform
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.Knob
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.components.dotGrid
import com.lecteur.player.ui.components.formatDuration
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Pochette de remplacement : une matrice de points éteints. */
internal val EmptyCover: DotGrid = dotGrid(22) { _, _ -> 0.12f }

/** Forme d'onde pendant le décodage : une ligne de points plate. */
internal val FlatWaveform = FloatArray(1)

@Stable
class NowPlayingActions(
    val onClose: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onOpenLyrics: () -> Unit,
    val onBassChange: (Float) -> Unit,
    val onCrossfadeChange: (Long) -> Unit,
    val onPlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onCycleRepeat: () -> Unit,
    val onSpeedChange: (Float) -> Unit,
    val onVolumeChange: (Float) -> Unit,
    val onToggleShuffle: () -> Unit = {},
    val onToggleLike: () -> Unit = {},
)

@Composable
fun NowPlayingRoute(
    viewModel: LecteurViewModel,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val track by viewModel.currentTrack.collectAsStateWithLifecycle()
    val cover by viewModel.currentCover.collectAsStateWithLifecycle()
    val waveform by viewModel.currentWaveform.collectAsStateWithLifecycle()
    val effects by AudioEffects.state.collectAsStateWithLifecycle()
    val playbackPrefs by PlaybackSettings.state.collectAsStateWithLifecycle()
    val player = viewModel.player
    val state = player.state

    BackHandler(onBack = onClose)
    LaunchedEffect(state.isPlaying) {
        player.refreshPosition()
        while (state.isPlaying) {
            delay(250)
            player.refreshPosition()
        }
    }

    val actions = remember(player, onClose, onOpenQueue, onOpenEqualizer, onOpenLyrics) {
        NowPlayingActions(
            onClose = onClose,
            onOpenQueue = onOpenQueue,
            onOpenEqualizer = onOpenEqualizer,
            onOpenLyrics = onOpenLyrics,
            onBassChange = AudioEffects::setBass,
            onCrossfadeChange = player::setCrossfadeMs,
            onPlayPause = player::playPause,
            onPrevious = player::previous,
            onNext = player::next,
            onSeek = { fraction -> player.seekTo(fraction, viewModel.currentTrack.value?.durationMs ?: 0L) },
            onCycleRepeat = player::cycleRepeat,
            onToggleShuffle = player::toggleShuffle,
            onToggleLike = { viewModel.currentTrack.value?.let { viewModel.toggleFavorite(it.id) } },
            onSpeedChange = player::setSpeed,
            onVolumeChange = player::setVolume,
        )
    }
    val profile by viewModel.currentProfile.collectAsStateWithLifecycle()
    val mix by viewModel.mixActive.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val lyricsState by viewModel.currentLyrics.collectAsStateWithLifecycle()
    var searchingCover by rememberSaveable { mutableStateOf(false) }
    NowPlayingScreen(
        track, cover, waveform, effects.bass.takeIf { effects.bassAvailable }, playbackPrefs.crossfadeMs, profile, mix, state, actions,
        liked = track?.id?.let { it in favorites } == true,
        lyrics = (lyricsState as? LyricsUiState.Found)?.result?.lyrics,
        onSearchCover = { searchingCover = true },
    )
    val searched = track
    if (searchingCover && searched != null) {
        MetadataSearchSheet(viewModel, searched, SearchKind.Cover, onDismiss = { searchingCover = false })
    }
}

@Composable
fun NowPlayingScreen(
    track: Track?,
    cover: ImageBitmap?,
    waveform: FloatArray?,
    /** Renforcement des graves 0..1, ou null si l'appareil ne le permet pas. */
    bass: Float?,
    /** Durée du fondu enchaîné, 0 = désactivé. */
    crossfadeMs: Long,
    /** Tempo, tonalité et humeur du morceau (null tant qu'il n'est pas analysé). */
    profile: TrackProfile?,
    /** Lecture en mode Mix. */
    mixActive: Boolean,
    state: PlaybackUiState,
    actions: NowPlayingActions,
    modifier: Modifier = Modifier,
    /** Le titre en cours est dans les Favoris. */
    liked: Boolean = false,
    /** Paroles du morceau, affichées une phrase à la fois si elles sont synchronisées. */
    lyrics: Lyrics? = null,
    /** Appui long sur la pochette : recherche manuelle d'une autre pochette. */
    onSearchCover: (() -> Unit)? = null,
) {
    val colors = LecteurTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Header(state, actions, mixActive, liked.takeIf { track != null })
        Spacer(Modifier.height(12.dp))
        if (track == null) {
            NothingPlaying(actions.onClose)
            return@Column
        }
        val durationMs = state.durationMs.takeIf { it > 0 } ?: track.durationMs
        val progress = if (durationMs > 0) (state.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        PlayerDisplay(track, cover, state.isPlaying, waveform ?: FlatWaveform, state.positionMs, durationMs, progress, actions.onSeek, lyrics, onSearchCover)
        Spacer(Modifier.height(12.dp))
        // Hauteur commune : une tuile en texte ne doit pas être plus basse que ses voisines en points.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val features = profile?.features
            if (features == null) {
                // Pas encore analysé : on montre ce que l'on sait du fichier.
                DataTile(formatLabel(track.mimeType), "Format", Modifier.weight(1f).fillMaxHeight())
                DataTile("…", "BPM à venir", Modifier.weight(1f).fillMaxHeight())
                DataTile(profile?.genre ?: "—", "Genre", Modifier.weight(1f).fillMaxHeight(), compact = true)
            } else {
                DataTile(features.bpm.roundToInt().toString(), "BPM", Modifier.weight(1f).fillMaxHeight())
                DataTile(camelot(features.key, features.minor).toString(), keyName(features.key, features.minor), Modifier.weight(1f).fillMaxHeight())
                DataTile(moodOf(features).label, profile.genre ?: "Humeur", Modifier.weight(1f).fillMaxHeight(), compact = true)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Knob(
                value = state.volume,
                onValueChange = actions.onVolumeChange,
                label = "Volume",
                valueText = "${(state.volume * 100).roundToInt()} %",
            )
            Knob(
                value = speedToKnob(state.speed),
                onValueChange = { actions.onSpeedChange(knobToSpeed(it)) },
                label = "Vitesse",
                valueText = formatSpeed(state.speed),
                accentMark = true,
            )
            val fadeSeconds = (crossfadeMs / 1000).toInt()
            Knob(
                value = crossfadeMs.toFloat() / PlaybackSettings.MAX_CROSSFADE_MS,
                onValueChange = { v ->
                    // Par secondes entières : 0 à 12 s.
                    val seconds = (v * PlaybackSettings.MAX_CROSSFADE_MS / 1000f).roundToInt()
                    actions.onCrossfadeChange(seconds * 1000L)
                },
                label = if (fadeSeconds == 0) "Sans fondu" else "Fondu $fadeSeconds s",
                valueText = if (fadeSeconds == 0) "Fondu enchaîné désactivé" else "Fondu enchaîné de $fadeSeconds secondes",
            )
            if (bass != null) {
                val percent = (bass * 100).roundToInt()
                Knob(
                    value = bass,
                    onValueChange = actions.onBassChange,
                    label = "Grave",
                    valueText = "Renforcement des graves $percent pour cent",
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TransportKeys(state, actions)
    }
}

@Composable
private fun Header(state: PlaybackUiState, actions: NowPlayingActions, mixActive: Boolean, liked: Boolean?) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HardwareKey(
            onClick = actions.onClose,
            contentDescription = "Revenir à la bibliothèque",
            modifier = Modifier.size(width = 48.dp, height = 40.dp),
        ) { Icon(LecteurIcons.ChevronDown, contentDescription = null) }
        Spacer(Modifier.width(12.dp))
        if (state.isPlaying) {
            LiveDot()
            Spacer(Modifier.width(6.dp))
        }
        if (state.queueSize > 0) {
            Text(
                (if (mixActive) "Mix, " else "") + "${state.queueIndex + 1} sur ${state.queueSize}",
                style = type.label,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        if (liked != null) {
            HardwareKey(
                onClick = actions.onToggleLike,
                contentDescription = if (liked) "Retirer des Favoris" else "Ajouter aux Favoris",
                modifier = Modifier.size(width = 48.dp, height = 40.dp),
            ) {
                Icon(
                    if (liked) LecteurIcons.HeartFilled else LecteurIcons.Heart,
                    contentDescription = null,
                    tint = if (liked) colors.accent else LocalContentColor.current,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        HardwareKey(
            onClick = actions.onOpenLyrics,
            contentDescription = "Voir les paroles",
            modifier = Modifier.size(width = 48.dp, height = 40.dp),
        ) { Icon(LecteurIcons.Lyrics, contentDescription = null) }
        Spacer(Modifier.width(8.dp))
        HardwareKey(
            onClick = actions.onOpenEqualizer,
            contentDescription = "Ouvrir l'égaliseur",
            modifier = Modifier.size(width = 48.dp, height = 40.dp),
        ) { Icon(LecteurIcons.Sliders, contentDescription = null) }
        Spacer(Modifier.width(8.dp))
        HardwareKey(
            onClick = actions.onOpenQueue,
            contentDescription = "Voir la file d'attente",
            modifier = Modifier.size(width = 48.dp, height = 40.dp),
        ) { Icon(LecteurIcons.Queue, contentDescription = null) }
    }
}

@Composable
private fun NothingPlaying(onClose: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Rien en lecture", style = type.displayLarge, color = colors.ink)
        Spacer(Modifier.height(8.dp))
        Text("Choisissez un titre dans la bibliothèque.", style = type.body, color = colors.inkMuted)
        Spacer(Modifier.height(20.dp))
        HardwareKey(onClick = onClose, contentDescription = "Ouvrir la bibliothèque", modifier = Modifier.width(200.dp)) {
            Text("Bibliothèque", style = type.bodyStrong)
        }
    }
}

/**
 * Pochette telle quelle ; un toucher la remplace par le spectre en direct, et inversement ; un appui long cherche une autre pochette.
 * Si les animations sont coupées dans les réglages d'Android, la pochette reste seule.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CoverOrSpectrum(
    track: Track,
    cover: ImageBitmap?,
    isPlaying: Boolean,
    modifier: Modifier,
    /** Appui long : chercher une autre pochette (null : pas de recherche, par exemple sur l'écran de verrouillage). */
    onLongPress: (() -> Unit)? = null,
) {
    val canAnimate = !animationsDisabled()
    var showSpectrum by rememberSaveable { mutableStateOf(false) }
    val spectrumVisible = canAnimate && showSpectrum
    val frame = rememberSpectrum(visible = spectrumVisible, isPlaying = isPlaying)
    val tappable = if (canAnimate || onLongPress != null) {
        Modifier.combinedClickable(
            onClickLabel = if (spectrumVisible) "Afficher la pochette" else "Afficher le spectre",
            onLongClickLabel = "Chercher une autre pochette".takeIf { onLongPress != null },
            onLongClick = onLongPress,
        ) {
            if (canAnimate) showSpectrum = !showSpectrum
        }
    } else {
        Modifier
    }
    if (spectrumVisible) {
        DotSpectrum(frame, modifier.then(tappable).semantics { contentDescription = "Spectre du son en direct" })
    } else {
        CoverImage(cover, "Pochette de ${track.album}", modifier.then(tappable))
    }
}

/** Les deux thèmes : grande pochette telle quelle, titre en matrice, forme d'onde avec tête orange. */
@Composable
private fun PlayerDisplay(
    track: Track,
    cover: ImageBitmap?,
    isPlaying: Boolean,
    waveform: FloatArray,
    positionMs: Long,
    durationMs: Long,
    progress: Float,
    onSeek: (Float) -> Unit,
    lyrics: Lyrics?,
    onCoverLongPress: (() -> Unit)?,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CoverOrSpectrum(track, cover, isPlaying, Modifier.fillMaxWidth(0.62f), onCoverLongPress)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(track.title, style = type.displayLarge, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("${track.artist}, ${track.album}", style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            // Paroles au rythme du morceau, une phrase à la fois.
            LyricTicker(lyrics, positionMs, style = type.displayTitle.copy(fontSize = 17.sp, lineHeight = 22.sp), color = colors.accent, height = 46.dp)
            Spacer(Modifier.height(12.dp))
            DotWaveform(
                amplitudes = waveform,
                progress = progress,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                playedColor = colors.ink,
                headColor = colors.accent,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(positionMs), style = type.displayTitle, color = colors.ink)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(durationMs), style = type.displayTitle, color = colors.ink)
            }
        }
    }
}

@Composable
internal fun TransportKeys(state: PlaybackUiState, actions: NowPlayingActions) {
    val repeatDescription = when (state.repeatMode) {
        Player.REPEAT_MODE_ALL -> "Répéter : toute la file"
        Player.REPEAT_MODE_ONE -> "Répéter : ce titre"
        else -> "Répéter : désactivé"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HardwareKey(
            onClick = actions.onToggleShuffle,
            contentDescription = if (state.shuffle) "Lecture aléatoire : activée" else "Lecture aléatoire : désactivée",
            modifier = Modifier.weight(1f),
            style = KeyStyle.Dark,
        ) {
            Icon(LecteurIcons.Shuffle, contentDescription = null, tint = if (state.shuffle) LecteurTheme.colors.accent else LocalContentColor.current)
        }
        HardwareKey(onClick = actions.onPrevious, contentDescription = "Précédent", modifier = Modifier.weight(1f)) {
            Icon(LecteurIcons.Previous, contentDescription = null)
        }
        HardwareKey(
            onClick = actions.onPlayPause,
            contentDescription = if (state.isPlaying) "Pause" else "Lecture",
            modifier = Modifier.weight(1f),
            style = KeyStyle.Accent,
        ) { Icon(if (state.isPlaying) LecteurIcons.Pause else LecteurIcons.Play, contentDescription = null) }
        HardwareKey(onClick = actions.onNext, contentDescription = "Suivant", modifier = Modifier.weight(1f)) {
            Icon(LecteurIcons.Next, contentDescription = null)
        }
        HardwareKey(
            onClick = actions.onCycleRepeat,
            contentDescription = repeatDescription,
            modifier = Modifier.weight(1f),
            style = KeyStyle.Dark,
        ) {
            val active = state.repeatMode != Player.REPEAT_MODE_OFF
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    LecteurIcons.Repeat,
                    contentDescription = null,
                    tint = if (active) LecteurTheme.colors.accent else LocalContentColor.current,
                )
                if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                    Text("1", style = LecteurTheme.type.label.copy(fontSize = 9.sp), color = LecteurTheme.colors.accent)
                }
            }
        }
    }
}

private val PreviewActions = NowPlayingActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

private fun previewState() = PlaybackUiState().apply {
    isPlaying = true
    positionMs = 128_000L
    durationMs = 337_000L
    queueIndex = 2
    queueSize = 6
}

@Preview(name = "Appareil (clair)", widthDp = 360, heightDp = 780)
@Composable
private fun NowPlayingAppareilPreview() {
    LecteurTheme(darkTheme = false) {
        NowPlayingScreen(PreviewData.current, null, PreviewData.waveform, 0.3f, 6_000L, null, true, previewState(), PreviewActions)
    }
}

@Preview(name = "Nuit (sombre)", widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NowPlayingNuitPreview() {
    LecteurTheme(darkTheme = true) {
        NowPlayingScreen(PreviewData.current, null, PreviewData.waveform, 0.3f, 6_000L, null, true, previewState(), PreviewActions)
    }
}
