package com.lecteur.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {

    @Test
    fun `lit les horodatages aux formats courants`() {
        val lyrics = parseLrc(
            """
            [ti:Minuit sur le quai]
            [ar:Lune Basse]
            [00:01.5]un dixième
            [00:02.25]centièmes
            [00:03.125]millièmes
            [01:04]sans fraction
            """.trimIndent(),
        )!!
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_500L, 2_250L, 3_125L, 64_000L), lyrics.lines.map { it.timeMs })
        assertEquals("un dixième", lyrics.lines.first().text)
    }

    @Test
    fun `une ligne à plusieurs horodatages est répétée et le tout trié`() {
        val lyrics = parseLrc("[00:20.00]refrain\n[00:05.00][00:30.00]couplet")!!
        assertEquals(listOf(5_000L to "couplet", 20_000L to "refrain", 30_000L to "couplet"), lyrics.lines.map { it.timeMs to it.text })
    }

    @Test
    fun `un décalage positif avance les paroles`() {
        val lyrics = parseLrc("[offset:+500]\n[00:02.00]a\n[00:00.20]b")!!
        assertEquals(listOf(0L, 1_500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `les balises de mots sont retirées`() {
        val lyrics = parseLrc("[00:01.00]<00:01.00>les <00:01.40>néons")!!
        assertEquals("les néons", lyrics.lines.single().text)
    }

    @Test
    fun `un texte sans horodatage donne des paroles non synchronisées`() {
        val lyrics = parseLrc("\n\nOn a laissé la ville\n\ntourner sans nous\n\n")!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("On a laissé la ville", "", "tourner sans nous"), lyrics.lines.map { it.text })
    }

    @Test
    fun `un texte vide ou fait d'étiquettes ne donne rien`() {
        assertNull(parseLrc("   \n"))
        assertNull(parseLrc("[ar:Lune Basse]\n[ti:Titre]"))
    }

    @Test
    fun `la marque BOM est ignorée`() {
        assertEquals(1_000L, parseLrc(0xFEFF.toChar() + "[00:01.00]a")!!.lines.single().timeMs)
    }

    @Test
    fun `decodeLrcBytes lit l'UTF-8, l'UTF-16 et se replie sur Windows-1252`() {
        assertEquals("[00:01.00]été", decodeLrcBytes("[00:01.00]été".toByteArray(Charsets.UTF_8)))
        assertEquals("été", decodeLrcBytes("été".toByteArray(Charsets.UTF_16)))
        assertEquals("été", decodeLrcBytes("été".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun `currentLineIndex trouve la dernière ligne commencée`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"), LyricLine(3_000, "c"))
        assertEquals(-1, currentLineIndex(lines, 500))
        assertEquals(0, currentLineIndex(lines, 1_000))
        assertEquals(1, currentLineIndex(lines, 2_999))
        assertEquals(2, currentLineIndex(lines, 60_000))
        assertEquals(-1, currentLineIndex(emptyList(), 1_000))
    }

    @Test
    fun `decodeUslt lit l'UTF-8 et le Latin-1`() {
        val utf8 = byteArrayOf(3, 'f'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte()) +
            "desc".toByteArray() + byteArrayOf(0) + "Été".toByteArray(Charsets.UTF_8)
        assertEquals("Été", decodeUslt(utf8))
        val latin1 = byteArrayOf(0, 'f'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 0) + "été".toByteArray(Charsets.ISO_8859_1)
        assertEquals("été", decodeUslt(latin1))
    }

    @Test
    fun `decodeUslt lit l'UTF-16 avec BOM`() {
        val data = byteArrayOf(1, 'f'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 0, 0) + "quai".toByteArray(Charsets.UTF_16)
        assertEquals("quai", decodeUslt(data))
    }

    @Test
    fun `decodeUslt refuse une trame trop courte`() {
        assertNull(decodeUslt(byteArrayOf(3, 1)))
    }

    @Test
    fun `lrcKeysFor propose le nom du fichier puis artiste - titre`() {
        assertEquals(
            listOf("03 minuit sur le quai", "lune basse - minuit sur le quai", "minuit sur le quai"),
            lrcKeysFor("03 Minuit sur le quai.flac", "Lune Basse", "Minuit sur le quai"),
        )
    }

    @Test
    fun `bestLrcMatch préfère le dossier le plus proche`() {
        val candidates = listOf("Paroles", "Music/Lune Basse/Quai Nord", "Music/Autre/Quai Nord")
        assertEquals(1, bestLrcMatch(candidates, "Music/Lune Basse/Quai Nord"))
        assertEquals(-1, bestLrcMatch(emptyList(), "Music"))
    }

    @Test
    fun `treeLabel donne le nom du dossier choisi`() {
        assertEquals("Music", treeLabel("primary:Music"))
        assertEquals("Stockage principal", treeLabel("primary:"))
    }

    @Test
    fun `ligne en cours pour l'affichage une phrase à la fois`() {
        val lyrics = Lyrics(true, listOf(LyricLine(1_000, "Première"), LyricLine(5_000, "  "), LyricLine(9_000, " Troisième ")))
        assertEquals(null, currentLyricLine(lyrics, 500))
        assertEquals("Première", currentLyricLine(lyrics, 4_999))
        // Passage instrumental : rien à afficher.
        assertEquals(null, currentLyricLine(lyrics, 6_000))
        assertEquals("Troisième", currentLyricLine(lyrics, 60_000))
        assertEquals(null, currentLyricLine(Lyrics(false, listOf(LyricLine(-1, "texte"))), 60_000))
        assertEquals(null, currentLyricLine(null, 0))
    }
}
