package com.lecteur.player.playback

import android.content.Context
import androidx.media3.common.Player
import com.lecteur.player.data.SharedLibrary
import com.lecteur.player.lyrics.Lyrics
import com.lecteur.player.lyrics.LyricsRepository
import com.lecteur.player.lyrics.MetadataChanges
import com.lecteur.player.lyrics.currentLyricLine
import com.lecteur.player.widget.ReaperWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Dans le service : charge les paroles du morceau en cours et passe la ligne chantée au widget
 * d'écran d'accueil, une phrase à la fois. Ne tourne que si un widget est posé et que la lecture avance.
 */
class LyricsFollower(private val context: Context, private val player: Player, private val scope: CoroutineScope) {

    private val repository = LyricsRepository(context)
    private var lyricsFor: String? = null
    private var lyrics: Lyrics? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    init {
        // Paroles choisies à la main dans l'app : on recharge.
        scope.launch { MetadataChanges.version.drop(1).collect { lyricsFor = null; update() } }
    }

    /** À appeler à chaque changement de morceau ou d'état de lecture. */
    fun update() {
        val mediaId = player.currentMediaItem?.mediaId
        if (!ReaperWidget.isPlaced(context) || mediaId == null) {
            stop()
            return
        }
        if (mediaId != lyricsFor) {
            lyricsFor = mediaId
            lyrics = null
            show(null)
            loadJob?.cancel()
            loadJob = scope.launch {
                val track = mediaId.toLongOrNull()?.let { SharedLibrary.load(context, it) } ?: return@launch
                lyrics = runCatching { repository.lyricsFor(track) }.getOrNull()?.lyrics?.takeIf { it.synced }
                update()
            }
        }
        if (player.playWhenReady && lyrics != null) {
            if (tickJob?.isActive != true) {
                tickJob = scope.launch {
                    while (true) {
                        show(currentLyricLine(lyrics, player.currentPosition))
                        delay(TICK_MS)
                    }
                }
            }
        } else {
            tickJob?.cancel()
            show(currentLyricLine(lyrics, player.currentPosition))
        }
    }

    private fun show(line: String?) {
        if (line == ReaperWidget.lyricLine) return
        ReaperWidget.lyricLine = line
        ReaperWidget.refresh(context, player)
    }

    private fun stop() {
        loadJob?.cancel()
        tickJob?.cancel()
        lyricsFor = null
        lyrics = null
        ReaperWidget.lyricLine = null
    }

    private companion object {
        const val TICK_MS = 300L
    }
}
