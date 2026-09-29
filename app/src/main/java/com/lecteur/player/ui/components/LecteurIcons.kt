package com.lecteur.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icônes de transport, dessinées sur une grille de 24. La teinte vient de `Icon(tint = …)`. */
object LecteurIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private val Fill = SolidColor(Color.Black)

    val Play: ImageVector by lazy {
        icon("Play") {
            path(fill = Fill) { moveTo(7f, 4.5f); verticalLineToRelative(15f); lineToRelative(12f, -7.5f); close() }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = Fill) {
                moveTo(6f, 5f); horizontalLineToRelative(4f); verticalLineToRelative(14f); horizontalLineTo(6f); close()
                moveTo(14f, 5f); horizontalLineToRelative(4f); verticalLineToRelative(14f); horizontalLineToRelative(-4f); close()
            }
        }
    }

    val Previous: ImageVector by lazy {
        icon("Previous") {
            path(fill = Fill) {
                moveTo(5f, 5f); horizontalLineToRelative(2f); verticalLineToRelative(14f); horizontalLineTo(5f); close()
                moveTo(19f, 5f); lineTo(9f, 12f); lineToRelative(10f, 7f); close()
            }
        }
    }

    val Next: ImageVector by lazy {
        icon("Next") {
            path(fill = Fill) {
                moveTo(17f, 5f); horizontalLineToRelative(2f); verticalLineToRelative(14f); horizontalLineToRelative(-2f); close()
                moveTo(5f, 5f); lineToRelative(10f, 7f); lineToRelative(-10f, 7f); close()
            }
        }
    }

    private fun chevron(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = icon(name) {
        path(
            fill = null,
            stroke = Fill,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    /** Refermer l'écran de lecture. */
    val ChevronDown: ImageVector by lazy { chevron("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }

    /** Revenir au niveau précédent. */
    val ChevronLeft: ImageVector by lazy { chevron("ChevronLeft") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) } }

    /** Supprimer ou fermer. */
    val Close: ImageVector by lazy { chevron("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }

    val Search: ImageVector by lazy {
        chevron("Search") {
            moveTo(16f, 11f)
            arcToRelative(5f, 5f, 0f, true, true, -10f, 0f)
            arcToRelative(5f, 5f, 0f, true, true, 10f, 0f)
            moveTo(15f, 15f); lineTo(20f, 20f)
        }
    }

    /** File d'attente : trois lignes et un triangle de lecture. */
    val Queue: ImageVector by lazy {
        icon("Queue") {
            path(fill = null, stroke = Fill, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 6f); horizontalLineTo(16f)
                moveTo(4f, 11f); horizontalLineTo(16f)
                moveTo(4f, 16f); horizontalLineTo(11f)
            }
            path(fill = Fill) { moveTo(15f, 14f); verticalLineToRelative(6f); lineToRelative(5f, -3f); close() }
        }
    }

    /** Égaliseur : trois curseurs à des hauteurs différentes. */
    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            path(fill = null, stroke = Fill, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 4f); verticalLineTo(20f)
                moveTo(12f, 4f); verticalLineTo(20f)
                moveTo(18f, 4f); verticalLineTo(20f)
            }
            path(fill = Fill) {
                moveTo(4f, 13f); horizontalLineToRelative(4f); verticalLineToRelative(3f); horizontalLineToRelative(-4f); close()
                moveTo(10f, 7f); horizontalLineToRelative(4f); verticalLineToRelative(3f); horizontalLineToRelative(-4f); close()
                moveTo(16f, 11f); horizontalLineToRelative(4f); verticalLineToRelative(3f); horizontalLineToRelative(-4f); close()
            }
        }
    }

    /** Paroles : lignes de texte de longueurs inégales. */
    val Lyrics: ImageVector by lazy {
        chevron("Lyrics") {
            moveTo(4f, 6f); horizontalLineTo(20f)
            moveTo(4f, 11f); horizontalLineTo(14f)
            moveTo(4f, 16f); horizontalLineTo(18f)
            moveTo(4f, 21f); horizontalLineTo(11f)
        }
    }

    /** Bilan : trois barres qui montent. */
    val Chart: ImageVector by lazy {
        icon("Chart") {
            path(fill = Fill) {
                moveTo(4f, 14f); horizontalLineToRelative(4f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(10f, 9f); horizontalLineToRelative(4f); verticalLineTo(20f); horizontalLineToRelative(-4f); close()
                moveTo(16f, 4f); horizontalLineToRelative(4f); verticalLineTo(20f); horizontalLineToRelative(-4f); close()
            }
        }
    }

    /** Jam : trois points reliés, plusieurs téléphones qui écoutent ensemble. */
    val Jam: ImageVector by lazy {
        icon("Jam") {
            path(fill = null, stroke = Fill, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 17f); lineTo(12f, 6f); lineTo(18f, 17f); close()
            }
            path(fill = Fill) {
                moveTo(9f, 6f); arcToRelative(3f, 3f, 0f, true, true, 6f, 0f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                moveTo(3f, 17f); arcToRelative(3f, 3f, 0f, true, true, 6f, 0f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                moveTo(15f, 17f); arcToRelative(3f, 3f, 0f, true, true, 6f, 0f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }
        }
    }

    /** Réglages : un bouton rotatif à huit crans. */
    val Settings: ImageVector by lazy {
        chevron("Settings") {
            moveTo(15f, 12f)
            arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
            moveTo(18.5f, 12f)
            arcToRelative(6.5f, 6.5f, 0f, true, true, -13f, 0f)
            arcToRelative(6.5f, 6.5f, 0f, true, true, 13f, 0f)
            moveTo(12f, 2.5f); verticalLineTo(5.5f)
            moveTo(12f, 18.5f); verticalLineTo(21.5f)
            moveTo(2.5f, 12f); horizontalLineTo(5.5f)
            moveTo(18.5f, 12f); horizontalLineTo(21.5f)
            moveTo(5.3f, 5.3f); lineTo(7.4f, 7.4f)
            moveTo(16.6f, 16.6f); lineTo(18.7f, 18.7f)
            moveTo(5.3f, 18.7f); lineTo(7.4f, 16.6f)
            moveTo(16.6f, 7.4f); lineTo(18.7f, 5.3f)
        }
    }

    /** Lecture aléatoire : deux flèches qui se croisent. */
    val Shuffle: ImageVector by lazy {
        chevron("Shuffle") {
            moveTo(3f, 7f); horizontalLineTo(6.5f)
            curveTo(8.5f, 7f, 9.7f, 8f, 10.8f, 9.7f)
            lineTo(13.2f, 14.3f)
            curveTo(14.3f, 16f, 15.5f, 17f, 17.5f, 17f)
            horizontalLineTo(21f)
            moveTo(3f, 17f); horizontalLineTo(6.5f)
            curveTo(7.8f, 17f, 8.7f, 16.6f, 9.5f, 15.8f)
            moveTo(14.5f, 8.2f)
            curveTo(15.3f, 7.4f, 16.2f, 7f, 17.5f, 7f)
            horizontalLineTo(21f)
            moveTo(18f, 4f); lineTo(21f, 7f); lineTo(18f, 10f)
            moveTo(18f, 14f); lineTo(21f, 17f); lineTo(18f, 20f)
        }
    }

    private fun androidx.compose.ui.graphics.vector.PathBuilder.heart() {
        moveTo(12f, 20f)
        curveTo(12f, 20f, 3.5f, 14.8f, 3.5f, 9f)
        arcTo(4.4f, 4.4f, 0f, false, true, 12f, 6.6f)
        arcTo(4.4f, 4.4f, 0f, false, true, 20.5f, 9f)
        curveTo(20.5f, 14.8f, 12f, 20f, 12f, 20f)
        close()
    }

    /** Titre pas encore aimé : cœur vide. */
    val Heart: ImageVector by lazy { chevron("Heart") { heart() } }

    /** Titre aimé, dans les Favoris : cœur plein. */
    val HeartFilled: ImageVector by lazy { icon("HeartFilled") { path(fill = Fill) { heart() } } }

    val Repeat: ImageVector by lazy {
        icon("Repeat") {
            path(
                fill = null,
                stroke = Fill,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 11f); verticalLineTo(8f); horizontalLineTo(20f)
                moveTo(17f, 5f); lineTo(20f, 8f); lineTo(17f, 11f)
                moveTo(20f, 13f); verticalLineTo(16f); horizontalLineTo(4f)
                moveTo(7f, 19f); lineTo(4f, 16f); lineTo(7f, 13f)
            }
        }
    }
}
