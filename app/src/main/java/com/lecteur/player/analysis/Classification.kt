package com.lecteur.player.analysis

import com.lecteur.player.data.Track
import com.lecteur.player.data.TrackGroup
import com.lecteur.player.data.countLabel
import com.lecteur.player.data.normalizeForSearch
import com.lecteur.player.data.sortedByTitle

/** Humeurs, sur deux axes : l'intensité (énergie, tempo) et la couleur (majeur/mineur, brillance). */
enum class Mood(val label: String, val description: String) {
    Energetic("Énergique", "Rapide, fort et lumineux"),
    Intense("Intense", "Fort et sombre"),
    Happy("Joyeux", "Entraînant et lumineux"),
    Relaxed("Détendu", "Doux et lumineux"),
    Melancholic("Mélancolique", "Doux et sombre"),
}

/**
 * Humeur d'un morceau d'après ses caractéristiques. C'est une règle simple, pas une écoute humaine :
 * elle donne une bonne tendance sur une bibliothèque, pas un verdict sur chaque titre.
 */
fun moodOf(features: AudioFeatures): Mood {
    val tempo = ((features.bpm - 70f) / 110f).coerceIn(0f, 1f)
    val arousal = 0.6f * features.energy + 0.4f * tempo
    val valence = 0.55f * (if (features.minor) 0.2f else 0.9f) + 0.45f * features.brightness
    return when {
        arousal >= 0.62f -> if (valence >= 0.5f) Mood.Energetic else Mood.Intense
        valence >= 0.55f -> if (arousal >= 0.4f) Mood.Happy else Mood.Relaxed
        else -> Mood.Melancholic
    }
}

/** Familles de genres, reconnues par mots-clés dans l'étiquette du fichier ou les tags en ligne. */
private val GenreFamilies: List<Pair<String, List<String>>> = listOf(
    "Hip-hop" to listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "grime"),
    "Musiques afro" to listOf("afro", "zouk", "kompa", "coupe", "ndombolo", "rumba congo", "amapiano", "makossa", "highlife", "bikutsi", "mbalax", "kizomba", "gqom", "bongo"),
    "Électro" to listOf("electro", "electronic", "house", "techno", "edm", "trance", "dance", "dubstep", "drum and bass", "dnb", "garage", "synthwave", "disco"),
    "Metal" to listOf("metal", "hardcore", "metalcore"),
    "Rock" to listOf("rock", "punk", "grunge", "indie", "alternative", "shoegaze", "new wave"),
    "Soul & R'n'B" to listOf("r&b", "rnb", "r n b", "soul", "funk", "motown", "neo soul"),
    "Jazz" to listOf("jazz", "swing", "bebop", "bossa"),
    "Blues" to listOf("blues"),
    "Latino" to listOf("latin", "salsa", "reggaeton", "bachata", "cumbia", "merengue", "tango"),
    "Reggae" to listOf("reggae", "dancehall", "dub", "ska"),
    "Classique" to listOf("classical", "classique", "baroque", "symphon", "opera", "orchestr", "chamber"),
    "Musiques de film" to listOf("soundtrack", " ost ", "score", "film", "bande originale"),
    "Ambient & chill" to listOf("ambient", "chill", "lofi", "lo-fi", "downtempo", "new age"),
    "Folk" to listOf("folk", "country", "acoustic", "bluegrass"),
    "Chanson" to listOf("chanson", "variete", "french pop"),
    "Pop" to listOf("pop"),
)

/**
 * Range une étiquette de genre (« Hip-Hop/Rap », « Deep House », « Variété française »…) dans une famille.
 * Une étiquette inconnue garde son nom, avec une majuscule ; vide → null.
 */
fun genreFamily(tag: String?): String? {
    val raw = tag?.trim().orEmpty()
    if (raw.isEmpty() || raw.equals("<unknown>", ignoreCase = true) || raw.all { it.isDigit() || it == '(' || it == ')' }) return null
    val normalized = " " + normalizeForSearch(raw).replace(Regex("[/_,;]"), " ") + " "
    GenreFamilies.forEach { (family, keywords) ->
        if (keywords.any { normalized.contains(it) }) return family
    }
    return raw.lowercase().replaceFirstChar { it.titlecase() }
}

/** Morceaux regroupés par famille de genre, les plus fournies d'abord ; les inconnus à la fin. */
fun genreGroups(tracks: List<Track>, genreOf: (Track) -> String?): List<TrackGroup> {
    val byGenre = tracks.groupBy { genreOf(it) }
    val known = byGenre.filterKeys { it != null }.entries
        .sortedWith(compareByDescending<Map.Entry<String?, List<Track>>> { it.value.size }.thenBy { it.key })
        .map { (genre, items) -> TrackGroup("genre:$genre", genre!!, countLabel(items.size, "titre", "titres"), sortedByTitle(items)) }
    val unknown = byGenre[null]?.let { TrackGroup("genre:?", "Genre inconnu", "Ni dans le fichier, ni trouvé en ligne", sortedByTitle(it)) }
    return known + listOfNotNull(unknown)
}

/** Morceaux regroupés par humeur, dans l'ordre des humeurs ; ceux pas encore analysés à la fin. */
fun moodGroups(tracks: List<Track>, moodOf: (Track) -> Mood?): List<TrackGroup> {
    val byMood = tracks.groupBy { moodOf(it) }
    val known = Mood.entries.mapNotNull { mood ->
        byMood[mood]?.let { TrackGroup("mood:${mood.name}", mood.label, mood.description, sortedByTitle(it)) }
    }
    val pending = byMood[null]?.let { TrackGroup("mood:?", "Pas encore analysés", "L'analyse se fait en arrière-plan", sortedByTitle(it)) }
    return known + listOfNotNull(pending)
}
