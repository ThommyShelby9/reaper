package com.lecteur.player.history

/** Une écoute terminée : combien de temps le morceau a réellement été entendu. */
data class Listen(val mediaId: String, val startedAtMs: Long, val listenedMs: Long)

/**
 * Mesure le temps réellement écouté de chaque morceau (pauses et sauts exclus).
 * On lui signale les changements de lecture ; il renvoie l'écoute terminée quand le morceau change.
 * Les instants viennent de l'appelant ([nowMs]) pour pouvoir le tester sans horloge.
 */
class ListenTracker {

    private var mediaId: String? = null
    private var startedAtMs = 0L
    private var listenedMs = 0L
    private var playingSinceMs: Long? = null

    /** Le morceau [newMediaId] devient le morceau en cours ; renvoie l'écoute du précédent, s'il y en avait un. */
    fun onItemChanged(newMediaId: String?, isPlaying: Boolean, nowMs: Long, wallClockMs: Long): Listen? {
        val finished = finish(nowMs)
        mediaId = newMediaId
        startedAtMs = wallClockMs
        listenedMs = 0L
        playingSinceMs = if (isPlaying && newMediaId != null) nowMs else null
        return finished
    }

    fun onPlayingChanged(isPlaying: Boolean, nowMs: Long) {
        if (mediaId == null) return
        val since = playingSinceMs
        if (isPlaying && since == null) {
            playingSinceMs = nowMs
        } else if (!isPlaying && since != null) {
            listenedMs += (nowMs - since).coerceAtLeast(0L)
            playingSinceMs = null
        }
    }

    /** Termine l'écoute en cours (arrêt du service) et la renvoie. */
    fun stop(nowMs: Long): Listen? {
        val finished = finish(nowMs)
        mediaId = null
        playingSinceMs = null
        listenedMs = 0L
        return finished
    }

    private fun finish(nowMs: Long): Listen? {
        val id = mediaId ?: return null
        val total = listenedMs + (playingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)
        return Listen(id, startedAtMs, total)
    }
}

/**
 * Une écoute compte comme « jouée » si on a entendu au moins la moitié du morceau ou quatre minutes,
 * et jamais moins de 30 secondes (règle proche de celle de Last.fm).
 */
fun countsAsPlay(listenedMs: Long, durationMs: Long): Boolean {
    if (listenedMs < 30_000) return false
    if (durationMs <= 0) return true
    return listenedMs >= minOf(durationMs / 2, 240_000)
}
