package com.lecteur.player.jam

import com.lecteur.player.data.Track
import com.lecteur.player.data.normalizeForSearch
import kotlin.math.abs
import kotlin.random.Random

/*
 * Logique de la jam, sans Android ni Firebase : testée à part.
 * Chaque participant joue sa propre copie des morceaux ; on les reconnaît par titre et artiste.
 */

/** Un morceau tel qu'il circule dans la jam : de quoi le retrouver dans la bibliothèque de chacun. */
data class JamTrackRef(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

private val Brackets = Regex("""\s*[(\[][^)\]]*[)\]]""")
private val Featuring = Regex("""\b(feat|ft|featuring)\b.*""")
private val NonAlphanumeric = Regex("[^a-z0-9]+")

/**
 * Clé de reconnaissance « artiste|titre » : sans accents, majuscules, ponctuation,
 * mentions entre parenthèses (« Remastered », « feat. … ») ni invités.
 */
fun trackKey(title: String, artist: String): String {
    fun clean(text: String) = normalizeForSearch(text)
        .replace(Brackets, " ")
        .replace(Featuring, " ")
        .replace(NonAlphanumeric, " ")
        .trim()
    return "${clean(artist)}|${clean(title)}"
}

fun Track.toJamRef() = JamTrackRef(trackKey(title, artist), title, artist, album, durationMs)

/**
 * Retrouve [ref] dans une bibliothèque : même clé, et durée proche (une autre version d'un morceau,
 * live ou remix, n'a presque jamais la même durée). Renvoie null si on ne l'a pas.
 */
fun matchTrack(ref: JamTrackRef, library: List<Track>, toleranceMs: Long = 4_000): Track? {
    val best = library.filter { trackKey(it.title, it.artist) == ref.key }
        .minByOrNull { abs(it.durationMs - ref.durationMs) } ?: return null
    return best.takeIf { ref.durationMs <= 0 || abs(best.durationMs - ref.durationMs) <= toleranceMs }
}

/** Une mesure d'horloge : envoi et réception en heure locale, et l'heure donnée par le serveur entre les deux. */
data class ClockSample(val sentAtMs: Long, val receivedAtMs: Long, val serverMs: Long) {
    val roundTripMs: Long get() = receivedAtMs - sentAtMs
}

/**
 * Décalage « serveur − local » : on garde la mesure la plus rapide (la moins incertaine)
 * et on suppose que le serveur a répondu au milieu de l'aller-retour.
 */
fun estimateClockOffset(samples: List<ClockSample>): Long? =
    samples.minByOrNull { it.roundTripMs }?.let { it.serverMs - (it.sentAtMs + it.receivedAtMs) / 2 }

/** Ce que l'hôte publie : quel morceau, où il en était, à quel instant (heure serveur), et s'il joue. */
data class JamPlayback(
    val track: JamTrackRef?,
    val playing: Boolean,
    val positionMs: Long,
    val anchorServerMs: Long,
    val speed: Float = 1f,
)

/** Où devrait en être la lecture à l'heure serveur [serverNowMs]. */
fun expectedPositionMs(playback: JamPlayback, serverNowMs: Long): Long {
    val position = if (!playback.playing) {
        playback.positionMs
    } else {
        playback.positionMs + ((serverNowMs - playback.anchorServerMs) * playback.speed).toLong()
    }
    val duration = playback.track?.durationMs ?: 0L
    return if (duration > 0) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
}

/**
 * Correction à appliquer à un participant : un saut si l'écart est grand,
 * sinon une vitesse légèrement modifiée (±3 % au plus) pour rattraper en douceur, sans coupure.
 */
data class SyncDecision(val seekToMs: Long?, val speed: Float, val inSync: Boolean)

fun syncDecision(localMs: Long, expectedMs: Long, seekThresholdMs: Long = 1_500, toleranceMs: Long = 30): SyncDecision {
    val ahead = localMs - expectedMs
    return when {
        abs(ahead) > seekThresholdMs -> SyncDecision(seekToMs = expectedMs, speed = 1f, inSync = false)
        abs(ahead) <= toleranceMs -> SyncDecision(seekToMs = null, speed = 1f, inSync = true)
        // 100 ms d'avance → 2,5 % plus lent : l'écart se résorbe en quelques secondes.
        else -> SyncDecision(seekToMs = null, speed = (1f - ahead / 4_000f).coerceIn(0.97f, 1.03f), inSync = false)
    }
}

/**
 * L'hôte republie son état quand quelque chose change pour de vrai : autre morceau, lecture ou pause,
 * ou position qui s'écarte de la prévision (l'hôte a avancé ou reculé dans le morceau).
 */
fun shouldPublish(last: JamPlayback?, current: JamPlayback, serverNowMs: Long, toleranceMs: Long = 400): Boolean {
    if (last == null) return true
    if (last.track?.key != current.track?.key || last.playing != current.playing) return true
    return abs(expectedPositionMs(last, serverNowMs) - current.positionMs) > toleranceMs
}

private const val CodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Code de jam à 6 caractères, sans les caractères qu'on confond à l'oral ou à l'écrit (0/O, 1/I). */
fun generateJamCode(random: Random = Random.Default): String = String(CharArray(6) { CodeAlphabet[random.nextInt(CodeAlphabet.length)] })

/** Nettoie un code tapé par l'utilisateur : majuscules, sans espaces ni tirets. */
fun normalizeJamCode(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }.take(6)

/** Un titre proposé dans la file partagée. */
data class JamQueueItem(
    val id: String,
    val track: JamTrackRef,
    val addedBy: String,
    val addedByName: String,
    val addedAtMs: Long,
    val votes: List<String>,
    /** L'hôte a-t-il ce titre ? null tant qu'il ne l'a pas vérifié. */
    val hostHas: Boolean?,
)

/** Ordre de la file : le plus de votes d'abord, puis le plus ancien. */
fun sortedQueue(items: List<JamQueueItem>): List<JamQueueItem> =
    items.sortedWith(compareByDescending<JamQueueItem> { it.votes.size }.thenBy { it.addedAtMs })

/** Un participant est considéré présent s'il a donné signe de vie depuis moins de 90 secondes. */
fun isPresent(lastSeenMs: Long, serverNowMs: Long): Boolean = serverNowMs - lastSeenMs < 90_000
