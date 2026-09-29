package com.lecteur.player.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lecteur.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Compléments trouvés en ligne, uniquement dans des bases libres et sans compte :
 * - pochettes : MusicBrainz (identifiant de l'album) puis Cover Art Archive ;
 * - genres : tags MusicBrainz de l'enregistrement ;
 * - paroles : LRCLIB (souvent synchronisées).
 * Ces services reçoivent l'artiste, le titre et l'album cherchés. Tout est gardé sur le téléphone ensuite.
 */
class OnlineMetadata(context: Context) {

    private val appContext = context.applicationContext
    private val coversDir = File(appContext.filesDir, "covers")
    private val lyricsDir = File(appContext.filesDir, "lyrics")

    fun coverFile(albumId: Long) = File(coversDir, "album_$albumId.jpg")
    fun lyricsFile(trackId: Long) = File(lyricsDir, "$trackId.lrc")
    /** Choix faits à la main dans la recherche : ils passent avant le fichier audio et les téléchargements. */
    fun chosenLyricsFile(trackId: Long) = File(lyricsDir, "choix_$trackId.lrc")
    fun chosenCoverFile(albumId: Long) = File(coversDir, "choix_album_$albumId.jpg")

    /** Pochette de l'album de [track] ; true si une image a été enregistrée. */
    suspend fun fetchCover(track: Track): Boolean = withContext(Dispatchers.IO) {
        if (coverFile(track.albumId).exists()) return@withContext true
        val release = musicBrainz("release", releaseQuery(track.artist, track.album)) ?: return@withContext false
        val mbid = release.optJSONArray("releases")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: return@withContext false
        val bytes = get("https://coverartarchive.org/release/$mbid/front-500") ?: return@withContext false
        coversDir.mkdirs()
        coverFile(track.albumId).writeBytes(bytes)
        true
    }

    /** Tags de genre de l'enregistrement, du plus cité au moins cité. */
    suspend fun fetchTags(track: Track): List<String> = withContext(Dispatchers.IO) {
        val result = musicBrainz("recording", recordingQuery(track.artist, track.title)) ?: return@withContext emptyList()
        val tags = result.optJSONArray("recordings")?.optJSONObject(0)?.optJSONArray("tags") ?: return@withContext emptyList()
        (0 until tags.length()).mapNotNull { tags.optJSONObject(it) }
            .sortedByDescending { it.optInt("count") }
            .map { it.optString("name") }
            .filter { it.isNotBlank() }
    }

    /** Paroles LRCLIB ; true si un fichier a été enregistré (synchronisé de préférence). */
    suspend fun fetchLyrics(track: Track): Boolean = withContext(Dispatchers.IO) {
        if (lyricsFile(track.id).exists()) return@withContext true
        val url = "https://lrclib.net/api/get?" + listOf(
            "artist_name" to track.artist,
            "track_name" to track.title,
            "album_name" to track.album,
            "duration" to (track.durationMs / 1000).toString(),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val body = get(url)?.toString(Charsets.UTF_8) ?: return@withContext false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext false
        val text = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: return@withContext false
        lyricsDir.mkdirs()
        lyricsFile(track.id).writeText(text)
        true
    }

    private suspend fun musicBrainz(entity: String, query: String, limit: Int = 1): JSONObject? {
        val url = "https://musicbrainz.org/ws/2/$entity/?fmt=json&limit=$limit&query=" + URLEncoder.encode(query, "UTF-8")
        // MusicBrainz demande au plus une requête par seconde.
        val body = musicBrainzLock.withLock {
            val wait = lastMusicBrainzCall + 1_100 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastMusicBrainzCall = System.currentTimeMillis()
            get(url)
        } ?: return null
        return runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
    }

    /** Recherche libre sur LRCLIB (titre, artiste) : jusqu'à 20 versions, les synchronisées d'abord. */
    suspend fun searchLyrics(title: String, artist: String): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val params = listOf("track_name" to title, "artist_name" to artist).filter { it.second.isNotBlank() }
        if (params.isEmpty()) return@withContext emptyList()
        val url = "https://lrclib.net/api/search?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val body = get(url)?.toString(Charsets.UTF_8) ?: return@withContext emptyList()
        val array = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            val text = synced ?: plain ?: return@mapNotNull null
            LyricsCandidate(
                title = o.optString("trackName"),
                artist = o.optString("artistName"),
                album = o.optString("albumName").takeIf { it.isNotBlank() && it != "null" },
                durationSec = o.optDouble("duration", 0.0).toInt(),
                synced = synced != null,
                text = text,
            )
        }.sortedByDescending { it.synced }.take(20)
    }

    /**
     * Recherche libre de pochettes (artiste, album ou titre) : groupes de sorties MusicBrainz,
     * image de chacun sur Cover Art Archive. Seules les images trouvées sont renvoyées.
     */
    suspend fun searchCovers(artist: String, album: String): List<CoverCandidate> = withContext(Dispatchers.IO) {
        val query = listOfNotNull(
            artist.takeIf { it.isNotBlank() }?.let { "artist:\"${luceneEscape(it)}\"" },
            album.takeIf { it.isNotBlank() }?.let { "releasegroup:\"${luceneEscape(it)}\"" },
        ).joinToString(" AND ")
        if (query.isBlank()) return@withContext emptyList()
        val result = musicBrainz("release-group", query, limit = 12) ?: return@withContext emptyList()
        val groups = result.optJSONArray("release-groups") ?: return@withContext emptyList()
        (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.mapNotNull { g ->
            val id = g.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val thumbnail = get("https://coverartarchive.org/release-group/$id/front-250") ?: return@mapNotNull null
            val credit = g.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name").orEmpty()
            CoverCandidate(
                title = g.optString("title"),
                artist = credit,
                year = g.optString("first-release-date").take(4).takeIf { it.length == 4 },
                thumbnail = thumbnail,
                fullUrl = "https://coverartarchive.org/release-group/$id/front-500",
            )
        }
    }

    /** Télécharge la pochette choisie et la garde pour tout l'album ; true si c'est fait. */
    suspend fun chooseCover(albumId: Long, candidate: CoverCandidate): Boolean = withContext(Dispatchers.IO) {
        val bytes = get(candidate.fullUrl) ?: candidate.thumbnail
        coversDir.mkdirs()
        chosenCoverFile(albumId).writeBytes(bytes)
        true
    }

    private fun luceneEscape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun get(url: String): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json, image/*")
        try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        /** MusicBrainz et LRCLIB demandent qu'une app s'identifie, avec un moyen de contact. */
        private const val USER_AGENT = "Reaper/0.1 ( https://lecteur-c23sj.web.app )"
        private val musicBrainzLock = Mutex()
        @Volatile private var lastMusicBrainzCall = 0L

        /** Vrai si le téléphone est connecté ; [unmeteredOnly] : seulement en Wi-Fi (réseau non facturé). */
        fun isOnline(context: Context, unmeteredOnly: Boolean = false): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return connected && (!unmeteredOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }
    }
}

/** Échappe les caractères spéciaux de la syntaxe de recherche de MusicBrainz (Lucene). */
fun luceneEscape(text: String): String = text.replace(Regex("""([+\-&|!(){}\[\]^"~*?:\\/])"""), """\\$1""")

fun releaseQuery(artist: String, album: String): String = "release:\"${luceneEscape(album)}\" AND artist:\"${luceneEscape(artist)}\""

fun recordingQuery(artist: String, title: String): String = "recording:\"${luceneEscape(title)}\" AND artist:\"${luceneEscape(artist)}\""

/** Une version de paroles trouvée sur LRCLIB. [text] : LRC si [synced], texte simple sinon. */
data class LyricsCandidate(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int,
    val synced: Boolean,
    val text: String,
)

/** Une pochette trouvée : vignette déjà téléchargée, image complète à [fullUrl]. */
class CoverCandidate(
    val title: String,
    val artist: String,
    val year: String?,
    val thumbnail: ByteArray,
    val fullUrl: String,
)
