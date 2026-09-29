package com.lecteur.player.audio

import android.media.AudioDeviceInfo
import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

/** La sortie audio active : chaque sortie garde ses propres réglages d'égaliseur. */
enum class OutputKind(val label: String, val key: String) {
    Speaker("Haut-parleur", "speaker"),
    Wired("Casque filaire", "wired"),
    Bluetooth("Bluetooth", "bluetooth"),
}

private val BluetoothTypes = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    26, // AudioDeviceInfo.TYPE_BLE_HEADSET (Android 12)
    27, // AudioDeviceInfo.TYPE_BLE_SPEAKER (Android 12)
)
private val WiredTypes = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

/** Android envoie le son vers le Bluetooth en priorité, puis vers un casque branché. */
fun outputKindOf(connectedTypes: Collection<Int>): OutputKind = when {
    connectedTypes.any { it in BluetoothTypes } -> OutputKind.Bluetooth
    connectedTypes.any { it in WiredTypes } -> OutputKind.Wired
    else -> OutputKind.Speaker
}

/** Réglages prédéfinis, décrits comme une courbe de gain (Hz → dB) indépendante de l'appareil. */
enum class EqPreset(val label: String, val points: List<Pair<Int, Float>>) {
    Flat("Plat", listOf(20 to 0f, 20_000 to 0f)),
    Bass("Grave", listOf(60 to 6f, 150 to 4f, 400 to 0f, 20_000 to 0f)),
    Voice("Voix", listOf(60 to -2f, 250 to -1f, 1_000 to 2f, 3_000 to 3f, 8_000 to 0f, 20_000 to 0f)),
    Treble("Aigus", listOf(20 to 0f, 2_000 to 0f, 6_000 to 3f, 14_000 to 4f)),
    Loudness("Loudness", listOf(60 to 5f, 250 to 2f, 1_000 to 0f, 4_000 to 1f, 14_000 to 4f)),
}

/** Gain de la courbe à [hz], interpolé en échelle logarithmique (une octave = un pas). */
fun curveGain(points: List<Pair<Int, Float>>, hz: Int): Float {
    require(points.isNotEmpty()) { "points ne doit pas être vide" }
    if (hz <= points.first().first) return points.first().second
    if (hz >= points.last().first) return points.last().second
    val upper = points.indexOfFirst { it.first >= hz }
    val (f0, g0) = points[upper - 1]
    val (f1, g1) = points[upper]
    val t = (log2(hz.toFloat()) - log2(f0.toFloat())) / (log2(f1.toFloat()) - log2(f0.toFloat()))
    return g0 + (g1 - g0) * t
}

/** Niveaux (en millibels) d'un réglage prédéfini pour les bandes de l'appareil, bornés à sa plage. */
fun presetLevelsMb(preset: EqPreset, centerHz: List<Int>, minMb: Int, maxMb: Int): List<Int> =
    centerHz.map { hz -> (curveGain(preset.points, hz) * 100).roundToInt().coerceIn(minMb, maxMb) }

/** Le réglage prédéfini qui correspond exactement à [levelsMb], s'il y en a un. */
fun matchingPreset(levelsMb: List<Int>, centerHz: List<Int>, minMb: Int, maxMb: Int): EqPreset? =
    EqPreset.entries.firstOrNull { presetLevelsMb(it, centerHz, minMb, maxMb) == levelsMb }

/** « 60 », « 910 », « 3,6k », « 14k ». */
fun formatFrequency(hz: Int): String = when {
    hz < 1_000 -> hz.toString()
    hz < 10_000 -> String.format(Locale.FRANCE, "%.1fk", hz / 1000f).replace(",0k", "k")
    else -> "${(hz / 1000f).roundToInt()}k"
}

/** « +3 dB », « 0 dB », « −2,5 dB ». */
fun formatGain(mb: Int): String {
    val db = mb / 100f
    val text = if (db == db.roundToInt().toFloat()) db.roundToInt().toString() else String.format(Locale.FRANCE, "%.1f", db)
    return when {
        mb > 0 -> "+$text dB"
        mb < 0 -> "−${text.removePrefix("-")} dB"
        else -> "0 dB"
    }
}

fun encodeLevels(levelsMb: List<Int>): String = levelsMb.joinToString(",")

/** Relit des niveaux enregistrés ; null si le format ou le nombre de bandes ne correspond plus. */
fun decodeLevels(text: String?, bandCount: Int): List<Int>? {
    if (text.isNullOrBlank()) return null
    val values = text.split(',').map { it.trim().toIntOrNull() ?: return null }
    return values.takeIf { it.size == bandCount }
}
