package com.lecteur.player.lyrics

import java.nio.charset.Charset

/** Une ligne de paroles. [timeMs] vaut -1 pour des paroles non synchronisées. */
data class LyricLine(val timeMs: Long, val text: String)

/** Paroles d'un morceau : synchronisées (chaque ligne a son instant) ou simple texte. */
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>)

/** Marque d'ordre des octets (BOM) que certains éditeurs ajoutent en tête des fichiers texte. */
private val BOM = 0xFEFF.toChar().toString()

// Crochets fermants échappés : le moteur d'expressions régulières d'Android (ICU) les exige.
private val TimeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
private val OffsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
private val InfoTag = Regex("""^\[[a-zA-Z#]+:.*\]\s*$""")
private val WordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

private fun tagToMs(match: MatchResult): Long {
    val (min, sec, fraction) = match.destructured
    val fractionMs = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        else -> fraction.toLong()
    }
    return (min.toLong() * 60 + sec.toLong()) * 1000 + fractionMs
}

/**
 * Lit un texte au format LRC : « [01:23.45]paroles », plusieurs horodatages par ligne,
 * décalage « [offset:+500] », balises de mots « <01:23.45> » (ignorées) et étiquettes « [ar:…] ».
 * Un texte sans horodatage donne des paroles non synchronisées.
 * Renvoie null si le texte ne contient aucune parole.
 */
fun parseLrc(text: String): Lyrics? {
    val rawLines = text.removePrefix(BOM).lines()
    var offsetMs = 0L
    val synced = mutableListOf<LyricLine>()
    val plain = mutableListOf<String>()

    for (raw in rawLines) {
        val line = raw.trim()
        OffsetTag.find(line)?.let { offsetMs = it.groupValues[1].toLong() }
        val tags = generateSequence(TimeTag.matchAt(line, 0)) { previous ->
            TimeTag.matchAt(line, previous.range.last + 1)
        }.toList()
        if (tags.isNotEmpty()) {
            val lyric = line.substring(tags.last().range.last + 1).replace(WordTag, "").trim()
            tags.forEach { synced += LyricLine(tagToMs(it), lyric) }
        } else if (!InfoTag.matches(line)) {
            plain += line.replace(WordTag, "").trim()
        }
    }

    if (synced.isNotEmpty()) {
        // Un décalage positif fait apparaître les paroles plus tôt.
        val lines = synced
            .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
            .sortedBy { it.timeMs }
        return Lyrics(synced = true, lines = lines)
    }
    val trimmed = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    if (trimmed.isEmpty()) return null
    return Lyrics(synced = false, lines = trimmed.map { LyricLine(-1, it) })
}

/**
 * Décode un fichier .lrc : UTF-8 (avec ou sans BOM) ou UTF-16 avec BOM ; si l'UTF-8 est invalide,
 * le fichier vient sans doute d'un vieux logiciel Windows et on le lit en Windows-1252.
 */
fun decodeLrcBytes(bytes: ByteArray): String {
    if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) {
        return String(bytes, Charsets.UTF_16)
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}

/** Index de la ligne chantée à [positionMs], ou -1 avant la première ligne. */
fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/**
 * Décode une trame ID3 USLT (paroles non synchronisées d'un MP3) :
 * encodage (1 octet), langue (3 octets), description terminée par un zéro, puis le texte.
 */
fun decodeUslt(data: ByteArray): String? {
    if (data.size < 5) return null
    val charset: Charset
    val terminatorSize: Int
    when (data[0].toInt()) {
        0 -> { charset = Charsets.ISO_8859_1; terminatorSize = 1 }
        1 -> { charset = Charsets.UTF_16; terminatorSize = 2 }
        2 -> { charset = Charsets.UTF_16BE; terminatorSize = 2 }
        3 -> { charset = Charsets.UTF_8; terminatorSize = 1 }
        else -> return null
    }
    var index = 4
    while (index + terminatorSize <= data.size) {
        val isTerminator = (0 until terminatorSize).all { data[index + it].toInt() == 0 }
        if (isTerminator) break
        index += terminatorSize
    }
    val start = index + terminatorSize
    if (start > data.size) return null
    return String(data, start, data.size - start, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
}

/** Noms de fichier .lrc possibles pour un morceau, en minuscules et sans extension, par ordre de préférence. */
fun lrcKeysFor(fileName: String, artist: String, title: String): List<String> = listOfNotNull(
    fileName.substringBeforeLast('.').lowercase().takeIf { it.isNotBlank() },
    "$artist - $title".lowercase(),
    title.lowercase(),
).distinct()

/**
 * Parmi plusieurs .lrc de même nom, choisit celui dont le dossier ressemble le plus à celui du morceau
 * (le plus de dossiers en commun en partant de la fin).
 */
fun bestLrcMatch(candidateFolders: List<String>, trackFolder: String): Int {
    if (candidateFolders.isEmpty()) return -1
    val trackSegments = trackFolder.lowercase().split('/').filter { it.isNotBlank() }.reversed()
    fun score(folder: String): Int {
        val segments = folder.lowercase().split('/').filter { it.isNotBlank() }.reversed()
        return segments.zip(trackSegments).takeWhile { (a, b) -> a == b }.size
    }
    return candidateFolders.indices.maxBy { score(candidateFolders[it]) }
}

/** Nom lisible d'un dossier choisi dans le sélecteur Android (« primary:Music » → « Music »). */
fun treeLabel(treeDocumentId: String): String =
    treeDocumentId.substringAfter(':', "").trim('/').ifEmpty { "Stockage principal" }

/**
 * Ligne chantée à [positionMs], pour l'affichage « une phrase à la fois » (lecteur, écran de verrouillage, widget).
 * Null si les paroles ne sont pas synchronisées, avant la première ligne ou pendant un passage sans paroles.
 */
fun currentLyricLine(lyrics: Lyrics?, positionMs: Long): String? {
    if (lyrics == null || !lyrics.synced) return null
    val index = currentLineIndex(lyrics.lines, positionMs)
    return lyrics.lines.getOrNull(index)?.text?.trim()?.takeIf { it.isNotEmpty() }
}
