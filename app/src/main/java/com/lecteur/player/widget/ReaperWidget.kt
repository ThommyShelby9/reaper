package com.lecteur.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.lecteur.player.MainActivity
import com.lecteur.player.R
import com.lecteur.player.data.trackUri
import com.lecteur.player.online.OnlineMetadata
import com.lecteur.player.playback.EXTRA_ALBUM_ID
import com.lecteur.player.playback.PlaybackService
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.playback.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Widget d'écran d'accueil au style du lecteur : pochette telle quelle, titre, artiste,
 * et les touches précédent, lecture (orange) et suivant. Il suit le thème choisi dans Reaper.
 *
 * Le service de lecture le redessine à chaque changement de morceau ou d'état ([refresh]).
 * Ses touches agissent directement sur le lecteur du service ; sans lecture en cours, elles ouvrent l'app.
 */
class ReaperWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val player = PlaybackService.player ?: return
        when (intent.action) {
            ACTION_PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
            ACTION_NEXT -> player.seekToNext()
            ACTION_PREVIOUS -> player.seekToPrevious()
        }
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.lecteur.player.widget.LECTURE"
        private const val ACTION_NEXT = "com.lecteur.player.widget.SUIVANT"
        private const val ACTION_PREVIOUS = "com.lecteur.player.widget.PRECEDENT"
        private const val COVER_PX = 240

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        /** Pochette du morceau affiché, chargée une fois par morceau. */
        private var coverFor: String? = null
        private var cover: Bitmap? = null
        private var coverJob: Job? = null

        /** Ligne de paroles en cours, tenue à jour par le service de lecture ([com.lecteur.player.playback.LyricsFollower]). */
        var lyricLine: String? = null

        /** Au moins un widget est posé sur l'écran d'accueil. */
        fun isPlaced(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ReaperWidget::class.java)).isNotEmpty()

        /** La pochette a changé (choix dans la recherche) : la recharger au prochain dessin. */
        fun forgetCover() {
            coverFor = null
            cover = null
        }

        /** Redessine tous les widgets posés. À appeler sur le fil principal (celui du lecteur). */
        fun refresh(context: Context, player: Player? = PlaybackService.player) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ReaperWidget::class.java))
            if (ids.isEmpty()) return
            PlaybackSettings.init(app)
            val item = player?.currentMediaItem?.takeIf { player.mediaItemCount > 0 }
            if (item?.mediaId != coverFor) {
                coverFor = item?.mediaId
                cover = null
                coverJob?.cancel()
                if (item != null) {
                    coverJob = scope.launch {
                        val loaded = withContext(Dispatchers.IO) { runCatching { loadCover(app, item) }.getOrNull() }
                        if (loaded != null && coverFor == item.mediaId) {
                            cover = loaded
                            refresh(app)
                        }
                    }
                }
            }
            manager.updateAppWidget(ids, views(app, player, item))
        }

        private fun views(context: Context, player: Player?, item: MediaItem?): RemoteViews {
            val dark = when (PlaybackSettings.state.value.themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.Auto ->
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val playing = item != null && player?.playWhenReady == true
            val keyInk = if (dark) 0xFFFFFFFF.toInt() else 0xFF151515.toInt()
            return RemoteViews(context.packageName, R.layout.widget_player).apply {
                setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_body_dark else R.drawable.widget_body_light)
                setInt(R.id.widget_display, "setBackgroundResource", if (dark) R.drawable.widget_display_dark else R.drawable.widget_display_light)
                val key = if (dark) R.drawable.widget_key_dark else R.drawable.widget_key_light
                setInt(R.id.widget_previous, "setBackgroundResource", key)
                setInt(R.id.widget_next, "setBackgroundResource", key)
                setInt(R.id.widget_previous_icon, "setColorFilter", keyInk)
                setInt(R.id.widget_next_icon, "setColorFilter", keyInk)
                setImageViewResource(R.id.widget_play_icon, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setContentDescription(R.id.widget_play, if (playing) "Pause" else "Lecture")

                val line = lyricLine.takeIf { item != null }
                setTextViewText(R.id.widget_lyric, line ?: "")
                setViewVisibility(R.id.widget_lyric, if (line != null) View.VISIBLE else View.GONE)
                if (item == null) {
                    setTextViewText(R.id.widget_status, "Reaper")
                    setTextViewText(R.id.widget_title, "Rien en lecture")
                    setTextViewText(R.id.widget_artist, "Touchez pour ouvrir Reaper")
                    setViewVisibility(R.id.widget_live, View.GONE)
                    setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_empty)
                } else {
                    val meta = item.mediaMetadata
                    setTextViewText(R.id.widget_status, if (playing) "En lecture" else "En pause")
                    setTextViewText(R.id.widget_title, meta.title ?: "")
                    setTextViewText(R.id.widget_artist, meta.artist ?: "")
                    setViewVisibility(R.id.widget_live, if (playing) View.VISIBLE else View.GONE)
                    val image = cover
                    if (image != null) setImageViewBitmap(R.id.widget_cover, image) else setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_empty)
                }

                val openApp = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.widget_display, openApp)
                // Sans service de lecture, il n'y a rien à piloter : les touches ouvrent l'app.
                val live = player != null && item != null
                setOnClickPendingIntent(R.id.widget_play, if (live) command(context, ACTION_PLAY_PAUSE, 1) else openApp)
                setOnClickPendingIntent(R.id.widget_next, if (live) command(context, ACTION_NEXT, 2) else openApp)
                setOnClickPendingIntent(R.id.widget_previous, if (live) command(context, ACTION_PREVIOUS, 3) else openApp)
            }
        }

        private fun command(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, ReaperWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Pochette du fichier ; à défaut, celle téléchargée plus tôt pour l'album. Aucune recherche en ligne ici. */
        private fun loadCover(context: Context, item: MediaItem): Bitmap? {
            val id = item.mediaId.toLongOrNull() ?: return null
            val albumId = item.mediaMetadata.extras?.getLong(EXTRA_ALBUM_ID, -1L)?.takeIf { it >= 0 }
            val online = OnlineMetadata(context)
            fun decode(file: java.io.File) = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            // Même ordre que dans l'app : pochette choisie, puis celle du fichier, puis celle téléchargée.
            val source = albumId?.let { decode(online.chosenCoverFile(it)) }
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { context.contentResolver.loadThumbnail(trackUri(id), Size(COVER_PX, COVER_PX), null) }.getOrNull()
                } else {
                    null
                }
                ?: albumId?.let { decode(online.coverFile(it)) }
                ?: return null
            return roundedSquare(source, COVER_PX)
        }

        /** Recadre au carré (centre), met à [size] pixels et arrondit les coins, comme la pochette du lecteur. */
        private fun roundedSquare(source: Bitmap, size: Int): Bitmap {
            val side = minOf(source.width, source.height).toFloat()
            val scale = size / side
            val matrix = Matrix().apply {
                postTranslate(-(source.width - side) / 2f, -(source.height - side) / 2f)
                postScale(scale, scale)
            }
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val radius = size * 0.16f
            Canvas(result).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
            return result
        }
    }
}
