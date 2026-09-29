package com.lecteur.player.jam

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.lecteur.player.account.AccountUser
import com.lecteur.player.audio.AudioEffects
import com.lecteur.player.data.AudioLibrary
import com.lecteur.player.data.Track
import com.lecteur.player.history.HistoryDatabase
import com.lecteur.player.history.PlayEntity
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Où en est ce téléphone dans la jam. */
sealed interface MyJamStatus {
    data object Host : MyJamStatus
    data object Waiting : MyJamStatus
    data object Syncing : MyJamStatus
    data class Synced(val driftMs: Long) : MyJamStatus
    data class Missing(val title: String) : MyJamStatus
}

data class JamState(
    val busy: Boolean = false,
    val error: String? = null,
    val jam: JamInfo? = null,
    val isHost: Boolean = false,
    val myUid: String? = null,
    val members: List<JamMember> = emptyList(),
    /** File dans l'ordre où elle passera (votes, ou chacun son tour). */
    val queue: List<JamQueueItem> = emptyList(),
    /** Empreintes des bibliothèques, par uid : pour afficher « présent chez 3 sur 4 ». */
    val libraries: Map<String, Set<String>> = emptyMap(),
    /** L'hôte ne donne plus signe de vie : un participant peut reprendre la main. */
    val hostAbsent: Boolean = false,
    val me: MyJamStatus = MyJamStatus.Waiting,
    /** Message ponctuel (fin de jam, titre proposé…), effacé par l'interface après affichage. */
    val notice: String? = null,
    /** Récap de la dernière jam quittée, jusqu'à ce que l'utilisateur le ferme. */
    val lastRecap: JamRecap? = null,
) {
    val inJam: Boolean get() = jam != null

    /** Participants présents (signe de vie depuis moins de 90 s), hôte compris. */
    fun presentUids(serverNowMs: Long): List<String> = members.filter { isPresent(it.lastSeenMs, serverNowMs) }.map { it.uid }
}

/**
 * La jam, pour tout le processus : elle continue quand l'interface est fermée tant que la musique joue.
 *
 * Hôte : publie ce qu'il joue (position réellement entendue, instant serveur) à chaque vrai changement,
 * enchaîne sur la file (votes ou chacun son tour) ou sur le DJ maison, applique les votes pour passer,
 * tient le journal des titres passés. Participant : retrouve le morceau chez lui, s'y place en tenant
 * compte du retard de sa sortie audio, puis corrige la dérive. Le rôle suit le champ hostUid de la jam :
 * passer la main ou la reprendre bascule chaque téléphone automatiquement.
 */
object JamManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { JamRepository() }
    private lateinit var appContext: Context
    private lateinit var player: PlayerConnection
    private lateinit var library: StateFlow<List<Track>>

    private val _state = MutableStateFlow(JamState())
    val state: StateFlow<JamState> = _state.asStateFlow()

    private val _reactions = MutableSharedFlow<JamReaction>(extraBufferCapacity = 16)
    /** Réactions reçues, pour l'animation affichée par-dessus l'app. */
    val reactions: SharedFlow<JamReaction> = _reactions.asSharedFlow()

    /** Code reçu par un lien d'invitation ou un QR code, en attente d'être utilisé par l'écran Jam. */
    val pendingInvite = MutableStateFlow<String?>(null)

    private var user: AccountUser? = null
    private val sessionJobs = mutableListOf<Job>()
    private var roleJob: Job? = null
    private var currentRole: Boolean? = null
    private var rawQueue: List<JamQueueItem> = emptyList()
    private var clockOffsetMs = 0L
    private var lastSpeed = 1f
    private var lastReactionAt = 0L

    // État de l'hôte.
    private var lastPublished: JamPlayback? = null
    private var nextScheduledFor: String? = null
    private val scheduledOrigin = mutableMapOf<Long, PlayedEntry>()
    private var lastLoggedKey: String? = null
    private val sessionPlayed = mutableListOf<PlayedEntry>()
    private var historyCache: List<PlayEntity>? = null

    fun init(context: Context) {
        if (::player.isInitialized) return
        appContext = context.applicationContext
        player = PlayerConnection(appContext).apply { connect() }
        library = AudioLibrary(appContext).tracks()
            .catch { emit(emptyList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    private fun currentUser(): AccountUser? = FirebaseAuth.getInstance().currentUser?.let { u ->
        AccountUser(u.uid, u.displayName?.takeIf { it.isNotBlank() } ?: u.email?.substringBefore('@') ?: "Sans nom", u.email, u.photoUrl?.toString())
    }

    private fun serverNow() = System.currentTimeMillis() + clockOffsetMs

    private fun myLatencyMs(): Long = PlaybackSettings.state.value.latencyFor(AudioEffects.state.value.output)

    // --- Actions de l'interface -------------------------------------------------------------------

    fun start() = launchAction("Impossible de lancer la jam") { u ->
        val id = repository.create(u)
        enter(id, u)
    }

    fun join(rawCode: String) = launchAction("Impossible de rejoindre la jam") { u ->
        val code = normalizeJamCode(rawCode)
        val id = repository.findActiveJam(code)
        if (id == null) {
            _state.update { it.copy(busy = false, error = "Aucune jam en cours avec le code $code. Vérifiez-le auprès de l'hôte.") }
            return@launchAction
        }
        repository.join(id, u, MemberStatus.Syncing)
        // Une jam dont l'hôte a disparu depuis 10 minutes est fermée plutôt que rejointe.
        val hostSeen = repository.hostLastSeenMs(id)
        if (hostSeen == null || System.currentTimeMillis() - hostSeen > 10 * 60_000) {
            runCatching { repository.markAbandoned(id) }
            runCatching { repository.leave(id, u.uid) }
            _state.update { it.copy(busy = false, error = "Cette jam n'est plus active : son hôte l'a quittée.") }
            return@launchAction
        }
        enter(id, u)
    }

    /** Quitter (participant) ou terminer (hôte) la jam ; prépare le récap. */
    fun leave(message: String? = null) {
        val jam = _state.value.jam ?: return
        val u = user
        val wasHost = _state.value.isHost
        val names = _state.value.members.map { it.name }
        stopSession()
        _state.value = JamState(notice = message)
        scope.launch {
            // Le journal n'est lisible qu'en étant encore participant : on le lit avant de partir.
            val played = runCatching { repository.fetchPlayed(jam.id) }.getOrDefault(emptyList())
            if (played.isNotEmpty()) {
                val recap = buildJamRecap(played, names, jam.createdAtMs.takeIf { it > 0 } ?: played.minOf { it.playedAtMs }, System.currentTimeMillis())
                _state.update { it.copy(lastRecap = recap) }
            }
            runCatching {
                if (u != null) repository.deleteLibrary(jam.id, u.uid)
                if (wasHost) repository.end(jam.id)
                if (u != null) repository.leave(jam.id, u.uid)
            }
        }
    }

    fun dismissRecap() = _state.update { it.copy(lastRecap = null) }

    /** Enregistre le récap comme liste locale (titres présents dans la bibliothèque). Renvoie le nombre de titres gardés. */
    suspend fun saveRecapAsPlaylist(recap: JamRecap): Int {
        val tracks = recap.played.mapNotNull { matchTrack(it.track, library.value) }.distinctBy { it.id }
        if (tracks.isEmpty()) return 0
        val name = "Jam du " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH))
        HistoryDatabase.get(appContext).playlists().create(name, tracks.map { it.id }, System.currentTimeMillis())
        return tracks.size
    }

    fun propose(track: Track) = withJam { jam, u ->
        runCatching { repository.propose(jam.id, u, track.toJamRef()) }
            .onSuccess { notice("Proposé : ${track.title}") }
            .onFailure { notice("La proposition n'est pas passée. Vérifiez votre connexion.") }
    }

    fun toggleVote(item: JamQueueItem) = withJam { jam, u ->
        runCatching { repository.setVote(jam.id, item.id, u.uid, voted = u.uid !in item.votes) }
    }

    fun remove(item: JamQueueItem) = withJam { jam, _ -> runCatching { repository.removeItem(jam.id, item.id) } }

    /** Vote (ou retire son vote) pour passer le titre en cours. */
    fun toggleSkipVote() = withJam { jam, u ->
        val current = jam.playback?.track?.key ?: return@withJam
        if (jam.skipVoteKey != current) {
            notice("Le vote s'ouvre dans un instant, le temps que l'hôte change de titre.")
            return@withJam
        }
        runCatching { repository.setSkipVote(jam.id, u.uid, voted = u.uid !in jam.skipVoteUids) }
    }

    /** L'hôte passe la main à un participant. */
    fun passHost(member: JamMember) = withJam { jam, _ ->
        runCatching { repository.setHost(jam.id, member) }
            .onSuccess { notice("${member.name.substringBefore(' ')} mène la lecture.") }
    }

    /** Un participant reprend la main quand l'hôte ne répond plus. */
    fun takeOver() = withJam { jam, u ->
        runCatching { repository.setHost(jam.id, JamMember(u.uid, u.displayName, MemberStatus.Host, null, 0)) }
            .onSuccess { notice("Vous menez la lecture.") }
            .onFailure { notice("Impossible de reprendre la main : l'hôte est peut-être revenu.") }
    }

    fun setFairQueue(enabled: Boolean) = withJam { jam, _ -> runCatching { repository.setOption(jam.id, "fairQueue", enabled) } }

    fun setDjMode(enabled: Boolean) = withJam { jam, _ -> runCatching { repository.setOption(jam.id, "djMode", enabled) } }

    /** Envoie une réaction à tout le monde (au plus une par seconde). */
    fun react() = withJam { jam, u ->
        val now = System.currentTimeMillis()
        if (now - lastReactionAt < 1_000) return@withJam
        lastReactionAt = now
        val id = runCatching { repository.react(jam.id, u) }.getOrNull() ?: return@withJam
        // Les réactions sont éphémères : on efface la sienne peu après.
        delay(20_000)
        runCatching { repository.deleteReaction(jam.id, id) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null, error = null) }

    private fun notice(message: String) = _state.update { it.copy(notice = message) }

    private fun withJam(block: suspend (JamInfo, AccountUser) -> Unit) {
        val jam = _state.value.jam ?: return
        val u = user ?: return
        scope.launch { block(jam, u) }
    }

    private fun launchAction(failure: String, block: suspend (AccountUser) -> Unit) {
        val u = currentUser()
        if (u == null) {
            _state.update { it.copy(error = "Connectez-vous avec Google pour utiliser la jam.") }
            return
        }
        if (_state.value.busy || _state.value.inJam) return
        _state.update { it.copy(busy = true, error = null, lastRecap = null) }
        scope.launch {
            runCatching { block(u) }.onFailure { e ->
                stopSession()
                _state.value = JamState(error = "$failure : ${e.localizedMessage ?: "erreur inconnue"}. Vérifiez votre connexion internet.")
            }
        }
    }

    // --- Session ----------------------------------------------------------------------------------

    private suspend fun enter(jamId: String, u: AccountUser) {
        user = u
        clockOffsetMs = measureClock(jamId, u.uid) ?: 0L
        currentRole = null
        _state.update { it.copy(busy = false, myUid = u.uid, me = MyJamStatus.Syncing) }

        sessionJobs += scope.launch {
            repository.observeJam(jamId).collect { jam ->
                if (jam == null) return@collect
                val amHost = jam.hostUid == u.uid
                if (!jam.active && !amHost) {
                    leave("L'hôte a terminé la jam.")
                    return@collect
                }
                _state.update { it.copy(jam = jam, queue = orderQueue(rawQueue, jam)) }
                if (amHost != currentRole) switchRole(jamId, amHost)
                refreshHostPresence()
            }
        }
        sessionJobs += scope.launch {
            repository.observeMembers(jamId).collect { members ->
                _state.update { it.copy(members = members) }
                refreshHostPresence()
            }
        }
        sessionJobs += scope.launch {
            repository.observeQueue(jamId).collect { q ->
                rawQueue = q
                _state.update { s -> s.copy(queue = s.jam?.let { orderQueue(q, it) } ?: q) }
            }
        }
        sessionJobs += scope.launch { repository.observeLibraries(jamId).collect { libs -> _state.update { it.copy(libraries = libs) } } }
        sessionJobs += scope.launch { repository.observeReactions(jamId).collect { _reactions.tryEmit(it) } }
        sessionJobs += scope.launch { heartbeatLoop(jamId, u.uid) }
        sessionJobs += scope.launch {
            val hashes = library.value.map { libraryHash(trackKey(it.title, it.artist)) }.distinct()
            runCatching { repository.publishLibrary(jamId, u.uid, hashes) }
        }
    }

    private fun switchRole(jamId: String, host: Boolean) {
        roleJob?.cancel()
        currentRole = host
        _state.update { it.copy(isHost = host, me = if (host) MyJamStatus.Host else MyJamStatus.Syncing) }
        if (host) {
            resetSpeed()
            lastPublished = null
            nextScheduledFor = null
            lastLoggedKey = _state.value.jam?.playback?.track?.key
            _state.value.jam?.let { NearbyJams.advertise(appContext, it.code, user?.displayName ?: "") }
            roleJob = scope.launch { hostLoop(jamId) }
        } else {
            NearbyJams.stopAdvertising(appContext)
            roleJob = scope.launch { followerLoop() }
        }
    }

    private fun stopSession() {
        roleJob?.cancel()
        roleJob = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        currentRole = null
        rawQueue = emptyList()
        scheduledOrigin.clear()
        sessionPlayed.clear()
        historyCache = null
        if (::appContext.isInitialized) NearbyJams.stopAdvertising(appContext)
        resetSpeed()
    }

    private fun resetSpeed() {
        if (lastSpeed != 1f) {
            player.setSpeed(1f)
            lastSpeed = 1f
        }
    }

    private fun refreshHostPresence() {
        val s = _state.value
        val jam = s.jam ?: return
        val host = s.members.firstOrNull { it.uid == jam.hostUid }
        val absent = !s.isHost && (host == null || !isPresent(host.lastSeenMs, serverNow()))
        if (absent != s.hostAbsent) _state.update { it.copy(hostAbsent = absent) }
    }

    /** Ordre de passage : chacun son tour si l'option est active, sinon par votes. */
    private fun orderQueue(items: List<JamQueueItem>, jam: JamInfo): List<JamQueueItem> =
        if (jam.fairQueue) fairQueue(items, sessionPlayed.mapNotNull { it.addedBy }) else sortedQueue(items)

    private suspend fun measureClock(jamId: String, uid: String): Long? {
        val samples = (1..3).mapNotNull { runCatching { repository.clockSample(jamId, uid) }.getOrNull() }
        return estimateClockOffset(samples)
    }

    private suspend fun heartbeatLoop(jamId: String, uid: String) {
        var beats = 0
        while (scope.isActive) {
            val me = _state.value.me
            val status = when (me) {
                MyJamStatus.Host -> MemberStatus.Host
                is MyJamStatus.Synced -> MemberStatus.Synced
                is MyJamStatus.Missing -> MemberStatus.Missing
                MyJamStatus.Syncing -> MemberStatus.Syncing
                MyJamStatus.Waiting -> MemberStatus.Waiting
            }
            runCatching { repository.heartbeat(jamId, uid, status, (me as? MyJamStatus.Synced)?.driftMs) }
            refreshHostPresence()
            // L'horloge du téléphone dérive un peu : nouvelle mesure toutes les 5 minutes environ.
            if (++beats % 10 == 0) measureClock(jamId, uid)?.let { clockOffsetMs = it }
            delay(30_000)
        }
    }

    // --- Hôte -------------------------------------------------------------------------------------

    private suspend fun hostLoop(jamId: String) {
        while (scope.isActive) {
            val tracks = library.value
            val mediaId = player.currentMediaId
            val local = mediaId?.let { id -> tracks.firstOrNull { it.id.toString() == id } }
            val position = player.currentPositionMs() ?: 0L
            val current = JamPlayback(
                track = local?.toJamRef(),
                playing = player.state.isPlaying,
                positionMs = audiblePosition(position, myLatencyMs()),
                anchorServerMs = serverNow(),
                speed = player.state.speed,
            )
            if (shouldPublish(lastPublished, current, serverNow())) {
                runCatching { repository.publish(jamId, current) }.onSuccess {
                    val newKey = current.track?.key
                    if (newKey != lastPublished?.track?.key && newKey != null) onNewTrack(jamId, local!!, current.track)
                    lastPublished = current
                }
            }
            applySkipVotes(current)
            checkHostHas(jamId, tracks)
            scheduleNext(jamId, tracks, mediaId, position, local)
            delay(500)
        }
    }

    /** Nouveau titre chez l'hôte : votes remis à zéro, entrée dans le journal de la jam. */
    private suspend fun onNewTrack(jamId: String, local: Track, ref: JamTrackRef) {
        runCatching { repository.resetSkipVotes(jamId, ref.key) }
        if (ref.key == lastLoggedKey) return
        lastLoggedKey = ref.key
        val u = user
        val entry = scheduledOrigin.remove(local.id)?.copy(playedAtMs = serverNow())
            ?: PlayedEntry(ref, u?.displayName, 0, serverNow(), u?.uid)
        sessionPlayed += entry
        runCatching { repository.logPlayed(jamId, entry) }
    }

    private fun applySkipVotes(current: JamPlayback) {
        val jam = _state.value.jam ?: return
        val key = current.track?.key ?: return
        if (jam.skipVoteKey != key) return
        if (shouldSkip(jam.skipVoteUids.size, _state.value.presentUids(serverNow()).size)) {
            player.next()
            notice("Titre passé à la demande des participants.")
        }
    }

    /** L'hôte indique pour chaque proposition s'il a le morceau : les autres voient ce qui pourra passer. */
    private suspend fun checkHostHas(jamId: String, tracks: List<Track>) {
        rawQueue.filter { it.hostHas == null }.forEach { item ->
            runCatching { repository.setHostHas(jamId, item.id, matchTrack(item.track, tracks) != null) }
        }
    }

    /**
     * Quelques secondes avant la fin (ou si la file de l'hôte est terminée), on enchaîne sur la file de la jam ;
     * si elle est vide et que l'hôte n'a rien derrière, le DJ maison choisit.
     */
    private suspend fun scheduleNext(jamId: String, tracks: List<Track>, mediaId: String?, position: Long, local: Track?) {
        val idle = mediaId == null || player.state.ended
        val remaining = (player.state.durationMs.takeIf { it > 0 } ?: local?.durationMs ?: 0L) - position
        val dueSoon = mediaId != nextScheduledFor && remaining in 1..8_000
        if (!idle && !dueSoon) return

        val jam = _state.value.jam ?: return
        val queued = _state.value.queue.firstOrNull { it.hostHas == true }
        val choice: Pair<Track, PlayedEntry>? = when {
            queued != null -> matchTrack(queued.track, tracks)?.let { t ->
                t to PlayedEntry(queued.track, queued.addedByName, queued.votes.size, 0, queued.addedBy)
            }
            jam.djMode && (idle || player.state.queueIndex + 1 >= player.state.queueSize) -> djPick(tracks, local)?.let { t ->
                t to PlayedEntry(t.toJamRef(), "DJ maison", 0, 0, null)
            }
            else -> null
        }
        // Rien à enchaîner pour l'instant : on réessaie au prochain tour (une proposition peut encore arriver).
        if (choice == null) return
        val (track, origin) = choice
        if (idle) player.play(listOf(track), 0) else player.playNext(track)
        nextScheduledFor = mediaId
        scheduledOrigin[track.id] = origin
        if (queued != null && origin.addedBy == queued.addedBy && origin.track.key == queued.track.key) {
            runCatching { repository.removeItem(jamId, queued.id) }
        }
    }

    private suspend fun djPick(tracks: List<Track>, current: Track?): Track? {
        val history = historyCache ?: runCatching { HistoryDatabase.get(appContext).plays().all() }.getOrDefault(emptyList()).also { historyCache = it }
        val s = _state.value
        val present = s.presentUids(serverNow())
        val played = sessionPlayed.map { it.track.key }.toSet()
        return pickDjTrack(
            library = tracks,
            current = current,
            everyoneHas = { availability(trackKey(it.title, it.artist), s.libraries, present).everyone },
            coPlayCounts = current?.let { coPlays(history, it.id) } ?: emptyMap(),
            playedKeys = played,
        )
    }

    // --- Participant ------------------------------------------------------------------------------

    private suspend fun followerLoop() {
        while (scope.isActive) {
            followOnce()
            delay(500)
        }
    }

    private fun followOnce() {
        val playback = _state.value.jam?.playback
        val ref = playback?.track
        if (playback == null || ref == null) {
            setMe(MyJamStatus.Waiting)
            return
        }
        val local = matchTrack(ref, library.value)
        if (local == null) {
            // Pas ce morceau ici : on se tait jusqu'au suivant.
            if (player.playWhenReady) player.setPlaying(false)
            setMe(MyJamStatus.Missing(ref.title))
            return
        }
        // On vise la position entendue chez l'hôte, en avance de notre propre retard de sortie.
        val target = followerTarget(expectedPositionMs(playback, serverNow()), myLatencyMs())
        if (player.currentMediaId != local.id.toString()) {
            player.play(listOf(local), 0)
            player.seekToMs(target)
            player.setPlaying(playback.playing)
            setMe(MyJamStatus.Syncing)
            return
        }
        player.setPlaying(playback.playing)
        if (!playback.playing) {
            if (abs((player.currentPositionMs() ?: target) - target) > 1_000) player.seekToMs(target)
            setMe(MyJamStatus.Synced(0))
            return
        }
        val localPosition = player.currentPositionMs() ?: return
        val decision = syncDecision(localPosition, target)
        decision.seekToMs?.let(player::seekToMs)
        val speed = decision.speed * playback.speed
        if (abs(speed - lastSpeed) > 0.001f) {
            player.setSpeed(speed)
            lastSpeed = speed
        }
        setMe(if (decision.inSync) MyJamStatus.Synced(localPosition - target) else MyJamStatus.Syncing)
    }

    private fun setMe(status: MyJamStatus) {
        if (_state.value.me != status) _state.update { it.copy(me = status) }
    }
}
