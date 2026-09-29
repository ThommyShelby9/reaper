package com.lecteur.player.data

/** Un fichier audio de la bibliothèque, tel que lu dans MediaStore. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    /** Numéro de plage dans le disque (0 si inconnu). */
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int?,
    val mimeType: String?,
    /** Dossier relatif, par exemple « Music/Lune Basse/Quai Nord ». */
    val folder: String,
    val dateAddedSec: Long,
    /** Nom du fichier avec son extension, pour retrouver le .lrc du même nom. */
    val fileName: String = "",
    /** Genre inscrit dans le fichier (Android 11 et plus), tel quel. */
    val genreTag: String? = null,
)

const val UNKNOWN_ARTIST = "Artiste inconnu"
const val UNKNOWN_ALBUM = "Album inconnu"

/** Remplace une étiquette vide ou « <unknown> » (valeur de MediaStore) par [fallback]. */
fun cleanTag(value: String?, fallback: String): String {
    val trimmed = value?.trim().orEmpty()
    return if (trimmed.isEmpty() || trimmed == "<unknown>") fallback else trimmed
}

/** MediaStore code la plage sous la forme disque × 1000 + plage (1003 = disque 1, plage 3). */
fun splitTrackNumber(raw: Int): Pair<Int, Int> {
    if (raw <= 0) return 0 to 0
    return (raw / 1000) to (raw % 1000)
}

/** Dossier relatif tiré d'un chemin absolu (Android 9 et moins), sans le préfixe du stockage. */
fun folderFromData(path: String?): String {
    if (path.isNullOrBlank()) return ""
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    val storagePrefix = Regex("^/storage/(emulated/\\d+|[^/]+)/?")
    return parent.replace(storagePrefix, "").trim('/')
}

/** Libellé court du format : « FLAC », « MP3 », « AAC »… */
fun formatLabel(mimeType: String?): String {
    val mime = mimeType?.lowercase()?.trim().orEmpty()
    return when (mime) {
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/mp4", "audio/aac", "audio/mp4a-latm", "audio/x-m4a", "audio/aacp" -> "AAC"
        "audio/ogg", "application/ogg", "audio/vorbis" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/wav", "audio/x-wav", "audio/wave" -> "WAV"
        "audio/x-ms-wma" -> "WMA"
        "audio/alac" -> "ALAC"
        "audio/aiff", "audio/x-aiff" -> "AIFF"
        "" -> "Audio"
        else -> mime.substringAfter('/').removePrefix("x-").uppercase()
    }
}
