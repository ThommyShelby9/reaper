package com.lecteur.player.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class EqBand(val centerHz: Int, val levelMb: Int)

data class EffectsState(
    /** Faux tant que le service de lecture n'a pas démarré, ou si l'appareil n'a pas d'égaliseur. */
    val available: Boolean = false,
    val output: OutputKind = OutputKind.Speaker,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minMb: Int = -1500,
    val maxMb: Int = 1500,
    val bassAvailable: Boolean = false,
    /** Renforcement des graves, 0..1. */
    val bass: Float = 0f,
) {
    val preset: EqPreset?
        get() = matchingPreset(bands.map { it.levelMb }, bands.map { it.centerHz }, minMb, maxMb)
}

/**
 * Égaliseur et renforcement des graves, appliqués à la session audio d'ExoPlayer.
 * L'app tourne dans un seul processus : le service l'attache à sa session, l'interface lit
 * [state] et appelle les réglages directement. Chaque sortie audio a ses propres réglages,
 * enregistrés et rechargés quand on branche ou débranche un casque.
 */
object AudioEffects {

    private val _state = MutableStateFlow(EffectsState())
    val state: StateFlow<EffectsState> = _state.asStateFlow()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var prefs: SharedPreferences? = null
    private var audioManager: AudioManager? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshOutput()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshOutput()
    }

    /** Appelé par le service de lecture, sur le thread principal. */
    fun attach(context: Context, audioSessionId: Int) {
        release()
        val app = context.applicationContext
        prefs = app.getSharedPreferences("effets_audio", Context.MODE_PRIVATE)
        audioManager = app.getSystemService(AudioManager::class.java)
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()?.takeIf { it.strengthSupported }

        val eq = equalizer
        _state.value = if (eq == null) {
            EffectsState(available = false, bassAvailable = bassBoost != null)
        } else {
            val range = eq.bandLevelRange
            EffectsState(
                available = true,
                minMb = range[0].toInt(),
                maxMb = range[1].toInt(),
                bands = (0 until eq.numberOfBands).map { EqBand(eq.getCenterFreq(it.toShort()) / 1000, 0) },
                bassAvailable = bassBoost != null,
            )
        }
        audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshOutput(force = true)
    }

    fun release() {
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        equalizer?.release()
        bassBoost?.release()
        equalizer = null
        bassBoost = null
        audioManager = null
        _state.update { it.copy(available = false, bassAvailable = false) }
    }

    fun setEnabled(enabled: Boolean) = change { it.copy(enabled = enabled) }

    fun setBandLevel(band: Int, levelMb: Int) = change { s ->
        s.copy(
            enabled = true,
            bands = s.bands.mapIndexed { i, b -> if (i == band) b.copy(levelMb = levelMb.coerceIn(s.minMb, s.maxMb)) else b },
        )
    }

    fun applyPreset(preset: EqPreset) = change { s ->
        val levels = presetLevelsMb(preset, s.bands.map { it.centerHz }, s.minMb, s.maxMb)
        s.copy(enabled = true, bands = s.bands.zip(levels) { b, level -> b.copy(levelMb = level) })
    }

    fun setBass(amount: Float) = change { it.copy(bass = amount.coerceIn(0f, 1f)) }

    private fun change(transform: (EffectsState) -> EffectsState) {
        _state.update(transform)
        apply()
        save()
    }

    private fun refreshOutput(force: Boolean = false) {
        val types = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type }.orEmpty()
        val output = outputKindOf(types)
        if (!force && output == _state.value.output) return
        load(output)
        apply()
    }

    private fun load(output: OutputKind) {
        val p = prefs ?: return
        _state.update { s ->
            val saved = decodeLevels(p.getString("${output.key}.bands", null), s.bands.size)
            s.copy(
                output = output,
                enabled = p.getBoolean("${output.key}.enabled", false),
                bands = s.bands.mapIndexed { i, b -> b.copy(levelMb = saved?.get(i) ?: 0) },
                bass = p.getFloat("${output.key}.bass", 0f),
            )
        }
    }

    private fun save() {
        val s = _state.value
        prefs?.edit()
            ?.putBoolean("${s.output.key}.enabled", s.enabled)
            ?.putString("${s.output.key}.bands", encodeLevels(s.bands.map { it.levelMb }))
            ?.putFloat("${s.output.key}.bass", s.bass)
            ?.apply()
    }

    private fun apply() {
        val s = _state.value
        equalizer?.let { eq ->
            runCatching {
                s.bands.forEachIndexed { i, band -> eq.setBandLevel(i.toShort(), band.levelMb.toShort()) }
                eq.enabled = s.enabled
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                bb.setStrength((s.bass * 1000).roundToInt().toShort())
                bb.enabled = s.bass > 0f
            }
        }
    }
}
