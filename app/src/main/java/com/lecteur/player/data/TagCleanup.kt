package com.lecteur.player.data

/*
 * Nettoyage des étiquettes à l'affichage (les fichiers ne sont jamais modifiés) :
 * restes de sites de téléchargement, numéros de piste collés au titre, mentions « Official Video »,
 * doublons « (1) », noms de fichier avec tirets ou soulignés, émojis en tête.
 */

/** Adresses web : « Www.33rapfr.com », « https://site.net/… », « monsite.fr ». */
private val WebAddress = Regex("""(?i)\b(?:https?://)?(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|fr|info|biz|io|co|tv|me|cc|ws|to|ru|de|be|ch|ca|xyz|site|online|top|club|pro|mobi)(?:/\S*)?\b""")

/**
 * Séparateurs laissés orphelins en début ou fin après un retrait (« Damso - » → « Damso »).
 * Le point n'en fait pas partie : « + de pluie... » garde ses points de suspension.
 */
private val DanglingSeparators = Regex("""^[\s\-–—|:_,/~•·]+|[\s\-–—|:_,/~•·]+$""")

/** Parenthèses ou crochets devenus vides. */
// Sur Android, le moteur ICU exige « \] » et « \} » échappés (Java les tolère) : toujours les échapper.
private val EmptyBrackets = Regex("""\(\s*\)|\[\s*\]|\{\s*\}""")

/** Mentions promotionnelles des titres de vidéos. */
private val PromoMentions = Regex(
    """(?i)[(\[]\s*(?:official\s+(?:music\s+)?(?:video|audio|lyric(?:s)?\s+video|visuali[sz]er)|official(?:\s+(?:hd|hq|4k|version))?|clip\s+officiel|audio\s+officiel|lyrics?|paroles|audio|hd|hq|4k|explicit|video\s+officielle)\s*[)\]]""",
)

/** Les mêmes mentions sans parenthèses, n'importe où (« mad_over_you_official_music_video_runtown »). */
private val LoosePromo = Regex("""(?i)\b(?:official\s+(?:music\s+)?(?:video|audio)|clip\s+officiel|lyrics?\s+video)\b""")

/** Numéro de piste collé au début : « 01-La quête », « 02. Titre », « 3_Titre ». */
private val LeadingTrackNumber = Regex("""^\d{1,3}\s*[-._)]\s*(?=\S)""")

/** « (1) » en tête, comme dans « (1)mad_over_you ». */
private val LeadingCopyNumber = Regex("""^\(\d{1,2}\)\s*""")

/** Marques de copie en fin : « (1) », « _2 », « - Copie », « copy ». */
private val TrailingCopyMarker = Regex("""(?i)(?:\s*\(\d{1,2}\)|_\d{1,2}|\s+-\s*(?:copie|copy)|\s+(?:copie|copy))+$""")

/** Émojis et symboles décoratifs en tête (on garde « # », « & », « $ », « ! », etc.). */
private val LeadingEmoji = Regex("""^[\p{So}\p{Sk}\x{FE0F}\x{200D}]+\s*""")

private val Spaces = Regex("""\s{2,}""")

private fun tidy(text: String): String = text
    .replace(EmptyBrackets, " ")
    .replace(Spaces, " ")
    .replace(DanglingSeparators, "")
    .trim()

/** Nettoie un nom d'artiste ou d'album ; renvoie une chaîne vide s'il n'en reste rien. */
fun cleanName(raw: String): String = tidy(raw.replace(WebAddress, " "))

/**
 * Nettoie un titre. Si le titre ressemble à un nom de fichier (numéro en tête, ou aucun espace),
 * les tirets et soulignés deviennent des espaces : « 02-La-quête » → « La quête ».
 */
fun cleanTitle(raw: String): String {
    var text = raw.trim()
    text = text.replace(LeadingEmoji, "")
    text = text.replace(LeadingCopyNumber, "")
    val numbered = LeadingTrackNumber.containsMatchIn(text)
    text = text.replace(LeadingTrackNumber, "")
    val fileLike = numbered || !text.contains(' ')
    if (fileLike && (text.contains('_') || text.count { it == '-' } >= 1 && numbered)) {
        text = text.replace('_', ' ').replace(Regex("""(?<=\S)-(?=\S)"""), " ")
    } else if (!text.contains(' ') && text.contains('_')) {
        text = text.replace('_', ' ')
    }
    text = text.replace(WebAddress, " ")
    text = text.replace(PromoMentions, " ")
    text = text.replace(LoosePromo, " ")
    text = text.replace(TrailingCopyMarker, "")
    return tidy(text)
}

/** Artiste, titre et album affichés, tirés des étiquettes brutes. */
data class CleanTags(val title: String, val artist: String, val album: String)

/**
 * Nettoie les trois étiquettes ensemble. Si l'artiste est inconnu et que le titre a la forme
 * « Artiste - Titre », on sépare les deux.
 */
fun cleanTags(rawTitle: String, rawArtist: String, rawAlbum: String, fallbackTitle: String): CleanTags {
    var artist = cleanName(rawArtist).ifEmpty { UNKNOWN_ARTIST }
    var title = cleanTitle(rawTitle).ifEmpty { cleanTitle(fallbackTitle).ifEmpty { rawTitle.trim() } }
    if (artist == UNKNOWN_ARTIST) {
        val parts = title.split(" - ", " – ", " — ", limit = 2)
        if (parts.size == 2 && parts[0].length in 1..40 && parts[1].isNotBlank()) {
            artist = parts[0].trim()
            title = parts[1].trim()
        }
    }
    val album = cleanName(rawAlbum).ifEmpty { UNKNOWN_ALBUM }
    return CleanTags(title, artist, album)
}
