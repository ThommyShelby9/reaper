package com.lecteur.player.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Dans le service : garde un ordre aléatoire qui part du titre en cours, pour que toute la file soit jouée
 * (voir [shuffledFrom]). Remélange à l'activation de l'aléatoire et quand la file est remplacée ;
 * un titre ajouté va juste après le titre en cours (« Lire ensuite ») ou à la fin (« Ajouter à la file »).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ShuffleKeeper(private val player: ExoPlayer) : Player.Listener {

    private var lastIds: List<String> = ids()

    init {
        player.addListener(this)
    }

    fun release() {
        player.removeListener(this)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled) reshuffle()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val newIds = ids()
        val oldIds = lastIds
        lastIds = newIds
        // Nos propres changements d'ordre ne modifient pas la file : rien à faire.
        if (!player.shuffleModeEnabled || newIds == oldIds || newIds.isEmpty()) return
        val inserted = singleInsertion(oldIds, newIds)
        when {
            inserted != null -> {
                val current = player.currentMediaItemIndex
                apply(withInserted(traversal(timeline), inserted, current, afterCurrent = inserted == current + 1))
            }
            // Titres retirés : le lecteur garde l'ordre des autres.
            newIds.size < oldIds.size && oldIds.containsAll(newIds) -> Unit
            // Nouvelle file (une liste lancée en aléatoire, un mix…) : on part du titre choisi.
            else -> reshuffle()
        }
    }

    private fun reshuffle() {
        val count = player.mediaItemCount
        if (count > 1) apply(shuffledFrom(count, player.currentMediaItemIndex, Random.Default))
    }

    private fun apply(order: IntArray) {
        if (order.size != player.mediaItemCount) return
        if (order.toList() == traversal(player.currentTimeline)) return
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, Random.nextLong()))
    }

    /** Ordre de lecture aléatoire actuel, du premier au dernier titre. */
    private fun traversal(timeline: Timeline): List<Int> {
        if (timeline.isEmpty) return emptyList()
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size <= timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    private fun ids(): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
}
