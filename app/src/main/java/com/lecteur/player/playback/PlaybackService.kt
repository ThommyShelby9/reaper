package com.lecteur.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import androidx.media3.common.Player
import kotlinx.coroutines.cancel
import com.lecteur.player.widget.ReaperWidget
import androidx.core.content.ContextCompat
import com.lecteur.player.ui.lockscreen.LockScreenLauncher
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.lecteur.player.audio.AudioSpectrum
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lecteur.player.MainActivity
import com.lecteur.player.audio.AudioEffects
import com.lecteur.player.data.trackUri
import com.lecteur.player.history.HistoryRecorder

/**
 * Service de lecture en arrière-plan : il possède l'ExoPlayer et la MediaSession.
 * La notification, l'écran de verrouillage et les casques Bluetooth passent par la session ;
 * l'interface s'y connecte avec un MediaController ([PlayerConnection]).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var history: HistoryRecorder? = null
    private var crossfader: Crossfader? = null
    /** Tâches du service (paroles du widget) ; annulées à sa destruction. */
    private val scope = kotlinx.coroutines.MainScope()
    private var lyricsFollower: LyricsFollower? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        PlaybackSettings.init(this)
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this, spectrumRenderers())
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Session audio fixée dès le départ pour y attacher l'égaliseur avant la première lecture.
        val audioSessionId = getSystemService(AudioManager::class.java).generateAudioSessionId()
        player.audioSessionId = audioSessionId
        AudioEffects.attach(this, audioSessionId)
        history = HistoryRecorder(this, player)
        crossfader = Crossfader(this, player, audioSessionId, attributes)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback)
            .setSessionActivity(openApp)
            .build()
        Companion.player = player
        player.addListener(widgetListener)
        lyricsFollower = LyricsFollower(this, player, scope)
        ReaperWidget.refresh(this, player)
    }

    /** Le widget d'écran d'accueil suit le morceau et l'état de lecture. */
    private val widgetListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                ReaperWidget.refresh(this@PlaybackService, player)
                lyricsFollower?.update()
            }
        }
    }

    /** Thème du téléphone changé : le widget en mode automatique suit. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ReaperWidget.refresh(this, mediaSession?.player)
    }

    /** Sortie audio standard, avec une dérivation du son vers le visualiseur. */
    @OptIn(UnstableApi::class)
    private fun spectrumRenderers() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioSpectrum)))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /**
     * Écran éteint pendant la lecture : on place le lecteur plein écran par-dessus le verrouillage,
     * pour qu'il soit là au prochain réveil du téléphone. Android n'autorise ce lancement en arrière-plan
     * qu'avec l'autorisation adaptée à la version (voir [LockScreenLauncher]).
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = mediaSession?.player ?: return
            val wanted = PlaybackSettings.state.value.lockScreenPlayer
            android.util.Log.d("ReaperVerrou", "Écran éteint : option $wanted, lecture ${player.playWhenReady}, file ${player.mediaItemCount}")
            if (!wanted || !player.playWhenReady || player.mediaItemCount == 0) return
            LockScreenLauncher.launch(context)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        lyricsFollower = null
        ReaperWidget.lyricLine = null
        Companion.player = null
        ReaperWidget.refresh(this, null)
        crossfader?.release()
        crossfader = null
        history?.release()
        history = null
        AudioEffects.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** Lecteur du service tant qu'il tourne (même processus) : le widget le pilote directement. */
        var player: Player? = null
            private set
    }

    /**
     * Les éléments envoyés par un contrôleur externe (Android Auto, montre…) n'ont que leur identifiant :
     * on retrouve l'adresse du fichier dans MediaStore à partir de celui-ci.
     */
    private object SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                when {
                    item.localConfiguration != null -> item
                    else -> item.mediaId.toLongOrNull()?.let { id -> item.buildUpon().setUri(trackUri(id)).build() }
                }
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }
}
