package com.lecteur.player.history

import com.lecteur.player.data.Track
import com.lecteur.player.data.TrackGroup
import com.lecteur.player.data.countLabel

/** Statistiques d'écoute d'un morceau. */
data class TrackStats(val trackId: Long, val plays: Int, val lastPlayedMs: Long)

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Listes calculées automatiquement à partir de la bibliothèque et de l'historique d'écoute. */
enum class SmartPlaylist(val title: String, val rule: String) {
    RecentlyAdded("Ajoutés récemment", "Les 100 derniers morceaux arrivés sur le téléphone"),
    NeverPlayed("Jamais écoutés", "Aucune écoute complète"),
    MostPlayed("Les plus écoutés", "Vos 50 titres les plus joués"),
    RecentlyPlayed("Écoutés récemment", "Les 50 derniers titres joués"),
    Forgotten("À redécouvrir", "Joués au moins 3 fois, pas depuis 3 mois"),
}

fun smartPlaylistTracks(
    playlist: SmartPlaylist,
    tracks: List<Track>,
    stats: Map<Long, TrackStats>,
    nowMs: Long,
): List<Track> {
    fun plays(t: Track) = stats[t.id]?.plays ?: 0
    fun lastPlayed(t: Track) = stats[t.id]?.lastPlayedMs ?: 0L
    return when (playlist) {
        SmartPlaylist.RecentlyAdded -> tracks
            .sortedByDescending { it.dateAddedSec }
            .take(100)
        SmartPlaylist.NeverPlayed -> tracks
            .filter { plays(it) == 0 }
            .sortedByDescending { it.dateAddedSec }
        SmartPlaylist.MostPlayed -> tracks
            .filter { plays(it) > 0 }
            .sortedWith(compareByDescending<Track> { plays(it) }.thenByDescending { lastPlayed(it) })
            .take(50)
        SmartPlaylist.RecentlyPlayed -> tracks
            .filter { lastPlayed(it) > 0 }
            .sortedByDescending { lastPlayed(it) }
            .take(50)
        SmartPlaylist.Forgotten -> tracks
            .filter { plays(it) >= 3 && nowMs - lastPlayed(it) > 90 * DAY_MS }
            .sortedByDescending { plays(it) }
    }
}

/** Les listes intelligentes présentées comme des groupes de la bibliothèque, dans un ordre fixe. */
fun smartPlaylistGroups(tracks: List<Track>, stats: Map<Long, TrackStats>, nowMs: Long): List<TrackGroup> =
    SmartPlaylist.entries.map { playlist ->
        val content = smartPlaylistTracks(playlist, tracks, stats, nowMs)
        TrackGroup(
            key = "smart:${playlist.name}",
            title = playlist.title,
            subtitle = "${playlist.rule}, ${countLabel(content.size, "titre", "titres")}",
            tracks = content,
        )
    }

/** Une liste enregistrée, avec ses morceaux dans l'ordre. */
data class SavedPlaylist(val id: Long, val name: String, val createdAtMs: Long, val trackIds: List<Long>)

/**
 * Les listes enregistrées présentées comme des groupes de la bibliothèque (les morceaux disparus sont ignorés).
 * « Favoris » passe en tête, avec le dernier titre aimé en premier.
 */
fun savedPlaylistGroups(playlists: List<SavedPlaylist>, tracks: List<Track>): List<TrackGroup> {
    val byId = tracks.associateBy { it.id }
    val (favorites, others) = playlists.partition { it.name == FAVORITES_NAME }
    return (favorites.take(1) + others).map { playlist ->
        val favorite = playlist.name == FAVORITES_NAME
        val ids = if (favorite) playlist.trackIds.asReversed() else playlist.trackIds
        val content = ids.mapNotNull { byId[it] }
        TrackGroup(
            key = "saved:${playlist.id}",
            title = playlist.name,
            subtitle = (if (favorite) "Vos titres aimés" else "Liste enregistrée") + ", ${countLabel(content.size, "titre", "titres")}",
            tracks = content,
        )
    }
}

/** Titres aimés (ceux de la liste « Favoris »). */
fun favoriteIds(playlists: List<SavedPlaylist>): Set<Long> =
    playlists.firstOrNull { it.name == FAVORITES_NAME }?.trackIds?.toSet().orEmpty()
