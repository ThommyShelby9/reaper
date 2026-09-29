package com.lecteur.player.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.lecteur.player.R
import com.lecteur.player.data.Track

/**
 * Dessine une carte à partager au format story (1080 × 1920), dans le style de Reaper :
 * fond noir, pochette telle quelle, titre en matrice de points, ligne en cours et points en orange.
 */
class ShareCardRenderer(context: Context) {

    private val doto = font(context, R.font.doto)
    private val mono = font(context, R.font.space_mono_regular)
    private val monoBold = font(context, R.font.space_mono_bold)

    fun render(template: ShareTemplate, track: Track, cover: Bitmap?, excerpt: LyricExcerpt, progress: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        when (template) {
            ShareTemplate.Cover -> drawCover(canvas, track, cover, progress)
            ShareTemplate.CoverLyrics -> drawCoverLyrics(canvas, track, cover, excerpt)
            ShareTemplate.Lyrics -> drawLyrics(canvas, track, cover, excerpt)
        }
        drawFooter(canvas)
        return bitmap
    }

    private fun drawCover(canvas: Canvas, track: Track, cover: Bitmap?, progress: Float) {
        val size = 880f
        drawArtwork(canvas, cover, RectF(MARGIN, 260f, MARGIN + size, 260f + size), 36f)
        var y = 260f + size + 90f
        y += drawText(canvas, track.title, doto, 88f, Color.WHITE, MARGIN, y, CONTENT_WIDTH, maxLines = 2)
        y += 18f
        y += drawText(canvas, track.artist, mono, 40f, MUTED, MARGIN, y, CONTENT_WIDTH, maxLines = 1)
        drawDotProgress(canvas, progress, y + 70f)
    }

    private fun drawCoverLyrics(canvas: Canvas, track: Track, cover: Bitmap?, excerpt: LyricExcerpt) {
        val size = 400f
        drawArtwork(canvas, cover, RectF(MARGIN, 200f, MARGIN + size, 200f + size), 28f)
        // Titre et artiste à droite de la pochette.
        val textLeft = MARGIN + size + 44f
        val textWidth = WIDTH - MARGIN - textLeft
        var y = 200f + 60f
        y += drawText(canvas, track.title, doto, 60f, Color.WHITE, textLeft, y, textWidth, maxLines = 4)
        y += 14f
        drawText(canvas, track.artist, mono, 34f, MUTED, textLeft, y, textWidth, maxLines = 2)
        drawExcerpt(canvas, excerpt, top = 760f, bottom = 1700f, maxSize = 64f)
    }

    private fun drawLyrics(canvas: Canvas, track: Track, cover: Bitmap?, excerpt: LyricExcerpt) {
        drawExcerpt(canvas, excerpt, top = 300f, bottom = 1440f, maxSize = 76f)
        val thumb = 150f
        val top = 1520f
        drawArtwork(canvas, cover, RectF(MARGIN, top, MARGIN + thumb, top + thumb), 18f)
        val textLeft = MARGIN + thumb + 36f
        val textWidth = WIDTH - MARGIN - textLeft
        var y = top + 12f
        y += drawText(canvas, track.title, doto, 50f, Color.WHITE, textLeft, y, textWidth, maxLines = 2)
        y += 8f
        drawText(canvas, track.artist, mono, 32f, MUTED, textLeft, y, textWidth, maxLines = 1)
    }

    /** Lignes de paroles, la ligne en cours en orange ; la taille diminue jusqu'à tenir entre [top] et [bottom]. */
    private fun drawExcerpt(canvas: Canvas, excerpt: LyricExcerpt, top: Float, bottom: Float, maxSize: Float) {
        if (excerpt.lines.isEmpty()) return
        var size = maxSize
        var layouts: List<StaticLayout>
        val gap = { s: Float -> s * 0.55f }
        while (true) {
            layouts = excerpt.lines.mapIndexed { index, line ->
                val color = if (index == excerpt.highlight || excerpt.highlight < 0 && index == 0) ACCENT else Color.WHITE
                layout(line, monoBold, size, color, CONTENT_WIDTH.toInt(), maxLines = 4)
            }
            val height = layouts.sumOf { it.height } + gap(size) * (layouts.size - 1)
            if (height <= bottom - top || size <= 34f) break
            size -= 4f
        }
        var y = top
        layouts.forEach { layout ->
            canvas.save()
            canvas.translate(MARGIN, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap(size)
        }
    }

    /** Pochette d'origine, recadrée au carré et arrondie ; à défaut, une grille de points éteints. */
    private fun drawArtwork(canvas: Canvas, cover: Bitmap?, rect: RectF, radius: Float) {
        if (cover == null) {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL })
            val dots = 9
            val step = rect.width() / (dots + 1)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT_OFF }
            for (i in 1..dots) for (j in 1..dots) {
                canvas.drawCircle(rect.left + i * step, rect.top + j * step, step * 0.28f, dot)
            }
            return
        }
        val side = minOf(cover.width, cover.height).toFloat()
        val scale = rect.width() / side
        val matrix = Matrix().apply {
            postTranslate(-(cover.width - side) / 2f, -(cover.height - side) / 2f)
            postScale(scale, scale)
            postTranslate(rect.left, rect.top)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    /** Rangée de points : orange jusqu'au moment écouté, éteints ensuite (comme la forme d'onde du lecteur). */
    private fun drawDotProgress(canvas: Canvas, progress: Float, y: Float) {
        val count = 40
        val step = CONTENT_WIDTH / (count - 1)
        val played = (progress.coerceIn(0f, 1f) * count).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until count) {
            paint.color = when {
                i < played -> ACCENT
                i == played -> Color.WHITE
                else -> DOT_OFF
            }
            canvas.drawCircle(MARGIN + i * step, y, 7f, paint)
        }
    }

    private fun drawFooter(canvas: Canvas) {
        val y = HEIGHT - 110f
        canvas.drawCircle(MARGIN + 10f, y - 12f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            textSize = 32f
            color = MUTED
        }
        canvas.drawText("Écouté sur Reaper", MARGIN + 40f, y, paint)
    }

    /** Dessine [text] en haut à gauche en ([x], [y]) ; renvoie la hauteur occupée. */
    private fun drawText(canvas: Canvas, text: String, typeface: Typeface, size: Float, color: Int, x: Float, y: Float, width: Float, maxLines: Int): Float {
        if (text.isBlank()) return 0f
        val layout = layout(text, typeface, size, color, width.toInt(), maxLines)
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }

    private fun layout(text: String, typeface: Typeface, size: Float, color: Int, width: Int, maxLines: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size
            this.color = color
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.08f)
            .build()
    }

    private fun font(context: Context, id: Int): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.MONOSPACE

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        private const val MARGIN = 100f
        private const val CONTENT_WIDTH = WIDTH - 2 * MARGIN
        private const val ACCENT = 0xFFFF5A1F.toInt()
        private const val MUTED = 0xFF8E8E93.toInt()
        private const val PANEL = 0xFF1C1C1E.toInt()
        private const val DOT_OFF = 0xFF3A3A3C.toInt()
    }
}
