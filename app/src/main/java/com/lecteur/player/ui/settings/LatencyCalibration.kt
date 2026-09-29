package com.lecteur.player.ui.settings

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.player.audio.OutputKind
import com.lecteur.player.jam.tapLatencyMs
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Suite de bips joués par la sortie audio en cours. On retient l'instant où Android annonce jouer
 * chaque bip (horodatage de l'AudioTrack) : l'écart avec les touchers de l'utilisateur est le retard
 * que le système ne connaît pas, typiquement celui d'un casque Bluetooth.
 */
private class ClickTrack(val beats: Int = 16, val periodMs: Int = 600) {
    private val rate = 48_000
    private val periodFrames = rate * periodMs / 1000
    val periodNs = periodMs * 1_000_000L

    @Volatile private var track: AudioTrack? = null
    @Volatile private var timestamp: Pair<Long, Long>? = null
    private var startedNs = 0L

    /** Joue les bips (bloquant jusqu'à la fin) en relevant les horodatages de sortie. */
    suspend fun play() = withContext(Dispatchers.Default) {
        val pcm = ShortArray(periodFrames * beats + rate / 2)
        val clickFrames = rate * 25 / 1000
        for (b in 0 until beats) {
            val start = b * periodFrames
            for (i in 0 until clickFrames) {
                val envelope = exp(-i / (rate * 0.006))
                pcm[start + i] = (sin(2 * PI * 1_500 * i / rate) * envelope * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        startedNs = System.nanoTime()
        t.play()
        val poller = launch {
            val ts = AudioTimestamp()
            while (isActive) {
                if (t.getTimestamp(ts)) timestamp = ts.framePosition to ts.nanoTime
                delay(100)
            }
        }
        t.write(pcm, 0, pcm.size)
        delay(periodMs.toLong())
        poller.cancel()
        runCatching { t.stop() }
        t.release()
        track = null
    }

    /** Instant (horloge System.nanoTime) où chaque bip sort, d'après Android. */
    fun clickTimesNs(): List<Long> {
        val (frame, nanos) = timestamp ?: (0L to startedNs)
        return List(beats) { b -> nanos + (b.toLong() * periodFrames - frame) * 1_000_000_000L / rate }
    }

    fun stop() {
        runCatching { track?.stop() }
    }
}

/** Écran de calibrage du retard de la sortie audio [output]. */
@Composable
fun LatencyCalibration(output: OutputKind, onSave: (Long) -> Unit, onClose: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val scope = rememberCoroutineScope()
    val taps = remember { mutableStateListOf<Long>() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val clicks = remember { ClickTrack() }
    DisposableEffect(Unit) { onDispose { clicks.stop(); job?.cancel() } }

    fun start() {
        taps.clear()
        tapCount = 0
        result = null
        failed = false
        running = true
        job = scope.launch {
            clicks.play()
            running = false
            val measured = tapLatencyMs(taps.toList(), clicks.clickTimesNs(), clicks.periodNs)
            if (measured == null) failed = true else result = measured
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = onClose, contentDescription = "Revenir aux réglages", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("Calibrage", style = type.displayLarge, color = colors.ink)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Sortie réglée : ${output.label}. Mettez le son à un niveau confortable. Touchez le grand bouton exactement en même temps que chaque bip que vous entendez, sans anticiper : 16 bips, un peu moins de 10 secondes.",
            style = type.body,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        // Le toucher est pris à l'appui (pas au relâchement) pour ne pas ajouter de retard.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (running) colors.accent else colors.display)
                .semantics { contentDescription = "Zone à toucher en rythme avec les bips" }
                .pointerInput(running) {
                    detectTapGestures(onPress = {
                        if (running) {
                            taps += System.nanoTime()
                            tapCount++
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    running -> "Touchez au bip\n$tapCount / ${clicks.beats}"
                    result != null -> "Retard mesuré\n$result ms"
                    failed -> "Pas assez de touchers\nréguliers"
                    else -> "Prêt"
                },
                style = type.displayLarge.copy(fontSize = 30.sp, lineHeight = 36.sp),
                color = if (running) colors.onAccent else colors.displayInk,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(14.dp))
        if (result != null) {
            Text(
                "C'est le retard que votre ${output.label.lowercase()} ajoute en plus de ce qu'Android annonce. La jam en tiendra compte pour que tout le monde entende en même temps.",
                style = type.body,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKey(
                onClick = { if (!running) start() },
                contentDescription = if (result == null && !failed) "Commencer" else "Recommencer",
                modifier = Modifier.weight(1f),
                style = if (result == null) KeyStyle.Accent else KeyStyle.Standard,
            ) { Text(if (result == null && !failed) "Commencer" else "Recommencer", style = type.bodyStrong) }
            result?.let { value ->
                HardwareKey(onClick = { onSave(value) }, contentDescription = "Enregistrer $value millisecondes", modifier = Modifier.weight(1f), style = KeyStyle.Accent) {
                    Text("Enregistrer", style = type.bodyStrong)
                }
            }
        }
    }
}
