package com.lecteur.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lecteur.player.data.Track
import com.lecteur.player.data.contentUri

/** Ce que l'interface affiche de la lecture en cours, mis à jour par le MediaController. */
@Stable
class PlaybackUiState {
    var connected by mutableStateOf(false)
    var mediaId by mutableStateOf<String?>(null)
    /** Titre et artiste lus dans le lecteur : affichés avant que la bibliothèque ait retrouvé le morceau. */
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    /** Vrai quand la file est arrivée au bout. */
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    /** 0 tant que la durée n'est pas connue. */
    var durationMs by mutableLongStateOf(0L)
    /** Position dans la file (0 = premier) et taille de la file. */
    var queueIndex by mutableIntStateOf(0)
    var queueSize by mutableIntStateOf(0)
    /** Identifiants des éléments de la file, dans l'ordre. */
    var queueIds by mutableStateOf<List<String>>(emptyList())
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    /** Lecture aléatoire. */
    var shuffle by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var volume by mutableFloatStateOf(1f)
}

/**
 * Relie l'interface au [PlaybackService] par un MediaController.
 * Toutes les méthodes doivent être appelées depuis le thread principal.
 */
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext

    init {
        PlaybackSettings.init(appContext)
    }
    val state = PlaybackUiState()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) =
            sync(player, queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
    }

    fun connect() {
        if (future != null) return
        // Même processus que le service : on affiche tout de suite son état, sans attendre la connexion.
        PlaybackService.player?.let { sync(it, queueChanged = true) }
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val pending = MediaController.Builder(appContext, token).buildAsync()
        future = pending
        pending.addListener({
            val connected = runCatching { pending.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            sync(connected, queueChanged = true)
            state.connected = true
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        state.connected = false
    }

    /** La position n'émet pas d'événement : l'écran de lecture l'interroge régulièrement. */
    fun refreshPosition() {
        controller?.let { state.positionMs = it.currentPosition }
    }

    /** Remplace la file par [tracks] et démarre à [startIndex]. */
    /**
     * [mix] : enchaîner en mode Mix (transitions calées sur le tempo) ; toute autre lecture le coupe.
     * [shuffle] : active ou coupe la lecture aléatoire ; null la laisse telle quelle.
     */
    fun play(tracks: List<Track>, startIndex: Int, mix: Boolean = false, shuffle: Boolean? = null) {
        val c = controller ?: return
        MixDirector.setActive(mix)
        (if (mix) false else shuffle)?.let { c.shuffleModeEnabled = it }
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    /** Ajoute [track] juste après le titre en cours ; démarre la lecture si la file est vide. */
    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
    }

    /** Ajoute [track] en fin de file ; démarre la lecture si la file est vide. */
    fun enqueue(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track), 0)
        c.addMediaItem(track.toMediaItem())
    }

    fun playQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun playPause() {
        controller?.let { Util.handlePlayPauseButtonAction(it) }
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    /** [fraction] entre 0 et 1 ; [fallbackDurationMs] sert tant que le lecteur ne connaît pas la durée. */
    fun seekTo(fraction: Float, fallbackDurationMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: fallbackDurationMs
        val target = (fraction.coerceIn(0f, 1f) * duration).toLong()
        c.seekTo(target)
        state.positionMs = target
    }

    /** Position exacte du lecteur à l'instant (et mise à jour de l'état affiché). */
    fun currentPositionMs(): Long? = controller?.currentPosition?.also { state.positionMs = it }

    val currentMediaId: String? get() = controller?.currentMediaItem?.mediaId

    /** Lecture ou pause imposée de l'extérieur (jam). */
    fun setPlaying(playing: Boolean) {
        val c = controller ?: return
        if (playing && !c.playWhenReady) c.play() else if (!playing && c.playWhenReady) c.pause()
    }

    val playWhenReady: Boolean get() = controller?.playWhenReady ?: false

    fun seekToMs(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0L))
        state.positionMs = positionMs
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Désactivé → toute la file → un seul titre → désactivé. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        state.speed = speed
    }

    fun setCrossfadeMs(ms: Long) = PlaybackSettings.setCrossfadeMs(ms)

    fun setVolume(volume: Float) {
        // Le service applique ce volume au lecteur, combiné au fondu enchaîné.
        PlaybackSettings.setVolume(volume)
        state.volume = volume
    }

    private fun sync(player: Player, queueChanged: Boolean) {
        if (queueChanged) {
            state.queueIds = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        }
        state.mediaId = player.currentMediaItem?.mediaId
        state.title = player.currentMediaItem?.mediaMetadata?.title?.toString()
        state.artist = player.currentMediaItem?.mediaMetadata?.artist?.toString()
        state.isPlaying = player.isPlaying
        state.ended = player.playbackState == Player.STATE_ENDED
        state.positionMs = player.currentPosition
        state.durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        state.queueIndex = player.currentMediaItemIndex
        state.queueSize = player.mediaItemCount
        state.repeatMode = player.repeatMode
        state.shuffle = player.shuffleModeEnabled
        state.speed = player.playbackParameters.speed
        state.volume = PlaybackSettings.state.value.volume
    }
}

/** Album du morceau, dans les extras de ses métadonnées : le widget y retrouve la pochette téléchargée. */
const val EXTRA_ALBUM_ID = "albumId"

fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setExtras(android.os.Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
                .build(),
        )
        .build()
