package com.lecteur.player.ui.settings

import android.Manifest
import android.os.Build
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.account.AccountUser
import com.lecteur.player.audio.AudioEffects
import com.lecteur.player.audio.OutputKind
import com.lecteur.player.analysis.LibraryProgress
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.ui.AccountUiState
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.lockscreen.LockScreenLauncher
import com.lecteur.player.ui.lockscreen.LockScreenRequirement
import com.lecteur.player.ui.lockscreen.isXiaomi
import com.lecteur.player.ui.lockscreen.xiaomiLockScreenAllowed
import com.lecteur.player.playback.ThemeMode
import com.lecteur.player.ui.theme.LecteurTheme

@Composable
fun SettingsRoute(viewModel: LecteurViewModel, onClose: () -> Unit) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val playback by PlaybackSettings.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    // Les autorisations se donnent souvent dans les réglages d'Android : on les revérifie au retour dans l'app.
    var missing by remember { mutableStateOf(LockScreenLauncher.missingRequirements(context)) }
    // Xiaomi ne dit pas toujours si ses autorisations sont accordées : on guide alors sans bloquer.
    var xiaomiUnknown by remember { mutableStateOf(isXiaomi && xiaomiLockScreenAllowed(context) == null) }
    LifecycleResumeEffect(Unit) {
        missing = LockScreenLauncher.missingRequirements(context)
        xiaomiUnknown = isXiaomi && xiaomiLockScreenAllowed(context) == null
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        missing = LockScreenLauncher.missingRequirements(context)
    }
    fun grant(requirement: LockScreenRequirement) {
        val intent = LockScreenLauncher.settingsIntent(context, requirement)
        if (intent != null) {
            context.startActivity(intent)
        } else if (requirement == LockScreenRequirement.Notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val effects by AudioEffects.state.collectAsStateWithLifecycle()
    var calibrating by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (calibrating) calibrating = false else onClose() }
    if (calibrating) {
        LatencyCalibration(
            output = effects.output,
            onSave = { ms ->
                PlaybackSettings.setLatency(effects.output, ms)
                calibrating = false
            },
            onClose = { calibrating = false },
        )
        return
    }
    SettingsScreen(
        account = account,
        output = effects.output,
        latencyMs = playback.latencyFor(effects.output),
        onLatencyChange = { PlaybackSettings.setLatency(effects.output, it) },
        onCalibrate = { calibrating = true },
        onlineCovers = playback.onlineCovers,
        onlineLyrics = playback.onlineLyrics,
        onlineWifiOnly = playback.onlineWifiOnly,
        onOnlineChange = { covers, lyrics, wifi -> PlaybackSettings.setOnline(covers, lyrics, wifi) },
        analysis = viewModel.analysisProgress.collectAsStateWithLifecycle().value,
        online = viewModel.online.collectAsStateWithLifecycle().value,
        lockScreenEnabled = playback.lockScreenPlayer,
        missing = missing,
        onSignIn = { activity?.let(viewModel::signIn) },
        onSignOut = viewModel::signOut,
        onLockScreenChange = { enabled ->
            PlaybackSettings.setLockScreenPlayer(enabled)
            if (enabled) missing.firstOrNull()?.let(::grant)
        },
        onGrant = ::grant,
        onClose = onClose,
        themeMode = playback.themeMode,
        onThemeChange = PlaybackSettings::setThemeMode,
        xiaomiUnknown = xiaomiUnknown,
        shareOnScreenshot = playback.shareOnScreenshot,
        onShareOnScreenshotChange = PlaybackSettings::setShareOnScreenshot,
        rescanRunning = viewModel.onlineRescanRunning.collectAsStateWithLifecycle().value,
        onRescanOnline = viewModel::rescanOnline,
    )
}

@Composable
fun SettingsScreen(
    account: AccountUiState,
    output: OutputKind,
    latencyMs: Long,
    onLatencyChange: (Long) -> Unit,
    onCalibrate: () -> Unit,
    onlineCovers: Boolean,
    onlineLyrics: Boolean,
    onlineWifiOnly: Boolean,
    onOnlineChange: (covers: Boolean?, lyrics: Boolean?, wifiOnly: Boolean?) -> Unit,
    analysis: LibraryProgress?,
    online: Boolean,
    lockScreenEnabled: Boolean,
    missing: List<LockScreenRequirement>,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onLockScreenChange: (Boolean) -> Unit,
    onGrant: (LockScreenRequirement) -> Unit,
    onClose: () -> Unit,
    themeMode: ThemeMode = ThemeMode.Auto,
    onThemeChange: (ThemeMode) -> Unit = {},
    xiaomiUnknown: Boolean = false,
    shareOnScreenshot: Boolean = true,
    onShareOnScreenshotChange: (Boolean) -> Unit = {},
    rescanRunning: Boolean = false,
    onRescanOnline: () -> Unit = {},
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = onClose, contentDescription = "Revenir à la bibliothèque", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("Réglages", style = type.displayLarge, color = colors.ink)
        }

        Section("Apparence")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                ToggleKey(mode.label, themeMode == mode, Modifier.weight(1f)) { onThemeChange(mode) }
            }
        }
        Text(
            when (themeMode) {
                ThemeMode.Auto -> "Reaper suit le téléphone : Appareil quand il est en clair, Nuit quand il est en sombre."
                ThemeMode.Light -> "Appareil : boîtier clair, écran sombre, touches en relief."
                ThemeMode.Dark -> "Nuit : tout en sombre, l'orange reste la seule lumière."
            },
            style = type.label,
            color = colors.inkMuted,
            modifier = Modifier.padding(4.dp),
        )

        Section("Compte")
        Panel {
            val user = account.user
            if (user == null) {
                Text("Non connecté", style = type.displayTitle, color = colors.displayInk)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Un compte sert à lancer une jam ou à en rejoindre une. Votre musique reste sur le téléphone : Reaper n'utilise que le nom et la photo de votre compte Google, visibles des participants de vos jams.",
                    style = type.body,
                    color = colors.displayMuted,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot()
                    Spacer(Modifier.width(8.dp))
                    Text("Connecté", style = type.label, color = colors.displayMuted)
                }
                Spacer(Modifier.height(6.dp))
                Text(user.displayName, style = type.displayTitle, color = colors.displayInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                user.email?.let { Text(it, style = type.label, color = colors.displayMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        account.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = type.body, color = colors.ink, modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(12.dp))
        when {
            account.busy -> Text("Connexion en cours…", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
            account.user == null -> HardwareKey(
                onClick = onSignIn,
                contentDescription = "Se connecter avec Google",
                modifier = Modifier.fillMaxWidth(),
                style = KeyStyle.Accent,
            ) { Text("Se connecter avec Google", style = type.bodyStrong) }
            else -> HardwareKey(onClick = onSignOut, contentDescription = "Se déconnecter", modifier = Modifier.fillMaxWidth()) {
                Text("Se déconnecter", style = type.bodyStrong)
            }
        }

        Section("Retard de sortie")
        Text(
            "Pour la jam : un casque Bluetooth ajoute souvent 100 à 300 ms de retard. Reaper en tient compte pour que tout le monde entende en même temps. Chaque sortie garde son réglage.",
            style = type.body,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(output.label, style = type.label, color = colors.inkMuted)
                Text("$latencyMs ms", style = type.displayTitle, color = colors.ink)
            }
            HardwareKey(onClick = { onLatencyChange(latencyMs - 10) }, contentDescription = "Diminuer de 10 millisecondes", modifier = Modifier.size(width = 56.dp, height = 44.dp)) {
                Text("−10", style = type.label.copy(fontSize = 12.sp))
            }
            Spacer(Modifier.width(6.dp))
            HardwareKey(onClick = { onLatencyChange(latencyMs + 10) }, contentDescription = "Augmenter de 10 millisecondes", modifier = Modifier.size(width = 56.dp, height = 44.dp)) {
                Text("+10", style = type.label.copy(fontSize = 12.sp))
            }
        }
        Spacer(Modifier.height(8.dp))
        HardwareKey(onClick = onCalibrate, contentDescription = "Calibrer la sortie ${output.label}", modifier = Modifier.fillMaxWidth()) {
            Text("Calibrer en tapant en rythme", style = type.bodyStrong)
        }

        Section("Analyse et téléchargements")
        Text(
            analysis?.let { "Analyse de la bibliothèque en cours : ${it.analyzed} sur ${it.total} morceaux. Tempo, tonalité et humeur se calculent sur le téléphone, sans internet." }
                ?: "Tempo, tonalité et humeur de chaque morceau se calculent sur le téléphone, sans internet, en arrière-plan quand la batterie le permet.",
            style = type.body,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleKey("Pochettes", onlineCovers, Modifier.weight(1f)) { onOnlineChange(!onlineCovers, null, null) }
            ToggleKey("Paroles", onlineLyrics, Modifier.weight(1f)) { onOnlineChange(null, !onlineLyrics, null) }
            ToggleKey("Wi-Fi seul", onlineWifiOnly, Modifier.weight(1f)) { onOnlineChange(null, null, !onlineWifiOnly) }
        }
        Text(
            "Les pochettes (MusicBrainz, Cover Art Archive) et les paroles (LRCLIB) manquantes sont cherchées en ligne puis gardées sur le téléphone : elles restent disponibles hors ligne. Ces services reçoivent seulement l'artiste, le titre et l'album cherchés." +
                if (!online) " Vous êtes hors ligne : les recherches reprendront au retour du réseau." else "",
            style = type.label,
            color = colors.inkMuted,
            modifier = Modifier.padding(4.dp),
        )
        Spacer(Modifier.height(4.dp))
        HardwareKey(
            onClick = onRescanOnline,
            contentDescription = "Relancer la recherche des pochettes et paroles manquantes",
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (rescanRunning) "Recherche en cours…" else "Relancer la recherche", style = type.bodyStrong) }
        Text(
            if (rescanRunning) {
                "Reaper cherche à nouveau les pochettes et paroles manquantes, même pour les titres déjà tentés. Vous pouvez quitter cet écran."
            } else {
                "Cherche à nouveau les pochettes et paroles manquantes de toute la bibliothèque, même pour les titres déjà tentés. Pour un seul titre, utilisez « Chercher » dans l'écran des paroles."
            },
            style = type.label,
            color = colors.inkMuted,
            modifier = Modifier.padding(4.dp),
        )

        Section("Partage")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
                Text("Carte après une capture", style = type.bodyStrong, color = colors.ink)
                Text(
                    "Après une capture d'écran de Reaper, proposer une carte du morceau (pochette, paroles) à envoyer en statut WhatsApp ou ailleurs. Rien n'est partagé sans votre accord.",
                    style = type.body,
                    color = colors.inkMuted,
                )
            }
            HardwareKey(
                onClick = { onShareOnScreenshotChange(!shareOnScreenshot) },
                contentDescription = if (shareOnScreenshot) "Ne plus proposer de carte après une capture" else "Proposer une carte après une capture",
                modifier = Modifier.size(width = 76.dp, height = 44.dp),
                style = if (shareOnScreenshot) KeyStyle.Accent else KeyStyle.Standard,
            ) { Text(if (shareOnScreenshot) "Activé" else "Désactivé", style = type.label.copy(fontSize = 11.sp)) }
        }

        Section("Écran de verrouillage")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
                Text("Lecteur plein écran", style = type.bodyStrong, color = colors.ink)
                Text(
                    "Quand l'écran s'éteint pendant la lecture, le lecteur s'affiche en grand par-dessus l'écran de verrouillage : horloge, morceau, commandes. Glissez vers le haut pour déverrouiller.",
                    style = type.body,
                    color = colors.inkMuted,
                )
            }
            HardwareKey(
                onClick = { onLockScreenChange(!lockScreenEnabled) },
                contentDescription = if (lockScreenEnabled) "Désactiver le lecteur plein écran" else "Activer le lecteur plein écran",
                modifier = Modifier.size(width = 76.dp, height = 44.dp),
                style = if (lockScreenEnabled) KeyStyle.Accent else KeyStyle.Standard,
            ) { Text(if (lockScreenEnabled) "Activé" else "Désactivé", style = type.label.copy(fontSize = 11.sp)) }
        }
        if (lockScreenEnabled) {
            missing.forEach { requirement ->
                Spacer(Modifier.height(12.dp))
                Panel {
                    Text("Autorisation nécessaire : ${requirement.title}", style = type.bodyStrong, color = colors.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(requirement.explanation, style = type.body, color = colors.displayMuted)
                }
                Spacer(Modifier.height(8.dp))
                HardwareKey(
                    onClick = { onGrant(requirement) },
                    contentDescription = "Autoriser : ${requirement.title}",
                    modifier = Modifier.fillMaxWidth(),
                    style = KeyStyle.Accent,
                ) { Text("Autoriser", style = type.bodyStrong) }
            }
            if (missing.isEmpty() && xiaomiUnknown) {
                Spacer(Modifier.height(12.dp))
                Panel {
                    Text("À vérifier : ${LockScreenRequirement.Xiaomi.title}", style = type.bodyStrong, color = colors.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(LockScreenRequirement.Xiaomi.explanation, style = type.body, color = colors.displayMuted)
                }
                Spacer(Modifier.height(8.dp))
                HardwareKey(
                    onClick = { onGrant(LockScreenRequirement.Xiaomi) },
                    contentDescription = "Ouvrir les autorisations Xiaomi",
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ouvrir les autorisations Xiaomi", style = type.bodyStrong) }
            } else if (missing.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Tout est prêt : éteignez l'écran pendant la lecture pour l'essayer.", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ToggleKey(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    HardwareKey(
        onClick = onClick,
        contentDescription = "$label, ${if (on) "activé" else "désactivé"}",
        modifier = modifier.height(44.dp).semantics { selected = on },
        style = if (on) KeyStyle.Selected else KeyStyle.Standard,
    ) { Text(label, style = LecteurTheme.type.label.copy(fontSize = 12.sp), maxLines = 1) }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = LecteurTheme.type.bodyStrong, color = LecteurTheme.colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LecteurTheme.colors.display)
            .padding(16.dp),
        content = content,
    )
}

@Preview(name = "Réglages, Appareil", widthDp = 360, heightDp = 900)
@Composable
private fun SettingsAppareilPreview() {
    LecteurTheme(darkTheme = false) { SettingsScreen(AccountUiState(), OutputKind.Bluetooth, 180, {}, {}, true, true, true, { _, _, _ -> }, LibraryProgress(120, 2480), true, true, listOf(LockScreenRequirement.FullScreen), {}, {}, {}, {}, {}) }
}

@Preview(name = "Réglages, Nuit", widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsNuitPreview() {
    LecteurTheme(darkTheme = true) {
        SettingsScreen(AccountUiState(user = AccountUser("1", "Inès Martin", "ines@example.com", null)), OutputKind.Speaker, 0, {}, {}, true, false, true, { _, _, _ -> }, null, false, false, emptyList(), {}, {}, {}, {}, {})
    }
}
