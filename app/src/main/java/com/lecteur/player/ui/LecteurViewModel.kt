package com.lecteur.player.ui

import android.app.Activity
import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lecteur.player.analysis.LibraryProgress
import com.lecteur.player.analysis.LibraryWorker
import com.lecteur.player.analysis.TrackProfile
import com.lecteur.player.analysis.dailyMix
import com.lecteur.player.analysis.genreFamily
import com.lecteur.player.analysis.momentLabel
import com.lecteur.player.analysis.momentMix
import com.lecteur.player.analysis.moodsForHour
import com.lecteur.player.analysis.orderForMix
import com.lecteur.player.analysis.recentPlays
import com.lecteur.player.analysis.weeklyDiscoveries
import com.lecteur.player.data.TrackGroup
import com.lecteur.player.data.countLabel
import com.lecteur.player.online.OnlineMetadata
import com.lecteur.player.online.CoverCandidate
import com.lecteur.player.online.LyricsCandidate
import com.lecteur.player.lyrics.MetadataChanges
import com.lecteur.player.data.forgetCover
import com.lecteur.player.widget.ReaperWidget
import com.lecteur.player.playback.MixDirector
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.channels.awaitClose
import com.lecteur.player.account.AccountRepository
import com.lecteur.player.account.AccountUser
import com.lecteur.player.account.SignInResult
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.player.data.AudioLibrary
import com.lecteur.player.data.CoverLoader
import com.lecteur.player.data.SharedLibrary
import com.lecteur.player.data.Track
import com.lecteur.player.data.WaveformExtractor
import com.lecteur.player.history.HistoryDatabase
import com.lecteur.player.history.Recap
import com.lecteur.player.history.SavedPlaylist
import com.lecteur.player.history.RecapPeriod
import com.lecteur.player.history.TrackStats
import com.lecteur.player.history.computeRecap
import com.lecteur.player.history.periodStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import java.time.ZoneId
import com.lecteur.player.jam.JamManager
import com.lecteur.player.lyrics.FoundLyrics
import com.lecteur.player.lyrics.LyricsRepository
import com.lecteur.player.playback.PlayerConnection
import com.lecteur.player.playback.QueueItem
import com.lecteur.player.ui.components.DotGrid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

data class AccountUiState(
    val user: AccountUser? = null,
    /** Vrai pendant la connexion. */
    val busy: Boolean = false,
    /** Dernière erreur à afficher, ou null. */
    val error: String? = null,
)

sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object None : LyricsUiState
    data class Found(val result: FoundLyrics) : LyricsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class LecteurViewModel(app: Application) : AndroidViewModel(app) {

    private val library = AudioLibrary(app)
    private val covers = CoverLoader(app)
    private val waveforms = WaveformExtractor(app)
    private val lyricsRepository = LyricsRepository(app)


    val player = PlayerConnection(app).apply { connect() }

    private val permissionGranted = MutableStateFlow(false)

    /** null tant que la permission n'est pas accordée ou que le premier scan n'est pas fini. */
    val tracks: StateFlow<List<Track>?> = permissionGranted
        .flatMapLatest { granted -> if (granted) library.tracks() else flowOf(null) }
        .onEach { list -> list?.let(SharedLibrary::publish) }
        // Un écran ouvert après les autres (écran de verrouillage) repart de la liste déjà lue.
        .stateIn(viewModelScope, SharingStarted.Eagerly, SharedLibrary.tracks.value)

    val currentTrack: StateFlow<Track?> =
        combine(tracks, snapshotFlow { player.state.mediaId }) { list, mediaId ->
            mediaId?.let { id -> list?.firstOrNull { it.id.toString() == id } }
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null : pas de pochette dans le fichier, ou chargement en cours. */
    val currentCover: StateFlow<ImageBitmap?> = combine(
        currentTrack.distinctUntilChanged { a, b -> a?.albumId == b?.albumId },
        MetadataChanges.version,
    ) { track, _ -> track }
        .mapLatest { track -> track?.let { covers.cover(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null pendant le décodage : l'écran affiche alors une ligne de points plate. */
    val currentWaveform: StateFlow<FloatArray?> = currentTrack
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .transformLatest { track ->
            emit(null)
            if (track != null) emit(waveforms.waveform(track))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** La file de lecture, chaque élément relié à son morceau de la bibliothèque. */
    val queue: StateFlow<List<QueueItem>> =
        combine(tracks, snapshotFlow { player.state.queueIds }) { list, ids ->
            val byId = list.orEmpty().associateBy { it.id.toString() }
            ids.mapIndexed { index, id -> QueueItem(index, id, byId[id]) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Nombre d'écoutes et dernière écoute de chaque morceau, par identifiant. */
    private val history = HistoryDatabase.get(app).plays()
    private val playlistDao = HistoryDatabase.get(app).playlists()

    /** Listes enregistrées par l'utilisateur (récaps de jam…), les plus récentes d'abord. */
    val savedPlaylists: StateFlow<List<SavedPlaylist>> =
        combine(playlistDao.playlistsFlow(), playlistDao.tracksFlow()) { playlists, entries ->
            val byPlaylist = entries.groupBy { it.playlistId }
            playlists.map { p -> SavedPlaylist(p.id, p.name, p.createdAt, byPlaylist[p.id].orEmpty().map { it.trackId }) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlistDao.delete(id) }
    }

    /** Titres aimés : ceux de la liste « Favoris ». */
    val favoriteIds: StateFlow<Set<Long>> = savedPlaylists
        .map { com.lecteur.player.history.favoriteIds(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Cœur : ajoute le titre aux Favoris (créés au premier titre aimé) ou l'en retire. */
    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch { playlistDao.toggleFavorite(trackId, System.currentTimeMillis()) }
    }

    val stats: StateFlow<Map<Long, TrackStats>> = history.statsFlow()
        .map { list -> list.associateBy { it.trackId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val recapPeriod = MutableStateFlow(RecapPeriod.Year)

    /** Bilan d'écoute de la période choisie ; null pendant le premier calcul. */
    val recap: StateFlow<Recap?> = recapPeriod
        .flatMapLatest { period ->
            val zone = ZoneId.systemDefault()
            combine(history.playsSince(periodStart(period, System.currentTimeMillis(), zone)), tracks) { plays, list ->
                computeRecap(plays, list.orEmpty().associateBy { it.id }, zone)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lyricsFolder: StateFlow<Uri?> = lyricsRepository.folder

    fun lyricsFolderLabel(uri: Uri): String = lyricsRepository.folderLabel(uri)

    /** Paroles du morceau en cours ; recherchées à nouveau quand le dossier des .lrc change. */
    val currentLyrics: StateFlow<LyricsUiState> =
        combine(currentTrack.distinctUntilChanged { a, b -> a?.id == b?.id }, lyricsRepository.folder, MetadataChanges.version) { track, _, _ -> track }
            .transformLatest { track ->
                if (track == null) {
                    emit(LyricsUiState.None)
                } else {
                    emit(LyricsUiState.Loading)
                    emit(lyricsRepository.lyricsFor(track)?.let { LyricsUiState.Found(it) } ?: LyricsUiState.None)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsUiState.None)

    private val onlineMetadata = OnlineMetadata(app)

    /** Recherche manuelle de paroles sur LRCLIB ; null si la recherche a échoué (réseau). */
    suspend fun searchLyrics(title: String, artist: String): List<LyricsCandidate>? =
        if (!OnlineMetadata.isOnline(getApplication())) null else runCatching { onlineMetadata.searchLyrics(title, artist) }.getOrNull()

    /** Garde les paroles choisies pour le morceau en cours ; elles passent désormais avant toutes les autres. */
    suspend fun chooseLyrics(candidate: LyricsCandidate) {
        val track = currentTrack.value ?: return
        lyricsRepository.choose(track, candidate.text)
        MetadataChanges.notifyChanged()
    }

    /** Recherche manuelle de pochettes (MusicBrainz, Cover Art Archive) ; null si la recherche a échoué. */
    suspend fun searchCovers(artist: String, album: String): List<CoverCandidate>? =
        if (!OnlineMetadata.isOnline(getApplication())) null else runCatching { onlineMetadata.searchCovers(artist, album) }.getOrNull()

    /** Garde la pochette choisie pour tout l'album du morceau en cours. Renvoie vrai si c'est fait. */
    suspend fun chooseCover(candidate: CoverCandidate): Boolean {
        val track = currentTrack.value ?: return false
        if (!runCatching { onlineMetadata.chooseCover(track.albumId, candidate) }.getOrDefault(false)) return false
        forgetCover(track.albumId)
        ReaperWidget.forgetCover()
        ReaperWidget.refresh(getApplication())
        MetadataChanges.notifyChanged()
        return true
    }

    /** Relance tout de suite la recherche en ligne des pochettes et paroles manquantes, y compris celles déjà tentées. */
    fun rescanOnline() {
        viewModelScope.launch { LibraryWorker.rescanOnline(getApplication()) }
    }

    fun setLyricsFolder(uri: Uri) {
        runCatching { lyricsRepository.setFolder(uri) }
    }

    private val accountRepository = AccountRepository(app)
    private val accountProgress = MutableStateFlow(AccountUiState())

    /** Compte Google connecté (ou non), avec l'état de la dernière tentative de connexion. */
    val account: StateFlow<AccountUiState> =
        combine(accountRepository.user, accountProgress) { user, progress -> progress.copy(user = user) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AccountUiState())

    fun signIn(activity: Activity) {
        if (accountProgress.value.busy) return
        viewModelScope.launch {
            accountProgress.value = AccountUiState(busy = true)
            val result = accountRepository.signInWithGoogle(activity)
            accountProgress.value = AccountUiState(error = (result as? SignInResult.Failure)?.message)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            accountRepository.signOut()
            accountProgress.value = AccountUiState()
        }
    }

    // --- Analyse, classement, listes du jour, mode Mix -------------------------------------------

    private val featuresDao = HistoryDatabase.get(app).features()

    /** Tempo, tonalité, humeur et genre de chaque morceau analysé. */
    val profiles: StateFlow<Map<Long, TrackProfile>> =
        combine(tracks, featuresDao.featuresFlow()) { list, features ->
            val byId = features.associateBy { it.trackId }
            list.orEmpty().associate { t ->
                val entry = byId[t.id]
                t.id to TrackProfile(t, entry?.toFeatures(), entry?.genre ?: genreFamily(t.genreTag))
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Analyse de la bibliothèque en cours (null si rien ne tourne). */
    val analysisProgress: StateFlow<LibraryProgress?> = LibraryWorker.progress(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Vrai pendant une recherche relancée des pochettes et paroles manquantes. */
    val onlineRescanRunning: StateFlow<Boolean> = LibraryWorker.onlineRescanRunning(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Heure courante, à l'heure pile : les listes du moment changent d'heure en heure. */
    private val hourTicker = kotlinx.coroutines.flow.flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now.truncatedTo(ChronoUnit.HOURS))
            kotlinx.coroutines.delay(Duration.between(now, now.truncatedTo(ChronoUnit.HOURS).plusHours(1)).toMillis() + 1_000)
        }
    }

    /** « Mix du jour », « Découvertes de la semaine » et la liste du moment. */
    val discoveryGroups: StateFlow<List<TrackGroup>> =
        combine(profiles, stats, history.playsSince(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000), hourTicker) { profiles, stats, plays, now ->
            val all = profiles.values.toList()
            val date = now.toLocalDate()
            val daily = dailyMix(all, recentPlays(plays, System.currentTimeMillis(), 30), date)
            val weekly = weeklyDiscoveries(all, stats, recentPlays(plays, System.currentTimeMillis(), 30), date)
            val moment = momentMix(all, now.hour, date)
            listOf(
                TrackGroup("disc:daily", "Mix du jour", "Vos favoris du moment et des titres proches, enchaînés en mix, ${countLabel(daily.size, "titre", "titres")}", daily.map { it.track }),
                TrackGroup("disc:weekly", "Découvertes de la semaine", "Peu ou pas écoutés, proches de vos goûts, ${countLabel(weekly.size, "titre", "titres")}", weekly.map { it.track }),
                TrackGroup(
                    "disc:moment",
                    momentLabel(now.hour),
                    moodsForHour(now.hour).joinToString(", ") { it.label.lowercase() }.replaceFirstChar { it.titlecase() } + ", ${countLabel(moment.size, "titre", "titres")}",
                    moment.map { it.track },
                ),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Profil du morceau en cours (tempo, tonalité, humeur). */
    val currentProfile: StateFlow<TrackProfile?> =
        combine(currentTrack, profiles) { track, profiles -> track?.let { profiles[it.id] } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mixActive: StateFlow<Boolean> = MixDirector.active

    /** Joue [tracks] en lecture aléatoire, en partant d'un titre au hasard. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        player.play(tracks, tracks.indices.random(), shuffle = true)
    }

    /** Joue [tracks] en mode Mix : ordre harmonique (différent à chaque fois) et transitions calées sur le tempo. */
    fun playMix(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val known = profiles.value
        // Ordre harmonieux mais différent à chaque mix : départ au hasard, suivant tiré parmi les plus compatibles.
        val ordered = orderForMix(tracks.map { known[it.id] ?: TrackProfile(it, null, null) }, kotlin.random.Random.Default)
        player.play(ordered.map { it.track }, 0, mix = true)
    }

    /** Connexion internet disponible (la jam et les téléchargements en ont besoin, le reste non). */
    val online: StateFlow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(OnlineMetadata.isOnline(app)) }
            override fun onLost(network: Network) { trySend(OnlineMetadata.isOnline(app)) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(OnlineMetadata.isOnline(app)) }
        }
        trySend(OnlineMetadata.isOnline(app))
        manager?.registerDefaultNetworkCallback(callback)
        awaitClose { manager?.unregisterNetworkCallback(callback) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // Chaque changement de la bibliothèque relance l'analyse des nouveaux morceaux (sans doublon).
        viewModelScope.launch {
            tracks.collect { list -> if (!list.isNullOrEmpty()) LibraryWorker.schedule(getApplication()) }
        }
    }

    private val _notice = MutableStateFlow<String?>(null)
    /** Message bref à afficher (enregistrement réussi…), effacé tout seul. */
    val notice: StateFlow<String?> = _notice

    fun showNotice(message: String) {
        _notice.value = message
        viewModelScope.launch {
            kotlinx.coroutines.delay(4_000)
            if (_notice.value == message) _notice.value = null
        }
    }

    fun onPermissionChanged(granted: Boolean) {
        permissionGranted.value = granted
        // La jam a besoin de la bibliothèque : on la démarre une fois l'accès aux fichiers audio accordé.
        if (granted) JamManager.init(getApplication())
    }

    fun play(queue: List<Track>, startIndex: Int) = player.play(queue, startIndex)

    override fun onCleared() {
        player.release()
    }
}
