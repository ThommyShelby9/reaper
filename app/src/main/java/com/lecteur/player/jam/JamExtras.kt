package com.lecteur.player.jam

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lecteur.player.data.Track
import com.lecteur.player.history.PlayEntity
import java.net.URI
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random

/*
 * Fonctions pures des extensions de la jam : bibliothèque commune, vote pour passer, file équitable,
 * DJ maison, latence de sortie, récap et liens d'invitation. Testées dans JamExtrasTest.
 */

// --- Bibliothèque commune -------------------------------------------------------------------------

/**
 * Empreinte d'un morceau (12 caractères hexadécimaux du SHA-1 de sa clé) : on partage ces empreintes
 * plutôt que les titres en clair. Ce n'est pas un vrai anonymat (un titre connu peut se retrouver
 * en calculant son empreinte), mais la liste lisible de la bibliothèque ne circule pas.
 */
fun libraryHash(key: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}

data class Availability(val have: Int, val total: Int) {
    val everyone: Boolean get() = total > 0 && have == total
}

/** Combien de participants présents ont ce morceau, d'après les empreintes publiées. */
fun availability(key: String, libraries: Map<String, Set<String>>, presentUids: Collection<String>): Availability {
    val hash = libraryHash(key)
    val known = presentUids.filter { it in libraries }
    return Availability(have = known.count { hash in libraries.getValue(it) }, total = known.size)
}

// --- Vote pour passer -----------------------------------------------------------------------------

/** Majorité stricte des participants présents (hôte compris). */
fun skipThreshold(present: Int): Int = present / 2 + 1

fun shouldSkip(votes: Int, present: Int): Boolean = present > 0 && votes >= skipThreshold(present)

// --- File équitable -------------------------------------------------------------------------------

/**
 * Ordre « chacun son tour » : on alterne entre les personnes qui ont proposé, en commençant par celles
 * dont aucun titre n'est passé récemment. Dans les propositions d'une même personne, les votes décident.
 * [recentContributors] : qui a proposé les derniers titres joués, du plus ancien au plus récent.
 */
fun fairQueue(items: List<JamQueueItem>, recentContributors: List<String>): List<JamQueueItem> {
    val byPerson = items.groupBy { it.addedBy }.mapValues { (_, list) -> sortedQueue(list) }
    val order = byPerson.keys.sortedWith(
        compareBy<String> { recentContributors.lastIndexOf(it) }
            .thenBy { person -> byPerson.getValue(person).minOf { it.addedAtMs } },
    )
    val result = mutableListOf<JamQueueItem>()
    var round = 0
    while (result.size < items.size) {
        order.forEach { person -> byPerson.getValue(person).getOrNull(round)?.let(result::add) }
        round++
    }
    return result
}

// --- DJ maison ------------------------------------------------------------------------------------

/**
 * Titres souvent écoutés « autour » de [trackId] : pour chaque écoute de ce titre, les autres titres
 * joués dans la même fenêtre de temps comptent un point.
 */
fun coPlays(plays: List<PlayEntity>, trackId: Long, windowMs: Long = 30 * 60_000L): Map<Long, Int> {
    val anchors = plays.filter { it.trackId == trackId }.map { it.playedAt }
    if (anchors.isEmpty()) return emptyMap()
    val counts = mutableMapOf<Long, Int>()
    for (play in plays) {
        if (play.trackId == trackId) continue
        if (anchors.any { abs(it - play.playedAt) <= windowMs }) counts[play.trackId] = (counts[play.trackId] ?: 0) + 1
    }
    return counts
}

/**
 * Choisit le titre suivant quand la file est vide : pas déjà joué pendant la jam, de préférence présent
 * chez tout le monde, du même artiste, ou souvent écouté avec le titre en cours. Un peu de hasard
 * parmi les meilleurs pour ne pas tourner en rond.
 */
fun pickDjTrack(
    library: List<Track>,
    current: Track?,
    everyoneHas: (Track) -> Boolean,
    coPlayCounts: Map<Long, Int>,
    playedKeys: Set<String>,
    random: Random = Random.Default,
): Track? {
    val candidates = library.filter { it.id != current?.id && trackKey(it.title, it.artist) !in playedKeys }
    if (candidates.isEmpty()) return null
    fun score(track: Track): Double =
        (if (everyoneHas(track)) 4.0 else 0.0) +
            (if (current != null && track.artist == current.artist) 2.0 else 0.0) +
            minOf(coPlayCounts[track.id] ?: 0, 5) * 1.0
    val ranked = candidates.map { it to score(it) }.sortedByDescending { it.second }
    val best = ranked.first().second
    val top = ranked.takeWhile { it.second >= best - 1.0 }.take(5)
    return top[random.nextInt(top.size)].first
}

// --- Latence de sortie ----------------------------------------------------------------------------

/**
 * Retard mesuré lors du calibrage : pour chaque toucher, l'écart avec le bip le plus proche (en ms),
 * puis la médiane. Il faut au moins 4 touchers près d'un bip ; sinon null.
 */
fun tapLatencyMs(tapTimesNs: List<Long>, clickTimesNs: List<Long>, periodNs: Long): Long? {
    if (clickTimesNs.isEmpty()) return null
    val offsets = tapTimesNs.mapNotNull { tap ->
        val nearest = clickTimesNs.minBy { abs(it - tap) }
        (tap - nearest).takeIf { abs(it) < periodNs / 2 }
    }.sorted()
    if (offsets.size < 4) return null
    val median = if (offsets.size % 2 == 1) {
        offsets[offsets.size / 2]
    } else {
        (offsets[offsets.size / 2 - 1] + offsets[offsets.size / 2]) / 2
    }
    return (median / 1_000_000).coerceAtLeast(0L)
}

/** Position réellement entendue chez l'hôte : ce que joue le lecteur, moins le retard de sa sortie. */
fun audiblePosition(playerPositionMs: Long, outputLatencyMs: Long): Long = (playerPositionMs - outputLatencyMs).coerceAtLeast(0L)

/** Où placer son propre lecteur pour entendre en même temps que l'hôte : en avance de son propre retard. */
fun followerTarget(expectedAudibleMs: Long, outputLatencyMs: Long): Long = expectedAudibleMs + outputLatencyMs

// --- Récap de fin de jam --------------------------------------------------------------------------

/** Un titre passé pendant la jam. */
data class PlayedEntry(
    val track: JamTrackRef,
    val addedByName: String?,
    val votes: Int,
    val playedAtMs: Long,
    /** uid de la personne qui l'a proposé (null : DJ maison). */
    val addedBy: String? = null,
)

data class JamRecap(
    val durationMs: Long,
    val played: List<PlayedEntry>,
    val participants: List<String>,
    val mostVoted: PlayedEntry?,
    /** Nombre de titres proposés par personne, du plus grand au plus petit. */
    val contributors: List<Pair<String, Int>>,
)

fun buildJamRecap(played: List<PlayedEntry>, participants: List<String>, startMs: Long, endMs: Long): JamRecap {
    val ordered = played.sortedBy { it.playedAtMs }
    return JamRecap(
        durationMs = (endMs - startMs).coerceAtLeast(0L),
        played = ordered,
        participants = participants.distinct(),
        mostVoted = ordered.filter { it.votes > 0 }.maxByOrNull { it.votes },
        contributors = ordered.mapNotNull { it.addedByName }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value },
    )
}

// --- Invitations ----------------------------------------------------------------------------------

const val JamLinkHost = "lecteur-c23sj.web.app"

fun jamLink(code: String): String = "https://$JamLinkHost/jam/$code"

/** Code contenu dans un lien d'invitation (https://…/jam/CODE ou reaper://jam/CODE), sinon null. */
fun parseJamLink(link: String): String? {
    val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
    val code = when {
        uri.scheme == "https" && uri.host == JamLinkHost && uri.path.orEmpty().startsWith("/jam/") -> uri.path.removePrefix("/jam/")
        uri.scheme == "reaper" && uri.host == "jam" -> uri.path.orEmpty().removePrefix("/")
        else -> return null
    }
    return normalizeJamCode(code).takeIf { it.length == 6 }
}

/** Matrice du QR code (vrai = module foncé), sans marge : l'interface ajoute la sienne. */
fun qrMatrix(text: String): Array<BooleanArray> {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
}
