package com.lecteur.player.ui.lockscreen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lecteur.player.R

/** Ce qu'il manque pour afficher le lecteur sur l'écran de verrouillage, selon la version d'Android. */
enum class LockScreenRequirement(val title: String, val explanation: String) {
    Overlay(
        "Affichage par-dessus les autres applis",
        "Android ne laisse une app s'afficher sur l'écran de verrouillage que si vous l'autorisez à « s'afficher par-dessus les autres applis ».",
    ),
    Notifications(
        "Notifications",
        "Sur votre version d'Android, le lecteur plein écran passe par une notification. Autorisez les notifications de Reaper.",
    ),
    FullScreen(
        "Notifications en plein écran",
        "Autorisez Reaper à « afficher des notifications en plein écran » : c'est ce qui lui permet d'apparaître sur l'écran de verrouillage.",
    ),
    Xiaomi(
        "Autorisations Xiaomi",
        "Sur les téléphones Xiaomi, Redmi et POCO, ouvrez « Autres autorisations » et activez « Afficher sur l'écran de verrouillage » et « Afficher des fenêtres pop-up lorsque l'application s'exécute en arrière-plan ».",
    ),
}

/** Téléphones Xiaomi, Redmi et POCO (MIUI / HyperOS), qui ajoutent leurs propres autorisations. */
val isXiaomi: Boolean
    get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

/**
 * Autorisations propres à Xiaomi : « afficher sur l'écran de verrouillage » (10020) et « pop-up en
 * arrière-plan » (10021). Vrai si toutes deux sont accordées, faux si l'une est refusée, null si le
 * système ne permet pas de le savoir (API non publique, lue avec précaution).
 */
fun xiaomiLockScreenAllowed(context: Context): Boolean? {
    if (!isXiaomi) return true
    return runCatching {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        val check = android.app.AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        listOf(10_020, 10_021).all { op ->
            check.invoke(appOps, op, android.os.Process.myUid(), context.packageName) as Int == android.app.AppOpsManager.MODE_ALLOWED
        }
    }.getOrNull()
}

/** Page « Autorisations » de l'app dans la sécurité Xiaomi ; à défaut, la fiche de l'app d'Android. */
fun xiaomiPermissionsIntent(context: Context): Intent =
    Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        .putExtra("extra_pkgname", context.packageName)
        .takeIf { it.resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/**
 * Ouvre le lecteur plein écran par-dessus le verrouillage.
 *
 * Jusqu'à Android 14, une app autorisée à s'afficher par-dessus les autres peut ouvrir un écran depuis l'arrière-plan.
 * Depuis Android 15, cette exception exige une fenêtre déjà visible : on passe alors par une notification
 * « plein écran », le mécanisme prévu par Android pour les écrans de réveil et d'appel.
 */
object LockScreenLauncher {

    private const val TAG = "ReaperVerrou"
    private const val CHANNEL_ID = "ecran_verrouillage"
    private const val NOTIFICATION_ID = 4_201

    private val usesFullScreenIntent get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    fun missingRequirements(context: Context): List<LockScreenRequirement> = buildList {
        if (!usesFullScreenIntent) {
            if (!Settings.canDrawOverlays(context)) add(LockScreenRequirement.Overlay)
        } else {
            val notificationsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!notificationsGranted) add(LockScreenRequirement.Notifications)
            if (!context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) add(LockScreenRequirement.FullScreen)
        }
        // Refus certain seulement : si le système ne dit rien, les réglages l'expliquent sans bloquer.
        if (xiaomiLockScreenAllowed(context) == false) add(LockScreenRequirement.Xiaomi)
    }

    /** Écran des réglages d'Android où l'utilisateur accorde [requirement] (null : demande directe dans l'app). */
    fun settingsIntent(context: Context, requirement: LockScreenRequirement): Intent? {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (requirement) {
            LockScreenRequirement.Overlay -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            LockScreenRequirement.Notifications -> null
            LockScreenRequirement.FullScreen ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
                } else {
                    null
                }
            LockScreenRequirement.Xiaomi -> xiaomiPermissionsIntent(context)
        }
    }

    fun launch(context: Context) {
        val missing = missingRequirements(context)
        android.util.Log.d(TAG, "Écran éteint pendant la lecture, autorisations manquantes : $missing")
        // Les autorisations Xiaomi ne se lisent pas de façon fiable (non publiques) : on tente quand même.
        if (missing.any { it != LockScreenRequirement.Xiaomi }) return
        val intent = Intent(context, LockScreenActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        if (!usesFullScreenIntent || isXiaomi) {
            // HyperOS laisse une app ouvrir un écran depuis l'arrière-plan si « pop-up en arrière-plan » est accordé.
            runCatching { context.startActivity(intent) }
                .onFailure { android.util.Log.w(TAG, "Ouverture directe refusée", it) }
            if (!usesFullScreenIntent) return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lecteur sur l'écran de verrouillage", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ouvre le lecteur plein écran quand l'écran s'éteint pendant la lecture."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Reaper")
            .setContentText("Lecture en cours")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { android.util.Log.w(TAG, "Notification plein écran refusée", it) }
    }

    /** L'écran est ouvert : la notification qui l'a lancé n'a plus de raison d'être. */
    fun dismissNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
