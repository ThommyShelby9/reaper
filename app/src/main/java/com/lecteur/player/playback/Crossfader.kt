package com.lecteur.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lecteur.player.analysis.TransitionPlan
import com.lecteur.player.analysis.msUntilNextBeat
import com.lecteur.player.history.HistoryDatabase
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fondu enchaîné entre deux titres.
 *
 * Quelques secondes avant la fin d'un titre, un second lecteur (« la traîne ») reprend la fin de ce titre
 * au même endroit et baisse doucement, pendant que le lecteur principal passe au titre suivant et monte.
 * Le lecteur principal reste la seule source de vérité pour la file, la notification et l'interface.
 * La traîne partage la session audio : l'égaliseur s'applique aux deux.
 *
 * Le volume du lecteur principal porte le fondu ; le volume choisi par l'utilisateur vient de [PlaybackSettings].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val audioSessionId: Int,
    private val audioAttributes: AudioAttributes,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tail: ExoPlayer? = null
    private var fadeStartMs = -1L
    private var fadeDurationMs = 0L
    private var mainGain = 1f
    /** Vrai entre notre propre passage au titre suivant et l'événement qui le confirme. */
    private var ownTransition = false

    /** Vitesse de départ du titre entrant en mode Mix (1 hors mix). */
    private var mixStartSpeed = 1f

    private val fading get() = fadeStartMs >= 0

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (main.playWhenReady || fading) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        main.addListener(this)
        scope.launch { PlaybackSettings.state.collect { applyVolumes() } }
        // Le mode Mix a besoin du tempo et de la tonalité des morceaux déjà analysés.
        scope.launch {
            HistoryDatabase.get(context).features().featuresFlow().collect { list ->
                MixDirector.features = list.mapNotNull { e -> e.toFeatures()?.let { e.trackId to it } }.toMap()
            }
        }
        if (main.playWhenReady) schedule()
    }

    fun release() {
        handler.removeCallbacks(tick)
        scope.cancel()
        main.removeListener(this)
        tail?.release()
        tail = null
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) schedule() else if (fading) finishFade()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (ownTransition) {
            ownTransition = false
        } else if (fading) {
            // L'utilisateur a changé de titre pendant le fondu : on coupe la traîne.
            finishFade()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun step() {
        val settings = PlaybackSettings.state.value
        if (fading) {
            val elapsed = SystemClock.elapsedRealtime() - fadeStartMs
            val gains = fadeGains(elapsed, fadeDurationMs)
            tail?.volume = settings.volume * gains.outgoing
            mainGain = gains.incoming
            applyVolumes()
            // Mode Mix : le titre qui arrive part au tempo du précédent, puis revient doucement à sa vitesse.
            if (mixStartSpeed != 1f) {
                val progress = (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
                val speed = mixStartSpeed + (1f - mixStartSpeed) * progress
                if (abs(speed - main.playbackParameters.speed) > 0.002f) main.setPlaybackSpeed(speed)
            }
            if (elapsed >= fadeDurationMs) finishFade()
            return
        }
        val duration = main.duration
        if (duration == C.TIME_UNSET) return
        val position = main.currentPosition
        val remaining = duration - position
        val playing = main.playWhenReady && main.playbackState == Player.STATE_READY
        val mix = MixDirector.active.value
        val currentId = main.currentMediaItem?.mediaId?.toLongOrNull()
        val plan = if (mix && main.hasNextMediaItem()) {
            MixDirector.plan(currentId, main.getMediaItemAt(main.nextMediaItemIndex).mediaId.toLongOrNull())
        } else {
            null
        }
        val fadeMs = plan?.fadeMs ?: settings.crossfadeMs
        if (shouldStartCrossfade(fadeMs, remaining, main.hasNextMediaItem(), main.repeatMode, playing, fading)) {
            // Mode Mix : on attend le prochain temps fort (au plus un temps) pour lancer la transition dessus.
            val outgoing = MixDirector.featuresOf(currentId)
            if (plan != null && outgoing != null && remaining > 1_500 && msUntilNextBeat(position, outgoing) > TICK_MS + 10) return
            startFade(remaining, plan)
        }
    }

    private fun startFade(remainingMs: Long, plan: TransitionPlan? = null) {
        val item = main.currentMediaItem ?: return
        val position = main.currentPosition
        val t = tail ?: ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .build()
            .also {
                it.audioSessionId = audioSessionId
                tail = it
            }
        t.setMediaItem(item, position)
        t.playbackParameters = main.playbackParameters
        t.volume = PlaybackSettings.state.value.volume
        t.prepare()
        t.play()

        fadeDurationMs = remainingMs
        fadeStartMs = SystemClock.elapsedRealtime()
        mainGain = 0f
        applyVolumes()
        ownTransition = true
        main.seekToNextMediaItem()
        if (plan != null) {
            // Entrée sur le premier temps du titre suivant, tempo calé sur celui qui part.
            if (plan.incomingStartMs > 0) main.seekTo(plan.incomingStartMs)
            mixStartSpeed = plan.incomingSpeed
            main.setPlaybackSpeed(plan.incomingSpeed)
        }
    }

    private fun finishFade() {
        tail?.run {
            stop()
            clearMediaItems()
        }
        fadeStartMs = -1L
        mainGain = 1f
        ownTransition = false
        if (mixStartSpeed != 1f) {
            main.setPlaybackSpeed(1f)
            mixStartSpeed = 1f
        }
        applyVolumes()
    }

    private fun applyVolumes() {
        main.volume = PlaybackSettings.state.value.volume * mainGain
    }

    private companion object {
        const val TICK_MS = 50L
    }
}
