package com.lecteur.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.lecteur.player.online.OnlineMetadata
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.ui.components.DotGrid
import com.lecteur.player.ui.components.luminanceOf
import com.lecteur.player.ui.components.normalizeContrast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Charge la pochette d'un morceau telle quelle (image d'origine, non retouchée), avec un cache par album. */
class CoverLoader(context: Context) {

    private val appContext = context.applicationContext
    private val cache get() = sharedCache
    private val online = OnlineMetadata(appContext)
    /** Albums déjà cherchés en ligne pendant cette session, trouvés ou non. */
    private val triedOnline = mutableSetOf<Long>()

    /**
     * Pochette du fichier ; à défaut, celle téléchargée plus tôt ; à défaut, et si l'utilisateur l'accepte,
     * on la cherche en ligne tout de suite. Renvoie null si rien n'a été trouvé.
     */
    suspend fun cover(track: Track): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(track.albumId)?.let { return@withContext it }
        val bitmap = loadChosen(track)
            ?: runCatching { loadBitmap(track) }.getOrNull()
            ?: loadDownloaded(track)
            ?: fetchNow(track)
            ?: return@withContext null
        val image = bitmap.asImageBitmap()
        cache.put(track.albumId, image)
        image
    }

    /** Pochette choisie à la main dans la recherche : elle passe avant celle du fichier. */
    private fun loadChosen(track: Track): Bitmap? {
        val file = online.chosenCoverFile(track.albumId)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    private fun loadDownloaded(track: Track): Bitmap? {
        val file = online.coverFile(track.albumId)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.path)
    }

    private suspend fun fetchNow(track: Track): Bitmap? {
        val prefs = PlaybackSettings.state.value
        if (!prefs.onlineCovers || track.albumId in triedOnline) return null
        if (!OnlineMetadata.isOnline(appContext, prefs.onlineWifiOnly)) return null
        triedOnline += track.albumId
        return if (runCatching { online.fetchCover(track) }.getOrDefault(false)) loadDownloaded(track) else null
    }

    private fun loadBitmap(track: Track): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return appContext.contentResolver.loadThumbnail(track.contentUri, Size(COVER_SIZE, COVER_SIZE), null)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, track.contentUri)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            retriever.release()
        }
    }
}

/** Réduit l'image à [size] × [size] pixels et garde la luminosité de chacun, contraste étiré. */
fun dotGridFromBitmap(source: Bitmap, size: Int): DotGrid {
    val software = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    val scaled = software.scale(size, size)
    val values = FloatArray(size * size) { luminanceOf(scaled[it % size, it / size]) }
    return DotGrid(size, normalizeContrast(values))
}

/** Taille de chargement des pochettes : nette sur un écran de téléphone en pleine largeur. */
private const val COVER_SIZE = 720

/** Pochettes déjà chargées, communes à tout le processus (écran de verrouillage, widget, app). */
private val sharedCache = LruCache<Long, ImageBitmap>(24)

/** Oublie la pochette gardée en mémoire pour [albumId] (après un choix dans la recherche). */
fun forgetCover(albumId: Long) {
    sharedCache.remove(albumId)
}
