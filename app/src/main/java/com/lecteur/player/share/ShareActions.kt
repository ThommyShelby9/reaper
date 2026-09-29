package com.lecteur.player.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Partage d'une carte : fichier temporaire, WhatsApp (statut ou discussion), autres apps, galerie. */
object ShareActions {

    const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /** WhatsApp installé (ou WhatsApp Business), sinon null. */
    fun whatsAppPackage(context: Context): String? =
        listOf(WHATSAPP, WHATSAPP_BUSINESS).firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    /** Écrit la carte dans le cache de l'app et renvoie son adresse partageable. */
    fun cacheCard(context: Context, card: Bitmap): Uri {
        val dir = File(context.cacheDir, "partage").apply { mkdirs() }
        // Un seul fichier à la fois : les cartes précédentes ne s'accumulent pas.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "reaper_${System.currentTimeMillis()}.png")
        file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.partage", file)
    }

    /**
     * Intent de partage d'image. Avec [targetPackage] = WhatsApp, l'app s'ouvre sur son choix de destinataire,
     * où « Mon statut » figure en tête.
     */
    fun shareIntent(uri: Uri, caption: String, targetPackage: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            targetPackage?.let(::setPackage)
        }

    fun chooser(uri: Uri, caption: String): Intent =
        Intent.createChooser(shareIntent(uri, caption), "Partager la carte").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Enregistre la carte dans Images/Reaper. Renvoie vrai si c'est fait. */
    fun saveToGallery(context: Context, card: Bitmap): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "reaper_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Reaper")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        true
    }.getOrDefault(false)
}
