package com.lecteur.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ShuffleOrdersTest {

    @Test
    fun `le titre en cours ouvre l'ordre mélangé et toute la file y est`() {
        repeat(50) { seed ->
            val order = shuffledFrom(count = 20, current = 7, random = Random(seed))
            assertEquals(7, order.first())
            assertEquals((0 until 20).toSet(), order.toSet())
            assertEquals(20, order.size)
        }
        assertEquals(0, shuffledFrom(0, 0, Random(1)).size)
    }

    @Test
    fun `lire ensuite passe juste après le titre en cours, ajouter à la file va à la fin`() {
        // Ordre actuel 2, 0, 3, 1 ; le lecteur a placé le nouveau titre 4 au hasard.
        val traversal = listOf(2, 4, 0, 3, 1)
        assertEquals(listOf(2, 0, 4, 3, 1), withInserted(traversal, inserted = 4, current = 0, afterCurrent = true).toList())
        assertEquals(listOf(2, 0, 3, 1, 4), withInserted(traversal, inserted = 4, current = 0, afterCurrent = false).toList())
    }

    @Test
    fun `détection d'un seul titre ajouté`() {
        assertEquals(1, singleInsertion(listOf("a", "b"), listOf("a", "x", "b")))
        assertEquals(2, singleInsertion(listOf("a", "b"), listOf("a", "b", "x")))
        assertNull(singleInsertion(listOf("a", "b"), listOf("x", "y", "z")))
        assertNull(singleInsertion(listOf("a", "b"), listOf("a", "b")))
    }
}
