package com.lecteur.player.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.lecteur.player.data.Track
import com.lecteur.player.data.contentUri
import com.lecteur.player.online.OnlineMetadata
import com.lecteur.player.playback.PlaybackSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LyricsSource(val label: String) {
    File("fichier .lrc"),
    Embedded("intégrées au fichier"),
    Downloaded("téléchargées sur LRCLIB"),
    Chosen("choisies sur LRCLIB"),
}

data class FoundLyrics(val lyrics: Lyrics, val source: LyricsSource)

/**
 * Cherche les paroles d'un morceau : fichier .lrc dans le dossier choisi par l'utilisateur,
 * puis paroles intégrées au fichier audio. Des paroles synchronisées passent toujours avant du texte simple.
 */
class LyricsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("paroles", Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    /** Dossier où chercher les .lrc, choisi une fois par l'utilisateur ; null s'il n'y en a pas. */
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    private val indexLock = Mutex()
    private var index: Map<String, List<LrcFile>>? = null

    private data class LrcFile(val uri: Uri, val folder: String)

    fun folderLabel(uri: Uri): String =
        runCatching { treeLabel(DocumentsContract.getTreeDocumentId(uri)) }.getOrDefault("Dossier choisi")

    /** À appeler avec l'adresse renvoyée par le sélecteur de dossier ; garde l'autorisation d'accès. */
    fun setFolder(uri: Uri) {
        val resolver = appContext.contentResolver
        _folder.value?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        index = null
        _folder.value = uri
    }

    suspend fun lyricsFor(track: Track): FoundLyrics? = withContext(Dispatchers.IO) {
        // Paroles choisies à la main dans la recherche : elles passent avant tout le reste.
        chosen(track)?.let { return@withContext FoundLyrics(it, LyricsSource.Chosen) }
        val candidates = listOfNotNull(
            runCatching { fromLrcFile(track) }.getOrNull()?.let { FoundLyrics(it, LyricsSource.File) },
            runCatching { embedded(track) }.getOrNull()?.let { FoundLyrics(it, LyricsSource.Embedded) },
            downloaded(track)?.let { FoundLyrics(it, LyricsSource.Downloaded) },
        )
        candidates.firstOrNull { it.lyrics.synced }
            // Texte simple seulement (souvent intégré au fichier) : LRCLIB a peut-être une version synchronisée.
            ?: fetchNow(track)?.takeIf { it.synced || candidates.isEmpty() }?.let { FoundLyrics(it, LyricsSource.Downloaded) }
            ?: candidates.firstOrNull()
    }

    /** Enregistre les paroles choisies par l'utilisateur pour ce morceau (texte LRC ou simple). */
    suspend fun choose(track: Track, text: String) = withContext(Dispatchers.IO) {
        val file = online.chosenLyricsFile(track.id)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun chosen(track: Track): Lyrics? {
        val file = online.chosenLyricsFile(track.id)
        return if (file.exists()) runCatching { parseLrc(file.readText()) }.getOrNull() else null
    }

    private val online = OnlineMetadata(appContext)
    /** Morceaux déjà cherchés en ligne pendant cette session, trouvés ou non. */
    private val triedOnline = mutableSetOf<Long>()

    private fun downloaded(track: Track): Lyrics? {
        val file = online.lyricsFile(track.id)
        return if (file.exists()) runCatching { parseLrc(file.readText()) }.getOrNull() else null
    }

    /** Aucune parole sur le téléphone : si l'utilisateur l'accepte, on les cherche sur LRCLIB tout de suite. */
    private suspend fun fetchNow(track: Track): Lyrics? {
        val prefs = PlaybackSettings.state.value
        if (!prefs.onlineLyrics || track.id in triedOnline) return null
        if (!OnlineMetadata.isOnline(appContext, prefs.onlineWifiOnly)) return null
        triedOnline += track.id
        return if (runCatching { online.fetchLyrics(track) }.getOrDefault(false)) downloaded(track) else null
    }

    private suspend fun fromLrcFile(track: Track): Lyrics? {
        val files = lrcIndex() ?: return null
        for (key in lrcKeysFor(track.fileName, track.artist, track.title)) {
            val matches = files[key].orEmpty()
            if (matches.isEmpty()) continue
            val best = matches[bestLrcMatch(matches.map { it.folder }, track.folder)]
            val text = appContext.contentResolver.openInputStream(best.uri)?.use { decodeLrcBytes(it.readBytes()) }
            return text?.let(::parseLrc)
        }
        return null
    }

    /** Parcourt une fois le dossier choisi et range tous les .lrc par nom (minuscules, sans extension). */
    private suspend fun lrcIndex(): Map<String, List<LrcFile>>? = indexLock.withLock {
        index?.let { return it }
        val tree = _folder.value ?: return null
        val resolver = appContext.contentResolver
        val found = mutableMapOf<String, MutableList<LrcFile>>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending += DocumentsContract.getTreeDocumentId(tree) to ""
        var visited = 0
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (pending.isNotEmpty() && visited < MAX_DOCUMENTS) {
            val (parentId, parentPath) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    visited++
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending += id to path
                    } else if (name.endsWith(".lrc", ignoreCase = true)) {
                        val key = name.substringBeforeLast('.').lowercase()
                        found.getOrPut(key) { mutableListOf() } +=
                            LrcFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), parentPath)
                    }
                }
            }
        }
        found.also { index = it }
    }

    @OptIn(UnstableApi::class)
    private suspend fun embedded(track: Track): Lyrics? {
        val groups = MetadataRetriever.retrieveMetadata(appContext, MediaItem.fromUri(track.contentUri)).await()
        for (g in 0 until groups.length) {
            val group = groups[g]
            for (f in 0 until group.length) {
                val metadata = group.getFormat(f).metadata ?: continue
                lyricsText(metadata)?.let { text -> parseLrc(text)?.let { return it } }
            }
        }
        return null
    }

    /** Paroles rangées dans les étiquettes : Vorbis (FLAC, OGG), ID3 USLT ou TXXX (MP3), ©lyr (M4A). */
    @OptIn(UnstableApi::class)
    private fun lyricsText(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            val text = when (val entry = metadata[i]) {
                is VorbisComment -> entry.value.takeIf { entry.key.uppercase() in VorbisKeys }
                is BinaryFrame -> if (entry.id == "USLT") decodeUslt(entry.data) else null
                is TextInformationFrame -> when {
                    entry.id == "USLT" -> entry.values.firstOrNull()
                    entry.id == "TXXX" && entry.description?.uppercase() in VorbisKeys -> entry.values.firstOrNull()
                    else -> null
                }
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            runCatching { get() }.fold(onSuccess = { cont.resume(it) }, onFailure = { cont.resumeWithException(it) })
        }, MoreExecutors.directExecutor())
        cont.invokeOnCancellation { cancel(false) }
    }

    private companion object {
        const val KEY_FOLDER = "dossier_lrc"
        const val MAX_DOCUMENTS = 50_000
        val VorbisKeys = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")
    }
}
