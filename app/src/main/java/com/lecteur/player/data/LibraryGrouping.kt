package com.lecteur.player.data

import java.text.Collator
import java.util.Locale

enum class LibraryTab(val label: String) {
    Titres("Titres"),
    Albums("Albums"),
    Artistes("Artistes"),
    Genres("Genres"),
    Humeurs("Humeurs"),
    Dossiers("Dossiers"),
    Listes("Listes"),
}

/** Un album, un artiste ou un dossier, avec ses morceaux dans l'ordre d'écoute. */
data class TrackGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
)

/** Tri alphabétique français, insensible aux accents et à la casse (« Écoute » avec les E). */
private fun frenchCollator(): Collator =
    Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }

fun sortedByTitle(tracks: List<Track>): List<Track> {
    val collator = frenchCollator()
    return tracks.sortedWith { a, b -> collator.compare(a.title, b.title) }
}

private val albumOrder = compareBy<Track>({ it.discNumber }, { it.trackNumber })

fun groupTracks(tracks: List<Track>, tab: LibraryTab): List<TrackGroup> {
    val collator = frenchCollator()
    val groups = when (tab) {
        LibraryTab.Titres, LibraryTab.Listes, LibraryTab.Genres, LibraryTab.Humeurs -> return emptyList()
        LibraryTab.Albums -> tracks.groupBy { it.albumId }.map { (albumId, items) ->
            val ordered = items.sortedWith(albumOrder.thenComparator { a, b -> collator.compare(a.title, b.title) })
            val artists = items.map { it.artist }.distinct()
            TrackGroup(
                key = "album:$albumId",
                title = items.first().album,
                subtitle = if (artists.size == 1) artists.first() else "Artistes variés",
                tracks = ordered,
            )
        }
        LibraryTab.Artistes -> tracks.groupBy { it.artist }.map { (artist, items) ->
            val ordered = items.sortedWith { a, b ->
                val byAlbum = collator.compare(a.album, b.album)
                if (byAlbum != 0) byAlbum else albumOrder.compare(a, b)
            }
            val albums = items.map { it.albumId }.distinct().size
            TrackGroup(
                key = "artist:$artist",
                title = artist,
                subtitle = countLabel(albums, "album", "albums"),
                tracks = ordered,
            )
        }
        LibraryTab.Dossiers -> tracks.groupBy { it.folder }.map { (folder, items) ->
            TrackGroup(
                key = "folder:$folder",
                title = folder.substringAfterLast('/').ifEmpty { "Racine" },
                subtitle = folder.ifEmpty { "Stockage principal" },
                tracks = items.sortedWith { a, b -> collator.compare(a.title, b.title) },
            )
        }
    }
    return groups.sortedWith { a, b -> collator.compare(a.title, b.title) }
}

fun countLabel(count: Int, singular: String, plural: String): String =
    "$count ${if (count > 1) plural else singular}"
