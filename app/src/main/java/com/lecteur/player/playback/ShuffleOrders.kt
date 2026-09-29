package com.lecteur.player.playback

import kotlin.random.Random

/*
 * Ordre de la lecture aléatoire.
 *
 * Le lecteur mélange toute la file sans tenir compte du titre en cours : celui-ci tombe n'importe où dans
 * l'ordre mélangé, et la lecture s'arrête après le dernier titre de cet ordre — souvent au bout de
 * quelques titres seulement. Ici, le titre en cours ouvre toujours l'ordre mélangé : toute la file est jouée.
 */

/** Ordre mélangé des [count] titres, qui commence par [current]. */
fun shuffledFrom(count: Int, current: Int, random: Random): IntArray {
    if (count <= 0) return IntArray(0)
    val start = current.coerceIn(0, count - 1)
    return intArrayOf(start) + (0 until count).filter { it != start }.shuffled(random)
}

/**
 * Un titre vient d'être ajouté à l'index [inserted] ; [traversal] est l'ordre mélangé actuel (qui le contient
 * déjà, placé au hasard par le lecteur). « Lire ensuite » ([afterCurrent]) le met juste après le titre en cours,
 * « Ajouter à la file » à la fin.
 */
fun withInserted(traversal: List<Int>, inserted: Int, current: Int, afterCurrent: Boolean): IntArray {
    val rest = traversal.filter { it != inserted }.toMutableList()
    val at = if (afterCurrent) rest.indexOf(current) + 1 else rest.size
    rest.add(at.coerceIn(0, rest.size), inserted)
    return rest.toIntArray()
}

/** Si [new] est [old] avec un seul titre ajouté, son index ; sinon null. */
fun singleInsertion(old: List<String>, new: List<String>): Int? {
    if (new.size != old.size + 1) return null
    val index = new.indices.firstOrNull { it >= old.size || new[it] != old[it] } ?: return null
    return index.takeIf { new.subList(0, index) + new.subList(index + 1, new.size) == old }
}
