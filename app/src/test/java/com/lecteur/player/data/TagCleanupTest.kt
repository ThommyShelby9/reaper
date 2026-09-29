package com.lecteur.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagCleanupTest {

    @Test
    fun `les adresses de sites disparaissent des artistes et albums`() {
        assertEquals("Damso", cleanName("Damso - Www.33rapfr.com"))
        assertEquals("Orelsan", cleanName("Orelsan - Www.33rapzip.com"))
        assertEquals("Ipséité", cleanName("Ipséité (www.site-musique.net)"))
        assertEquals("", cleanName("Www.33rapfr.com"))
        assertEquals("Billie Eilish, Vince Staples", cleanName("Billie Eilish, Vince Staples"))
    }

    @Test
    fun `numéros de piste, tirets de nom de fichier et marques de copie`() {
        assertEquals("La quête", cleanTitle("02-La-quête"))
        assertEquals("P. DOSE", cleanTitle("03-P.-DOSE"))
        assertEquals("Π. VANTABLACK", cleanTitle("02-Π.-VANTABLACK (1)"))
        assertEquals("Ο. OG", cleanTitle("01-Ο.-OG (1)"))
        assertEquals("Titre", cleanTitle("Titre - Copie"))
    }

    @Test
    fun `noms de vidéos téléchargées`() {
        assertEquals("mad over you runtown m", cleanTitle("(1)mad_over_you_official_music_video_runtown_m"))
        assertEquals("Blue Bird", cleanTitle("Blue Bird (Official Music Video)"))
        assertEquals("Blue Bird", cleanTitle("Blue Bird [HD]"))
        assertEquals("\"SANS COMMENTAIRE\"", cleanTitle("\"SANS COMMENTAIRE\" (OFFICIAL HD)"))
        assertEquals("Fran Vasilić - It's Been a Fun Ride", cleanTitle("🚘Fran Vasilić - It's Been a Fun Ride"))
    }

    @Test
    fun `les vrais titres restent intacts`() {
        assertEquals("!!!!!!!", cleanTitle("!!!!!!!"))
        assertEquals("&burn (feat. Vince Staples)", cleanTitle("&burn (feat. Vince Staples)"))
        assertEquals("#ImSippinTeaInYoHood", cleanTitle("#ImSippinTeaInYoHood"))
        assertEquals("\$\$\$", cleanTitle("\$\$\$"))
        assertEquals("+ de pluie...", cleanTitle("+ de pluie..."))
        assertEquals("#1", cleanTitle("#1"))
        assertEquals("Jay-Z", cleanTitle("Jay-Z"))
        assertEquals("[FULL] Naruto Shippuden OP 3", cleanTitle("[FULL] Naruto Shippuden OP 3"))
    }

    @Test
    fun `artiste inconnu et titre « Artiste - Titre » sont séparés`() {
        val tags = cleanTags("🚘Fran Vasilić - It's Been a Fun Ride", UNKNOWN_ARTIST, UNKNOWN_ALBUM, "fichier")
        assertEquals("Fran Vasilić", tags.artist)
        assertEquals("It's Been a Fun Ride", tags.title)
        // Artiste connu : on ne touche pas au titre.
        assertEquals("A - B", cleanTags("A - B", "Kaelo", "Néons", "f").title)
    }

    @Test
    fun `un artiste réduit à une adresse devient inconnu, un titre vide reprend le nom du fichier`() {
        assertEquals(UNKNOWN_ARTIST, cleanTags("Titre", "www.33rapfr.com", "Album", "f").artist)
        assertEquals("mon morceau", cleanTags("www.site.com", "A", "B", "mon_morceau").title)
    }
}
