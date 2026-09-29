package com.lecteur.player.ui.jam

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.account.AccountUser
import com.lecteur.player.data.Track
import com.lecteur.player.data.matchesQuery
import com.lecteur.player.data.sortedByTitle
import com.lecteur.player.jam.JamInfo
import com.lecteur.player.jam.JamManager
import com.lecteur.player.jam.JamMember
import com.lecteur.player.jam.JamPlayback
import com.lecteur.player.jam.JamQueueItem
import com.lecteur.player.jam.JamRecap
import com.lecteur.player.jam.JamState
import com.lecteur.player.jam.JamTrackRef
import com.lecteur.player.jam.MemberStatus
import com.lecteur.player.jam.MyJamStatus
import com.lecteur.player.jam.NearbyJam
import com.lecteur.player.jam.NearbyJams
import com.lecteur.player.jam.PlayedEntry
import com.lecteur.player.jam.availability
import com.lecteur.player.jam.expectedPositionMs
import com.lecteur.player.jam.jamLink
import com.lecteur.player.jam.normalizeJamCode
import com.lecteur.player.jam.skipThreshold
import com.lecteur.player.jam.trackKey
import com.lecteur.player.history.formatListening
import com.lecteur.player.playback.departureTimes
import com.lecteur.player.playback.formatClock
import com.lecteur.player.playback.waitingTimes
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.components.LiveDot
import com.lecteur.player.ui.components.formatDuration
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class JamActions(
    val onClose: () -> Unit,
    val onSignIn: () -> Unit,
    val onStart: () -> Unit,
    val onJoin: (String) -> Unit,
    val onLeave: () -> Unit,
    val onPropose: (Track) -> Unit,
    val onVote: (JamQueueItem) -> Unit,
    val onRemove: (JamQueueItem) -> Unit,
    val onSkipVote: () -> Unit,
    val onReact: () -> Unit,
    val onTakeOver: () -> Unit,
    val onPassHost: (JamMember) -> Unit,
    val onFairQueue: (Boolean) -> Unit,
    val onDjMode: (Boolean) -> Unit,
    val onShare: (String) -> Unit,
    val onSaveRecap: (JamRecap) -> Unit,
    val onDismissRecap: () -> Unit,
)

@Composable
fun JamRoute(viewModel: LecteurViewModel, onClose: () -> Unit) {
    val jam by JamManager.state.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val invite by JamManager.pendingInvite.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf(false) }
    var prefill by rememberSaveable { mutableStateOf("") }

    BackHandler { if (picking) picking = false else onClose() }
    LaunchedEffect(jam.notice, jam.error) {
        if (jam.notice != null) {
            delay(3_500)
            JamManager.clearNotice()
        }
    }
    // Invitation reçue par lien ou QR code : on rejoint tout de suite si c'est possible, sinon on préremplit le code.
    LaunchedEffect(invite, account.user, jam.inJam) {
        val code = invite ?: return@LaunchedEffect
        when {
            jam.inJam -> Unit
            account.user != null -> JamManager.join(code)
            else -> prefill = code
        }
        JamManager.pendingInvite.value = null
    }
    val actions = remember(onClose) {
        JamActions(
            onClose = onClose,
            onSignIn = { activity?.let(viewModel::signIn) },
            onStart = JamManager::start,
            onJoin = JamManager::join,
            onLeave = { JamManager.leave() },
            onPropose = { JamManager.propose(it); picking = false },
            onVote = JamManager::toggleVote,
            onRemove = JamManager::remove,
            onSkipVote = JamManager::toggleSkipVote,
            onReact = JamManager::react,
            onTakeOver = JamManager::takeOver,
            onPassHost = JamManager::passHost,
            onFairQueue = JamManager::setFairQueue,
            onDjMode = JamManager::setDjMode,
            onShare = { code ->
                val text = "Rejoins ma jam sur Reaper : ${jamLink(code)} (code $code)"
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Inviter à la jam"))
            },
            onSaveRecap = { recap ->
                scope.launch {
                    val kept = JamManager.saveRecapAsPlaylist(recap)
                    JamManager.dismissRecap()
                    viewModel.showNotice(
                        if (kept > 0) "Liste enregistrée dans Bibliothèque › Listes ($kept titres)."
                        else "Aucun de ces titres n'est dans votre bibliothèque : rien à enregistrer.",
                    )
                }
            },
            onDismissRecap = JamManager::dismissRecap,
        )
    }
    if (picking && jam.inJam) {
        ProposePicker(tracks.orEmpty(), jam, onPick = actions.onPropose, onClose = { picking = false })
    } else {
        val nearby by remember(jam.inJam) {
            if (jam.inJam) kotlinx.coroutines.flow.flowOf(emptyList()) else NearbyJams.discover(context)
        }.collectAsState(initial = emptyList())
        JamScreen(
            jam, account.user, account.busy, nearby, prefill, viewModel.notice.collectAsStateWithLifecycle().value,
            viewModel.online.collectAsStateWithLifecycle().value, actions, onOpenPicker = { picking = true },
        )
    }
}

@Composable
fun JamScreen(
    state: JamState,
    user: AccountUser?,
    signingIn: Boolean,
    nearby: List<NearbyJam>,
    prefillCode: String,
    appNotice: String?,
    online: Boolean,
    actions: JamActions,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(
        modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = actions.onClose, contentDescription = "Revenir à la bibliothèque", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("Jam", style = type.displayLarge, color = colors.ink)
        }
        Spacer(Modifier.height(16.dp))

        if (!online) {
            Panel {
                Text("Hors ligne", style = type.displayTitle, color = colors.accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    "La jam a besoin d'une connexion internet. Tout le reste de Reaper fonctionne normalement sans réseau.",
                    style = type.body,
                    color = colors.displayMuted,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        val jam = state.jam
        when {
            user == null -> SignedOut(signingIn, actions.onSignIn)
            jam == null -> NoJam(state, nearby, prefillCode, actions)
            else -> InJam(state, jam, user, actions, onOpenPicker)
        }

        val message = state.error ?: state.notice ?: appNotice
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, style = type.body, color = colors.ink, modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(16.dp))
    }
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

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = LecteurTheme.type.bodyStrong, color = LecteurTheme.colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SignedOut(signingIn: Boolean, onSignIn: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Panel {
        Text("Écouter à plusieurs", style = type.displayTitle, color = colors.displayInk)
        Spacer(Modifier.height(8.dp))
        Text("La jam demande un compte et une connexion internet, pour que chacun sache qui écoute et qui propose quoi.", style = type.body, color = colors.displayMuted)
    }
    Spacer(Modifier.height(14.dp))
    if (signingIn) {
        Text("Connexion en cours…", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
    } else {
        HardwareKey(onClick = onSignIn, contentDescription = "Se connecter avec Google", modifier = Modifier.fillMaxWidth(), style = KeyStyle.Accent) {
            Text("Se connecter avec Google", style = type.bodyStrong)
        }
    }
}

@Composable
private fun NoJam(state: JamState, nearby: List<NearbyJam>, prefillCode: String, actions: JamActions) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    var code by rememberSaveable(prefillCode) { mutableStateOf(prefillCode) }

    state.lastRecap?.let { recap ->
        RecapCard(recap, actions)
        Spacer(Modifier.height(18.dp))
    }
    Panel {
        Text("Écouter à plusieurs", style = type.displayTitle, color = colors.displayInk)
        Spacer(Modifier.height(8.dp))
        Text(
            "Chacun écoute sur son téléphone, avec sa propre copie des morceaux : Reaper les retrouve par titre et artiste, et cale la lecture de tout le monde sur celle de l'hôte. Un titre absent de votre bibliothèque est passé chez vous.",
            style = type.body,
            color = colors.displayMuted,
        )
    }
    Spacer(Modifier.height(14.dp))
    if (state.busy) {
        Text("Connexion à la jam…", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    HardwareKey(onClick = actions.onStart, contentDescription = "Lancer une jam", modifier = Modifier.fillMaxWidth(), style = KeyStyle.Accent) {
        Text("Lancer une jam", style = type.bodyStrong)
    }

    if (nearby.isNotEmpty()) {
        Section("À proximité")
        nearby.forEach { jam ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Jam de ${jam.hostName.substringBefore(' ')}", style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Code ${jam.code}, même réseau Wi-Fi", style = type.label, color = colors.inkMuted)
                }
                HardwareKey(
                    onClick = { actions.onJoin(jam.code) },
                    contentDescription = "Rejoindre la jam de ${jam.hostName}",
                    modifier = Modifier.size(width = 104.dp, height = 44.dp),
                    style = KeyStyle.Selected,
                ) { Text("Rejoindre", style = type.label.copy(fontSize = 12.sp)) }
            }
        }
    }

    Section("Rejoindre avec un code")
    Text("Tapez le code affiché chez l'hôte, ou scannez son QR code avec l'appareil photo.", style = type.label, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.display)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (code.isEmpty()) Text("K7Q2X9", style = type.displayLarge.copy(fontSize = 28.sp), color = colors.dotOff)
            BasicTextField(
                value = code,
                onValueChange = { code = normalizeJamCode(it) },
                singleLine = true,
                textStyle = type.displayLarge.copy(fontSize = 28.sp, color = colors.accent),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.length == 6) actions.onJoin(code) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Code de la jam, 6 caractères" },
            )
        }
        Spacer(Modifier.width(8.dp))
        HardwareKey(
            onClick = { if (code.length == 6) actions.onJoin(code) },
            contentDescription = "Rejoindre",
            modifier = Modifier.size(width = 104.dp, height = 56.dp),
            style = if (code.length == 6) KeyStyle.Selected else KeyStyle.Standard,
        ) { Text("Rejoindre", style = type.label.copy(fontSize = 12.sp)) }
    }
}

/** La « feuille de session » de la dernière jam : durée, titres passés, contributeurs. */
@Composable
private fun RecapCard(recap: JamRecap, actions: JamActions) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Panel {
        Text("Votre dernière jam", style = type.label, color = colors.displayMuted)
        Text(
            "${recap.played.size} titres, ${formatListening(recap.durationMs)}",
            style = type.displayTitle,
            color = colors.displayInk,
        )
        Spacer(Modifier.height(6.dp))
        Text("Avec ${recap.participants.joinToString(", ") { it.substringBefore(' ') }}", style = type.body, color = colors.displayMuted)
        recap.mostVoted?.let {
            Spacer(Modifier.height(8.dp))
            Text("Le plus voté : ${it.track.title}, ${it.votes} votes", style = type.body, color = colors.accent)
        }
        if (recap.contributors.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Propositions : " + recap.contributors.joinToString(", ") { (name, n) -> "${name.substringBefore(' ')} $n" },
                style = type.label,
                color = colors.displayMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        recap.played.take(12).forEach { entry ->
            Text("${entry.track.title}, ${entry.track.artist}", style = type.label, color = colors.displayInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (recap.played.size > 12) Text("et ${recap.played.size - 12} autres", style = type.label, color = colors.displayMuted)
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HardwareKey(onClick = { actions.onSaveRecap(recap) }, contentDescription = "Enregistrer comme liste", modifier = Modifier.weight(1f), style = KeyStyle.Selected) {
            Text("Enregistrer la liste", style = type.label.copy(fontSize = 12.sp))
        }
        HardwareKey(onClick = actions.onDismissRecap, contentDescription = "Fermer le récap", modifier = Modifier.weight(1f)) {
            Text("Fermer", style = type.label.copy(fontSize = 12.sp))
        }
    }
}

@Composable
private fun InJam(state: JamState, jam: JamInfo, user: AccountUser, actions: JamActions, onOpenPicker: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val present = state.presentUids(now)

    if (state.hostAbsent) {
        Panel {
            Text("L'hôte ne répond plus", style = type.displayTitle, color = colors.accent)
            Spacer(Modifier.height(6.dp))
            Text("${jam.hostName.substringBefore(' ')} n'a pas donné signe de vie depuis plus d'une minute. Vous pouvez reprendre la main : la jam continuera depuis votre téléphone.", style = type.body, color = colors.displayMuted)
        }
        Spacer(Modifier.height(8.dp))
        HardwareKey(onClick = actions.onTakeOver, contentDescription = "Reprendre la main", modifier = Modifier.fillMaxWidth(), style = KeyStyle.Accent) {
            Text("Reprendre la main", style = type.bodyStrong)
        }
        Spacer(Modifier.height(14.dp))
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(8.dp))
            Text("En direct", style = type.label, color = colors.displayMuted)
            Spacer(Modifier.weight(1f))
            Text(if (state.isHost) "Vous êtes l'hôte" else "Hôte : ${jam.hostName}", style = type.label, color = colors.displayMuted, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        Text("Code de la jam", style = type.label, color = colors.displayMuted)
        Text(
            jam.code,
            style = type.displayLarge.copy(fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = 4.sp),
            color = colors.displayInk,
            modifier = Modifier.semantics { contentDescription = "Code de la jam : ${jam.code.toList().joinToString(" ")}" },
        )
        if (showQr) {
            Spacer(Modifier.height(10.dp))
            QrDots(jamLink(jam.code), "QR code d'invitation à la jam ${jam.code}", Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(6.dp))
            Text("À scanner avec l'appareil photo : Reaper s'ouvre directement dans la jam.", style = type.label, color = colors.displayMuted)
        }
        Spacer(Modifier.height(10.dp))
        Text(statusText(state.me), style = type.body, color = colors.accent)
        val playback = jam.playback
        playback?.track?.let { track ->
            Spacer(Modifier.height(10.dp))
            Text(if (playback.playing) "En lecture" else "En pause", style = type.label, color = colors.displayMuted)
            Text(track.title, style = type.displayTitle, color = colors.displayInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val have = availability(track.key, state.libraries, present)
            Text(
                track.artist + if (have.total > 1) ", présent chez ${have.have} sur ${have.total}" else "",
                style = type.label,
                color = colors.displayMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HardwareKey(onClick = { showQr = !showQr }, contentDescription = if (showQr) "Masquer le QR code" else "Afficher le QR code", modifier = Modifier.weight(1f)) {
            Text(if (showQr) "Masquer le QR" else "QR code", style = type.label.copy(fontSize = 12.sp))
        }
        HardwareKey(onClick = { actions.onShare(jam.code) }, contentDescription = "Inviter par message", modifier = Modifier.weight(1f)) {
            Text("Inviter", style = type.label.copy(fontSize = 12.sp))
        }
        HardwareKey(onClick = actions.onReact, contentDescription = "Réagir", modifier = Modifier.weight(1f), style = KeyStyle.Accent) {
            Text("Réagir", style = type.label.copy(fontSize = 12.sp))
        }
    }

    // Vote pour passer le titre en cours : la majorité des présents suffit.
    val currentKey = jam.playback?.track?.key
    if (currentKey != null) {
        Spacer(Modifier.height(8.dp))
        val votes = if (jam.skipVoteKey == currentKey) jam.skipVoteUids.size else 0
        val voted = jam.skipVoteKey == currentKey && user.uid in jam.skipVoteUids
        HardwareKey(
            onClick = actions.onSkipVote,
            contentDescription = if (voted) "Retirer mon vote pour passer ce titre" else "Voter pour passer ce titre",
            modifier = Modifier.fillMaxWidth().semantics { selected = voted },
            style = if (voted) KeyStyle.Selected else KeyStyle.Standard,
        ) { Text("Passer ce titre  $votes / ${skipThreshold(present.size.coerceAtLeast(1))}", style = type.bodyStrong) }
    }

    Section("Participants, ${state.members.size}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.members.take(4).forEach { member ->
            MemberTile(
                member = member,
                isMe = member.uid == user.uid,
                present = member.uid in present,
                canPassHost = state.isHost && member.uid != user.uid,
                onPassHost = { actions.onPassHost(member) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (state.members.size > 4) {
        Text("et ${state.members.size - 4} autres", style = type.label, color = colors.inkMuted, modifier = Modifier.padding(4.dp))
    }
    if (state.isHost && state.members.size > 1) {
        Text("Touchez un participant pour lui passer la main.", style = type.label, color = colors.inkMuted, modifier = Modifier.padding(4.dp))
    }

    if (state.isHost) {
        Section("Réglages de la jam")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionKey("File équitable", jam.fairQueue, Modifier.weight(1f)) { actions.onFairQueue(!jam.fairQueue) }
            OptionKey("DJ maison", jam.djMode, Modifier.weight(1f)) { actions.onDjMode(!jam.djMode) }
        }
        Text(
            (if (jam.fairQueue) "Chacun son tour dans la file. " else "La file suit les votes. ") +
                (if (jam.djMode) "Quand elle est vide, Reaper enchaîne sur des titres proches, de préférence présents chez tout le monde." else "Quand elle est vide, la lecture s'arrête après votre propre file."),
            style = type.label,
            color = colors.inkMuted,
            modifier = Modifier.padding(4.dp),
        )
    }

    Section(if (jam.fairQueue) "À suivre, chacun son tour" else "À suivre")
    QueueBoard(state, jam.playback, now, user.uid, present, actions)
    Spacer(Modifier.height(12.dp))
    HardwareKey(onClick = onOpenPicker, contentDescription = "Proposer un titre", modifier = Modifier.fillMaxWidth(), style = KeyStyle.Selected) {
        Text("Proposer un titre", style = type.bodyStrong)
    }
    Spacer(Modifier.height(10.dp))
    HardwareKey(onClick = actions.onLeave, contentDescription = if (state.isHost) "Terminer la jam" else "Quitter la jam", modifier = Modifier.fillMaxWidth()) {
        Text(if (state.isHost) "Terminer la jam" else "Quitter la jam", style = type.bodyStrong)
    }
}

@Composable
private fun OptionKey(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    HardwareKey(
        onClick = onClick,
        contentDescription = "$label, ${if (on) "activé" else "désactivé"}",
        modifier = modifier.semantics { selected = on },
        style = if (on) KeyStyle.Selected else KeyStyle.Standard,
    ) { Text(label, style = LecteurTheme.type.label.copy(fontSize = 12.sp)) }
}

private fun statusText(me: MyJamStatus): String = when (me) {
    MyJamStatus.Host -> "Vous menez la lecture : les autres se calent sur vous."
    MyJamStatus.Waiting -> "En attente d'un morceau chez l'hôte."
    MyJamStatus.Syncing -> "Synchronisation…"
    is MyJamStatus.Synced -> "Synchronisé, écart ${abs(me.driftMs)} ms."
    is MyJamStatus.Missing -> "« ${me.title} » n'est pas dans votre bibliothèque : vous reprendrez au titre suivant."
}

@Composable
private fun MemberTile(member: JamMember, isMe: Boolean, present: Boolean, canPassHost: Boolean, onPassHost: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    var menu by remember { mutableStateOf(false) }
    val status = when {
        !present -> "absent"
        member.status == MemberStatus.Host -> "hôte"
        member.status == MemberStatus.Synced -> member.driftMs?.let { "${abs(it)} ms" } ?: "calé"
        member.status == MemberStatus.Syncing -> "se cale…"
        member.status == MemberStatus.Missing -> "titre absent"
        else -> "en attente"
    }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.tile)
                .border(1.dp, if (member.status == MemberStatus.Host) colors.accent else colors.line, RoundedCornerShape(8.dp))
                .then(if (canPassHost) Modifier.clickable(onClickLabel = "Options") { menu = true } else Modifier)
                .padding(8.dp),
        ) {
            Text(if (isMe) "Vous" else member.name.substringBefore(' '), style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = type.label, color = colors.inkMuted, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.tile, shape = RoundedCornerShape(10.dp)) {
            DropdownMenuItem(
                text = { Text("Lui passer la main", style = type.bodyStrong, color = colors.ink) },
                onClick = { menu = false; onPassHost() },
            )
        }
    }
}

/** La file comme un tableau des départs : heure de passage, votes, présence du titre chez chacun. */
@Composable
private fun QueueBoard(state: JamState, playback: JamPlayback?, nowMs: Long, myUid: String, present: List<String>, actions: JamActions) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    if (state.queue.isEmpty()) {
        Text(
            "Personne n'a encore rien proposé. Le premier titre de la file passe juste après le morceau en cours.",
            style = type.body,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    val currentRemaining = playback?.track?.let { it.durationMs - expectedPositionMs(playback, nowMs) } ?: 0L
    val playable = state.queue.map { if (it.hostHas == false) 0L else it.track.durationMs }
    val times = if (playback?.playing == true) {
        departureTimes(nowMs, currentRemaining, playable).map { formatClock(it) }
    } else {
        waitingTimes(currentRemaining, playable).map { "+${formatDuration(it)}" }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(if (playback?.playing == true) "Départ" else "Dans", style = type.label, color = colors.inkMuted, modifier = Modifier.width(60.dp))
        Text("Titre, proposé par", style = type.label, color = colors.inkMuted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink))
    state.queue.forEachIndexed { index, item ->
        val voted = myUid in item.votes
        val have = availability(item.track.key, state.libraries, present)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(times[index], style = type.displayValue.copy(fontSize = 15.sp), color = colors.ink, modifier = Modifier.width(60.dp))
            Column(Modifier.weight(1f)) {
                Text(item.track.title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        item.hostHas == false -> "Absent chez l'hôte, ne passera pas"
                        have.total > 1 -> "par ${item.addedByName.substringBefore(' ')}, chez ${have.have} sur ${have.total}"
                        else -> "${item.track.artist}, par ${item.addedByName.substringBefore(' ')}"
                    },
                    style = type.label,
                    color = if (item.hostHas == false) colors.accent else colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            HardwareKey(
                onClick = { actions.onVote(item) },
                contentDescription = if (voted) "Retirer mon vote pour ${item.track.title}" else "Voter pour ${item.track.title}",
                modifier = Modifier.size(width = 52.dp, height = 40.dp).semantics { selected = voted },
                style = if (voted) KeyStyle.Accent else KeyStyle.Standard,
            ) { Text("+${item.votes.size}", style = type.label.copy(fontSize = 12.sp)) }
            if (state.isHost || item.addedBy == myUid) {
                Spacer(Modifier.width(6.dp))
                HardwareKey(
                    onClick = { actions.onRemove(item) },
                    contentDescription = "Retirer ${item.track.title} de la file",
                    modifier = Modifier.size(width = 44.dp, height = 40.dp),
                ) { Icon(LecteurIcons.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

/** Choisir dans sa bibliothèque le titre à proposer, avec sa présence chez les autres. */
@Composable
private fun ProposePicker(tracks: List<Track>, state: JamState, onPick: (Track) -> Unit, onClose: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    var query by rememberSaveable { mutableStateOf("") }
    var everyoneOnly by rememberSaveable { mutableStateOf(false) }
    val present = remember(state.members) { state.presentUids(System.currentTimeMillis()) }
    val results = remember(tracks, query, everyoneOnly, state.libraries, present) {
        sortedByTitle(tracks.filter { matchesQuery(it, query) })
            .map { it to availability(trackKey(it.title, it.artist), state.libraries, present) }
            .filter { !everyoneOnly || it.second.everyone }
            .take(150)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = onClose, contentDescription = "Revenir à la jam", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("Proposer", style = type.displayLarge, color = colors.ink)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.tile)
                    .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text("Titre, artiste ou album", style = type.body, color = colors.inkMuted)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = type.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Rechercher un titre à proposer" },
                )
            }
            Spacer(Modifier.width(8.dp))
            HardwareKey(
                onClick = { everyoneOnly = !everyoneOnly },
                contentDescription = "Seulement les titres présents chez tout le monde",
                modifier = Modifier.size(width = 112.dp, height = 44.dp).semantics { selected = everyoneOnly },
                style = if (everyoneOnly) KeyStyle.Selected else KeyStyle.Standard,
            ) { Text("Chez tous", style = type.label.copy(fontSize = 12.sp)) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results, key = { it.first.id }) { (track, have) ->
                Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Proposer") { onPick(track) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (have.total > 1) {
                            Text(
                                "${have.have}/${have.total}",
                                style = type.displayValue.copy(fontSize = 14.sp),
                                color = if (have.everyone) colors.accent else colors.inkMuted,
                                modifier = Modifier.semantics { contentDescription = "Présent chez ${have.have} sur ${have.total}" },
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(formatDuration(track.durationMs), style = type.displayValue.copy(fontSize = 14.sp), color = colors.ink)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                }
            }
        }
    }
}

private val PreviewActions = JamActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

private fun previewState(): JamState {
    val ref = JamTrackRef("lune basse|minuit sur le quai", "Minuit sur le quai", "Lune Basse", "Quai Nord", 337_000)
    val now = System.currentTimeMillis()
    return JamState(
        jam = JamInfo("j", "K7Q2X9", "u1", "Inès Martin", true, JamPlayback(ref, true, 128_000, now), skipVoteKey = ref.key, skipVoteUids = listOf("u3")),
        isHost = false,
        myUid = "me",
        members = listOf(
            JamMember("u1", "Inès Martin", MemberStatus.Host, null, now),
            JamMember("me", "Malik", MemberStatus.Synced, 34, now),
            JamMember("u3", "Chloé", MemberStatus.Missing, null, now),
        ),
        queue = listOf(
            JamQueueItem("a", JamTrackRef("k|n", "Néons", "Kaelo", "", 228_000), "u3", "Chloé", 1, listOf("me", "u1", "u3"), true),
            JamQueueItem("b", JamTrackRef("s|r", "Radio Nacre", "Les Satellites", "", 242_000), "u1", "Inès", 2, listOf("u1"), false),
        ),
        me = MyJamStatus.Synced(34),
    )
}

private fun previewRecap() = JamRecap(
    durationMs = 5_400_000,
    played = listOf(PlayedEntry(JamTrackRef("k|n", "Néons", "Kaelo", "", 228_000), "Chloé", 3, 0)),
    participants = listOf("Inès", "Malik", "Chloé"),
    mostVoted = PlayedEntry(JamTrackRef("k|n", "Néons", "Kaelo", "", 228_000), "Chloé", 3, 0),
    contributors = listOf("Chloé" to 1),
)

@Preview(name = "Jam, Appareil", widthDp = 360, heightDp = 1300)
@Composable
private fun JamAppareilPreview() {
    LecteurTheme(darkTheme = false) {
        JamScreen(previewState(), AccountUser("me", "Malik", null, null), false, emptyList(), "", null, true, PreviewActions, {})
    }
}

@Preview(name = "Jam, Nuit", widthDp = 360, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun JamNuitPreview() {
    LecteurTheme(darkTheme = true) {
        JamScreen(
            JamState(lastRecap = previewRecap()),
            AccountUser("me", "Malik", null, null),
            false,
            listOf(NearbyJam("K7Q2X9", "Inès Martin")),
            "",
            null,
            false,
            PreviewActions,
            {},
        )
    }
}

