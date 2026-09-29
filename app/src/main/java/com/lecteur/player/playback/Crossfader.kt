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

    /** Titre pour lequel la traîne est préparée (C.INDEX_UNSET : aucune). */
    private var armedIndex = C.INDEX_UNSET
    /** Position où la traîne attend le lecteur principal. */
    private var armPositionMs = 0L
    private var startPending = false
    private var resyncs = 0

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
        if (playWhenReady) {
            schedule()
        } else if (fading) {
            finishFade()
        } else {
            disarm()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (ownTransition) {
            ownTransition = false
        } else if (fading) {
            // L'utilisateur a changé de titre pendant le fondu : on coupe la traîne.
            finishFade()
        } else {
            disarm()
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // L'utilisateur s'est déplacé dans le titre : la traîne préparée n'est plus au bon endroit.
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !fading && !ownTransition) disarm()
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
        val fadeComing = shouldStartCrossfade(fadeMs + ARM_AHEAD_MS, remaining, main.hasNextMediaItem(), main.repeatMode, playing, fading)
        if (fadeComing) arm()
        if (shouldStartCrossfade(fadeMs, remaining, main.hasNextMediaItem(), main.repeatMode, playing, fading)) {
            // La traîne doit déjà jouer la fin du titre en silence : sinon, un court blanc au passage.
            if (!tailInSync() && remaining > MIN_WAIT_FOR_TAIL_MS) return
            // Mode Mix : on attend le prochain temps fort (au plus un temps) pour lancer la transition dessus.
            val outgoing = MixDirector.featuresOf(currentId)
            if (plan != null && outgoing != null && remaining > 1_500) {
                val untilBeat = msUntilNextBeat(position, outgoing)
                if (untilBeat > TICK_MS + 10) return
                if (untilBeat > 5) {
                    // Le temps fort tombe avant le prochain tour : on le vise précisément.
                    handler.postDelayed({ if (!fading && main.playWhenReady) startFade(main.duration - main.currentPosition, plan) }, untilBeat)
                    return
                }
            }
            startFade(remaining, plan)
        }
    }

    /**
     * Prépare la traîne quelques secondes avant le fondu : même titre, un peu en avance sur le lecteur
     * principal, en pause et muette. Quand le lecteur principal atteint ce point, elle démarre en silence
     * et le suit ; au moment du fondu il n'y a plus qu'à monter son volume.
     */
    private fun arm() {
        val t = tail
        if (armedIndex == main.currentMediaItemIndex && t != null) {
            keepTailInSync(t)
            return
        }
        val item = main.currentMediaItem ?: return
        val player = t ?: ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .build()
            .also {
                it.audioSessionId = audioSessionId
                tail = it
            }
        armPositionMs = main.currentPosition + ARM_LEAD_MS
        resyncs = 0
        player.volume = 0f
        player.playWhenReady = false
        player.setMediaItem(item, armPositionMs)
        player.playbackParameters = main.playbackParameters
        player.prepare()
        armedIndex = main.currentMediaItemIndex
    }

    private fun keepTailInSync(t: ExoPlayer) {
        if (!t.playWhenReady) {
            if (t.playbackState != Player.STATE_READY) return
            val lead = armPositionMs - main.currentPosition
            when {
                // Le lecteur principal arrive au point de départ : on lance la traîne pile à ce moment-là.
                lead in 0..TICK_MS -> if (!startPending) {
                    startPending = true
                    handler.postDelayed({
                        startPending = false
                        if (tail === t && armedIndex == main.currentMediaItemIndex) t.play()
                    }, lead)
                }
                lead < 0 -> rearm(t)
            }
            return
        }
        t.playbackParameters = main.playbackParameters
        // Décalage audible entre les deux lecteurs : on recale (deux fois au plus, pour ne pas boucler).
        if (t.isPlaying && abs(t.currentPosition - main.currentPosition) > MAX_DRIFT_MS && resyncs < MAX_RESYNCS) rearm(t)
    }

    private fun rearm(t: ExoPlayer) {
        resyncs++
        armPositionMs = main.currentPosition + ARM_LEAD_MS
        t.playWhenReady = false
        t.seekTo(armPositionMs)
    }

    private fun tailInSync(): Boolean {
        val t = tail ?: return false
        return armedIndex == main.currentMediaItemIndex && t.isPlaying &&
            abs(t.currentPosition - main.currentPosition) <= MAX_DRIFT_MS * 2
    }

    /** Oublie la traîne préparée (pause, changement de titre, déplacement dans le titre). */
    private fun disarm() {
        if (armedIndex == C.INDEX_UNSET) return
        armedIndex = C.INDEX_UNSET
        startPending = false
        tail?.run {
            stop()
            clearMediaItems()
        }
    }

    private fun startFade(remainingMs: Long, plan: TransitionPlan? = null) {
        if (fading || !main.hasNextMediaItem()) return
        val t = tail
        if (t != null && tailInSync()) {
            // La traîne joue déjà la fin du titre, en silence et au même endroit : elle prend le relais sans blanc.
            t.volume = PlaybackSettings.state.value.volume
        } else {
            // Pas eu le temps de la préparer (titre très court, déplacement tardif) : démarrage direct.
            val item = main.currentMediaItem ?: return
            val player = t ?: ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
                .build()
                .also {
                    it.audioSessionId = audioSessionId
                    tail = it
                }
            player.setMediaItem(item, main.currentPosition)
            player.playbackParameters = main.playbackParameters
            player.volume = PlaybackSettings.state.value.volume
            player.prepare()
            player.play()
        }
        armedIndex = C.INDEX_UNSET

        fadeDurationMs = remainingMs
        fadeStartMs = SystemClock.elapsedRealtime()
        mainGain = 0f
        applyVolumes()
        ownTransition = true
        // Un seul déplacement : titre suivant (dans l'ordre aléatoire s'il est actif), sur son premier temps en mode Mix.
        main.seekTo(main.nextMediaItemIndex, plan?.incomingStartMs?.takeIf { it > 0 } ?: 0L)
        if (plan != null) {
            // Tempo calé sur celui du titre qui part.
            mixStartSpeed = plan.incomingSpeed
            main.setPlaybackSpeed(plan.incomingSpeed)
        }
    }

    private fun finishFade() {
        tail?.run {
            stop()
            clearMediaItems()
        }
        armedIndex = C.INDEX_UNSET
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
        /** La traîne se prépare ce temps-là avant le début du fondu. */
        const val ARM_AHEAD_MS = 3_000L
        /** Avance de la traîne sur le lecteur principal pendant qu'elle se prépare. */
        const val ARM_LEAD_MS = 700L
        /** Écart toléré entre les deux lecteurs sur le même titre. */
        const val MAX_DRIFT_MS = 40L
        const val MAX_RESYNCS = 2
        /** En deçà, on n'attend plus la traîne : mieux vaut un court blanc qu'un fondu manqué. */
        const val MIN_WAIT_FOR_TAIL_MS = 1_200L
    }
}
