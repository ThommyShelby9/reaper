package com.lecteur.player.ui

import com.lecteur.player.data.Track
import com.lecteur.player.ui.components.DotGrid
import com.lecteur.player.ui.components.dotGrid
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** Données fictives pour les aperçus Android Studio uniquement. */
internal object PreviewData {

    private fun track(id: Long, title: String, artist: String, album: String, albumId: Long, n: Int, ms: Long) = Track(
        id = id, title = title, artist = artist, album = album, albumId = albumId, durationMs = ms,
        trackNumber = n, discNumber = 1, year = 2024, mimeType = "audio/flac",
        folder = "Music/$artist/$album", dateAddedSec = 0,
    )

    val tracks: List<Track> = listOf(
        track(1, "Après la fête", "Vesper Club", "Après la fête", 6, 1, 301_000),
        track(2, "Côte Ouest", "Maison Soleil", "Côte Ouest", 5, 2, 375_000),
        track(3, "Minuit sur le quai", "Lune Basse", "Quai Nord", 1, 3, 337_000),
        track(4, "Néons", "Kaelo", "Néons", 4, 1, 228_000),
        track(5, "Pluie d'été", "Odette Moreau", "Pluie d'été", 3, 4, 209_000),
        track(6, "Radio Nacre", "Les Satellites", "Radio Nacre", 2, 1, 242_000),
    )

    val current: Track = tracks[2]

    val cover: DotGrid = dotGrid(22) { u, v ->
        val d = hypot(u - 0.68f, v - 0.32f)
        when {
            d < 0.17f -> 0.95f
            d < 0.24f -> 0.55f
            else -> 0.08f + 0.3f * (1f - v)
        }
    }

    val waveform: FloatArray = run {
        var seed = 5
        fun rnd(): Float {
            seed = (seed * 9301 + 49297) % 233280
            return seed / 233280f
        }
        FloatArray(160) { i -> (sin(PI * i / 160).toFloat() * 0.55f + 0.3f + rnd() * 0.3f).coerceAtMost(1f) }
    }
}
