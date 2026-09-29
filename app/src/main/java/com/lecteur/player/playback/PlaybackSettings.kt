package com.lecteur.player.playback

import android.content.Context
import android.content.SharedPreferences
import com.lecteur.player.audio.OutputKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackPrefs(
    /** Volume choisi par l'utilisateur (0..1), distinct du volume du lecteur qui porte aussi le fondu. */
    val volume: Float = 1f,
    /** Durée du fondu enchaîné, 0 = désactivé. */
    val crossfadeMs: Long = 0L,
    /** Afficher le lecteur en plein écran par-dessus l'écran de verrouillage pendant la lecture. */
    val lockScreenPlayer: Boolean = true,
    /** Retard de chaque sortie audio (clé : [com.lecteur.player.audio.OutputKind.key]), en ms, mesuré par calibrage. */
    val latencyMs: Map<String, Long> = emptyMap(),
    /** Télécharger les pochettes manquantes (MusicBrainz / Cover Art Archive). */
    val onlineCovers: Boolean = true,
    /** Télécharger les paroles manquantes (LRCLIB). */
    val onlineLyrics: Boolean = true,
    /** Ne rien télécharger sur les données mobiles. */
    val onlineWifiOnly: Boolean = true,
    /** Thème de l'app : celui du téléphone, ou forcé en clair (Appareil) ou en sombre (Nuit). */
    val themeMode: ThemeMode = ThemeMode.Auto,
    /** L'utilisateur a fermé la carte d'aide sur l'écran de verrouillage. */
    val lockScreenHintDismissed: Boolean = false,
    /** Après une capture d'écran dans Reaper, proposer une carte à partager. */
    val shareOnScreenshot: Boolean = true,
) {
    fun latencyFor(output: OutputKind): Long = latencyMs[output.key] ?: 0L
}

/**
 * Réglages de lecture partagés par l'interface et le service (même processus), enregistrés sur le téléphone.
 * Le service les applique au lecteur ; l'interface les modifie.
 */
object PlaybackSettings {

    const val MAX_CROSSFADE_MS = 12_000L

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(PlaybackPrefs())
    val state: StateFlow<PlaybackPrefs> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("lecture", Context.MODE_PRIVATE)
        prefs = p
        _state.value = PlaybackPrefs(
            volume = p.getFloat("volume", 1f),
            crossfadeMs = p.getLong("fondu_ms", 0L),
            lockScreenPlayer = p.getBoolean("ecran_verrouillage", true),
            themeMode = ThemeMode.entries.firstOrNull { it.name == p.getString("theme", null) } ?: ThemeMode.Auto,
            lockScreenHintDismissed = p.getBoolean("ecran_verrouillage.aide_fermee", false),
            shareOnScreenshot = p.getBoolean("partage.capture", true),
            latencyMs = OutputKind.entries.associate { it.key to p.getLong("latence.${it.key}", 0L) },
            onlineCovers = p.getBoolean("en_ligne.pochettes", true),
            onlineLyrics = p.getBoolean("en_ligne.paroles", true),
            onlineWifiOnly = p.getBoolean("en_ligne.wifi", true),
        )
    }

    const val MAX_LATENCY_MS = 500L

    fun setLatency(output: OutputKind, ms: Long) {
        val value = ms.coerceIn(0L, MAX_LATENCY_MS)
        _state.update { it.copy(latencyMs = it.latencyMs + (output.key to value)) }
        prefs?.edit()?.putLong("latence.${output.key}", value)?.apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        prefs?.edit()?.putString("theme", mode.name)?.apply()
    }

    fun setShareOnScreenshot(enabled: Boolean) {
        _state.update { it.copy(shareOnScreenshot = enabled) }
        prefs?.edit()?.putBoolean("partage.capture", enabled)?.apply()
    }

    fun dismissLockScreenHint() {
        _state.update { it.copy(lockScreenHintDismissed = true) }
        prefs?.edit()?.putBoolean("ecran_verrouillage.aide_fermee", true)?.apply()
    }

    fun setLockScreenPlayer(enabled: Boolean) {
        _state.update { it.copy(lockScreenPlayer = enabled) }
        prefs?.edit()?.putBoolean("ecran_verrouillage", enabled)?.apply()
    }

    fun setVolume(volume: Float) {
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        prefs?.edit()?.putFloat("volume", _state.value.volume)?.apply()
    }

    /** Téléchargements automatiques ; un paramètre null reste inchangé. */
    fun setOnline(covers: Boolean? = null, lyrics: Boolean? = null, wifiOnly: Boolean? = null) {
        _state.update {
            it.copy(
                onlineCovers = covers ?: it.onlineCovers,
                onlineLyrics = lyrics ?: it.onlineLyrics,
                onlineWifiOnly = wifiOnly ?: it.onlineWifiOnly,
            )
        }
        val s = _state.value
        prefs?.edit()
            ?.putBoolean("en_ligne.pochettes", s.onlineCovers)
            ?.putBoolean("en_ligne.paroles", s.onlineLyrics)
            ?.putBoolean("en_ligne.wifi", s.onlineWifiOnly)
            ?.apply()
    }

    fun setCrossfadeMs(ms: Long) {
        _state.update { it.copy(crossfadeMs = ms.coerceIn(0L, MAX_CROSSFADE_MS)) }
        prefs?.edit()?.putLong("fondu_ms", _state.value.crossfadeMs)?.apply()
    }
}

enum class ThemeMode(val label: String) {
    Auto("Automatique"),
    Light("Appareil"),
    Dark("Nuit"),
}
