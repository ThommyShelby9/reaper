package com.lecteur.player.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Lit les fichiers audio du téléphone dans MediaStore et suit les ajouts ou suppressions. */
class AudioLibrary(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /** Émet la liste complète au démarrage, puis à chaque changement dans MediaStore. */
    fun tracks(): Flow<List<Track>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        .map { query() }
        .flowOn(Dispatchers.IO)

    private fun query(): List<Track> {
        val usesRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val pathColumn = if (usesRelativePath) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            pathColumn,
        )
        val hasGenre = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val fullProjection = if (hasGenre) projection + MediaStore.Audio.Media.GENRE else projection
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = resolver.query(collection, fullProjection, selection, null, null) ?: return emptyList()
        return cursor.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val displayName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val track = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val year = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val mime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val path = c.getColumnIndexOrThrow(pathColumn)
            val genre = if (hasGenre) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1

            buildList(c.count) {
                while (c.moveToNext()) {
                    val (disc, number) = splitTrackNumber(c.getInt(track))
                    // Étiquettes nettoyées pour l'affichage et les recherches ; les fichiers restent intacts.
                    val fileTitle = c.getString(displayName)?.substringBeforeLast('.') ?: "Sans titre"
                    val tags = cleanTags(
                        rawTitle = cleanTag(c.getString(title), fileTitle),
                        rawArtist = cleanTag(c.getString(artist), UNKNOWN_ARTIST),
                        rawAlbum = cleanTag(c.getString(album), UNKNOWN_ALBUM),
                        fallbackTitle = fileTitle,
                    )
                    val rawPath = c.getString(path)
                    add(
                        Track(
                            id = c.getLong(id),
                            title = tags.title,
                            artist = tags.artist,
                            album = tags.album,
                            albumId = c.getLong(albumId),
                            durationMs = c.getLong(duration),
                            trackNumber = number,
                            discNumber = disc,
                            year = c.getInt(year).takeIf { it > 0 },
                            mimeType = c.getString(mime),
                            folder = if (usesRelativePath) rawPath.orEmpty().trim('/') else folderFromData(rawPath),
                            dateAddedSec = c.getLong(dateAdded),
                            fileName = c.getString(displayName).orEmpty(),
                            genreTag = if (genre >= 0) c.getString(genre) else null,
                        ),
                    )
                }
            }
        }
    }
}

/** Adresse du fichier dans MediaStore, utilisable pour la lecture. */
fun trackUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

val Track.contentUri: Uri get() = trackUri(id)
