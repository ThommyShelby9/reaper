package com.lecteur.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lecteur.player.ui.settings.SettingsRoute
import com.lecteur.player.ui.equalizer.EqualizerRoute
import com.lecteur.player.ui.jam.JamRoute
import com.lecteur.player.ui.jam.JamReactionsOverlay
import com.lecteur.player.jam.JamManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.share.ScreenshotSignal
import com.lecteur.player.ui.share.ShareSheet
import com.lecteur.player.ui.library.LibraryRoute
import com.lecteur.player.ui.lyrics.LyricsRoute
import com.lecteur.player.ui.nowplaying.NowPlayingRoute
import com.lecteur.player.ui.queue.QueueRoute
import com.lecteur.player.ui.recap.RecapRoute

/** Autorisation de lecture des fichiers audio selon la version d'Android. */
fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/** Les écrans, du plus « bas » au plus « haut » : la lecture s'ouvre par-dessus la bibliothèque, la file par-dessus la lecture. */
private enum class Screen { Library, Player, Queue, Equalizer, Lyrics, Recap, Settings, Jam }

/** Écrans ouverts depuis la lecture, qui glissent de côté. */
private val SideScreens = setOf(Screen.Queue, Screen.Equalizer, Screen.Lyrics, Screen.Recap, Screen.Settings, Screen.Jam)

/** Racine de l'app : demande d'accès, puis bibliothèque, lecture et file d'attente. */
@Composable
fun LecteurApp(viewModel: LecteurViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun isGranted() = ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }
    // L'utilisateur peut accorder l'accès depuis les réglages puis revenir.
    LifecycleResumeEffect(Unit) {
        granted = isGranted()
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) { viewModel.onPermissionChanged(granted) }

    if (!granted) {
        val blocked = asked && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission())
        PermissionScreen(
            blocked = blocked,
            onRequest = { launcher.launch(audioPermission()) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
        return
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Library) }
    // Une invitation (lien ou QR code) ouvre directement l'écran Jam, qui utilisera le code.
    val invite by JamManager.pendingInvite.collectAsStateWithLifecycle()
    LaunchedEffect(invite) { if (invite != null) screen = Screen.Jam }
    // Garde l'onglet, la recherche et la position de défilement quand on passe d'un écran à l'autre.
    val screens = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState == Screen.Player && initialState == Screen.Library
            when {
                targetState in SideScreens -> slideInHorizontally { it } togetherWith fadeOut()
                initialState in SideScreens -> fadeIn() togetherWith slideOutHorizontally { it }
                forward -> slideInVertically { it } togetherWith fadeOut()
                else -> fadeIn() togetherWith slideOutVertically { it }
            }
        },
        label = "navigation",
    ) { target ->
        screens.SaveableStateProvider(key = target.name) {
            when (target) {
                Screen.Library -> LibraryRoute(
                    viewModel,
                    onOpenPlayer = { screen = Screen.Player },
                    onOpenRecap = { screen = Screen.Recap },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenJam = { screen = Screen.Jam },
                )
                Screen.Player -> NowPlayingRoute(
                    viewModel,
                    onClose = { screen = Screen.Library },
                    onOpenQueue = { screen = Screen.Queue },
                    onOpenEqualizer = { screen = Screen.Equalizer },
                    onOpenLyrics = { screen = Screen.Lyrics },
                )
                Screen.Queue -> QueueRoute(viewModel, onClose = { screen = Screen.Player })
                Screen.Equalizer -> EqualizerRoute(onClose = { screen = Screen.Player })
                Screen.Lyrics -> LyricsRoute(viewModel, onClose = { screen = Screen.Player })
                Screen.Recap -> RecapRoute(viewModel, onClose = { screen = Screen.Library })
                Screen.Settings -> SettingsRoute(viewModel, onClose = { screen = Screen.Library })
                Screen.Jam -> JamRoute(viewModel, onClose = { screen = Screen.Library })
            }
        }
    }
    // Les réactions de la jam s'affichent par-dessus n'importe quel écran.
    JamReactionsOverlay()
    ShareAfterScreenshot(viewModel)
    }
}

/** Après une capture d'écran de Reaper, propose une carte à partager (si l'option est active et qu'un titre est chargé). */
@Composable
private fun ShareAfterScreenshot(viewModel: LecteurViewModel) {
    val prefs by PlaybackSettings.state.collectAsStateWithLifecycle()
    val track by viewModel.currentTrack.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ScreenshotSignal.events.collect {
            if (PlaybackSettings.state.value.shareOnScreenshot && viewModel.currentTrack.value != null) open = true
        }
    }
    val current = track
    if (!open || current == null || !prefs.shareOnScreenshot) return
    val cover by viewModel.currentCover.collectAsStateWithLifecycle()
    val lyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val state = viewModel.player.state
    ShareSheet(
        track = current,
        cover = cover,
        lyrics = (lyrics as? LyricsUiState.Found)?.result?.lyrics,
        positionMs = state.positionMs,
        durationMs = state.durationMs.takeIf { it > 0 } ?: current.durationMs,
        onDismiss = { open = false },
    )
}
