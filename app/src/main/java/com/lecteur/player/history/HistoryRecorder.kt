package com.lecteur.player.history

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Écoute le lecteur du service et enregistre chaque morceau réellement écouté dans l'historique.
 * Tourne dans le service : l'historique se remplit même quand l'interface est fermée.
 */
class HistoryRecorder(context: Context, private val player: Player) : Player.Listener {

    private val plays = HistoryDatabase.get(context).plays()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = ListenTracker()
    /** Durée du morceau en cours, gardée pour juger l'écoute quand on passe au suivant. */
    private var durationMs = C.TIME_UNSET

    init {
        player.addListener(this)
        tracker.onItemChanged(player.currentMediaItem?.mediaId, player.isPlaying, now(), System.currentTimeMillis())
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        record(tracker.onItemChanged(mediaItem?.mediaId, player.isPlaying, now(), System.currentTimeMillis()))
        durationMs = C.TIME_UNSET
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        tracker.onPlayingChanged(isPlaying, now())
        rememberDuration()
    }

    override fun onPlaybackStateChanged(playbackState: Int) = rememberDuration()

    fun release() {
        player.removeListener(this)
        record(tracker.stop(now()))
    }

    private fun rememberDuration() {
        player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { durationMs = it }
    }

    private fun record(listen: Listen?) {
        if (listen == null) return
        val trackId = listen.mediaId.toLongOrNull() ?: return
        val duration = durationMs.takeIf { it != C.TIME_UNSET } ?: 0L
        if (!countsAsPlay(listen.listenedMs, duration)) return
        scope.launch {
            plays.insert(PlayEntity(trackId = trackId, playedAt = listen.startedAtMs, listenedMs = listen.listenedMs))
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
}
