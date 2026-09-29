package com.lecteur.player.share

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Captures d'écran prises pendant que Reaper est affiché (signalées par `MainActivity`, Android 14+). */
object ScreenshotSignal {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events

    fun notifyCaptured() {
        _events.tryEmit(Unit)
    }
}
