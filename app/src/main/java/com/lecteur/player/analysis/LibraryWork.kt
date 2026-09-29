package com.lecteur.player.analysis

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lecteur.player.data.AudioLibrary
import com.lecteur.player.data.Track
import com.lecteur.player.data.contentUri
import com.lecteur.player.history.HistoryDatabase
import com.lecteur.player.online.OnlineMetadata
import com.lecteur.player.playback.PlaybackSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Progression de l'analyse de la bibliothèque. */
data class LibraryProgress(val analyzed: Int, val total: Int)

/**
 * Travail de fond sur la bibliothèque, confié à WorkManager (il survit à la fermeture de l'app) :
 * 1. analyse audio des morceaux nouveaux ou remplacés (tempo, tonalité, énergie, genre du fichier) ;
 * 2. si l'utilisateur l'accepte et que le réseau le permet : pochettes, genres et paroles manquants.
 * Chaque passage dure au plus 8 minutes ; s'il reste du travail, il se relance lui-même.
 */
class LibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        PlaybackSettings.init(context)
        val tracks = runCatching { AudioLibrary(context).tracks().first() }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return Result.success()
        val dao = HistoryDatabase.get(context).features()
        val deadline = System.currentTimeMillis() + 8 * 60_000
        // Relance demandée par l'utilisateur : seulement les pochettes et paroles, sans attendre la fin de l'analyse.
        val onlineOnly = inputData.getBoolean(KEY_ONLINE_ONLY, false)

        // 1. Analyse audio, hors ligne.
        val known = dao.allFeatures().associateBy { it.trackId }
        val todo = if (onlineOnly) emptyList() else tracks.filter { t ->
            val f = known[t.id]
            f == null || f.dateAddedSec != t.dateAddedSec || f.version < ANALYSIS_VERSION
        }
        val analyzer = AudioAnalyzer(context)
        var analyzed = tracks.size - todo.size
        var remaining = false
        // Le décodeur du téléphone traite un morceau à son rythme : on en décode plusieurs à la fois,
        // un par cœur de processeur disponible (au plus PARALLEL_ANALYSES).
        for (batch in todo.chunked(PARALLEL_ANALYSES)) {
            if (isStopped || System.currentTimeMillis() > deadline) {
                remaining = true
                break
            }
            coroutineScope {
                batch.map { track ->
                    async {
                        val started = System.currentTimeMillis()
                        val features = runCatching { analyzer.analyze(track) }.getOrNull()
                        Log.d(TAG, "Analyse ${track.id} : ${System.currentTimeMillis() - started} ms, ${features?.bpm?.let { "%.0f BPM".format(it) } ?: "échec"}")
                        dao.upsert(entityOf(track, features, genreFamily(track.genreTag) ?: known[track.id]?.genre))
                    }
                }.awaitAll()
            }
            analyzed += batch.size
            setProgress(workDataOf(KEY_DONE to analyzed, KEY_TOTAL to tracks.size))
        }

        // 2. Compléments en ligne (jamais sur les données mobiles si l'utilisateur l'a demandé).
        val prefs = PlaybackSettings.state.value
        if (!remaining && (prefs.onlineCovers || prefs.onlineLyrics) && OnlineMetadata.isOnline(context, prefs.onlineWifiOnly)) {
            remaining = enrichOnline(context, tracks, deadline)
        }

        if (remaining && !isStopped) {
            if (onlineOnly) enqueueOnline(context, ExistingWorkPolicy.APPEND_OR_REPLACE) else enqueueContinuation(context)
        }
        return Result.success()
    }

    /** Renvoie vrai s'il reste des recherches à faire au prochain passage. */
    private suspend fun enrichOnline(context: Context, tracks: List<Track>, deadline: Long): Boolean {
        val online = OnlineMetadata(context)
        val dao = HistoryDatabase.get(context).features()
        val prefs = PlaybackSettings.state.value
        val lookups = dao.allLookups().associateBy { it.trackId }.toMutableMap()
        val features = dao.allFeatures().associateBy { it.trackId }
        val retryAfter = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        fun due(trackId: Long, done: (com.lecteur.player.analysis.OnlineLookupEntity) -> Boolean): Boolean {
            val lookup = lookups[trackId] ?: return true
            return !done(lookup) || lookup.updatedAt < retryAfter
        }
        suspend fun mark(trackId: Long, change: (com.lecteur.player.analysis.OnlineLookupEntity) -> com.lecteur.player.analysis.OnlineLookupEntity) {
            val updated = change(lookups[trackId] ?: OnlineLookupEntity(trackId)).copy(updatedAt = System.currentTimeMillis())
            lookups[trackId] = updated
            dao.upsertLookup(updated)
        }
        fun outOfTime() = isStopped || System.currentTimeMillis() > deadline

        // Pochettes : un morceau par album, seulement si le fichier n'en a pas.
        if (prefs.onlineCovers) {
            for (track in tracks.distinctBy { it.albumId }) {
                if (outOfTime()) return true
                if (!due(track.id) { it.coverDone } || online.coverFile(track.albumId).exists()) continue
                if (!hasEmbeddedCover(context, track)) runCatching { online.fetchCover(track) }
                mark(track.id) { it.copy(coverDone = true) }
            }
        }
        // Genres : pour les morceaux sans genre dans le fichier.
        for (track in tracks) {
            if (outOfTime()) return true
            if (features[track.id]?.genre != null || !due(track.id) { it.tagsDone }) continue
            val tags = runCatching { online.fetchTags(track) }.getOrDefault(emptyList())
            val family = tags.firstNotNullOfOrNull { genreFamily(it) }
            if (family != null) dao.setGenre(track.id, family)
            mark(track.id) { it.copy(tagsDone = true) }
        }
        // Paroles : d'abord les morceaux écoutés, puis le reste.
        if (prefs.onlineLyrics) {
            val played = HistoryDatabase.get(context).plays().all().map { it.trackId }.toSet()
            for (track in tracks.sortedByDescending { it.id in played }) {
                if (outOfTime()) return true
                if (!due(track.id) { it.lyricsDone } || online.lyricsFile(track.id).exists()) continue
                runCatching { online.fetchLyrics(track) }
                mark(track.id) { it.copy(lyricsDone = true) }
            }
        }
        return false
    }

    private fun hasEmbeddedCover(context: Context, track: Track): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.contentResolver.loadThumbnail(track.contentUri, Size(64, 64), null) }.isSuccess
        } else {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.contentUri)
                retriever.embeddedPicture != null
            } catch (_: Exception) {
                false
            } finally {
                retriever.release()
            }
        }

    private fun entityOf(track: Track, f: AudioFeatures?, genre: String?) = TrackFeaturesEntity(
        trackId = track.id,
        dateAddedSec = track.dateAddedSec,
        bpm = f?.bpm,
        beatOffsetMs = f?.beatOffsetMs,
        beatStrength = f?.beatStrength,
        key = f?.key,
        minor = f?.minor,
        energy = f?.energy,
        brightness = f?.brightness,
        genre = genre,
        analyzedAt = System.currentTimeMillis(),
        version = ANALYSIS_VERSION,
    )

    companion object {
        private const val TAG = "ReaperAnalyse"
        /** Analyses menées de front ; le téléphone a assez de cœurs, et chacune attend surtout le décodeur. */
        private val PARALLEL_ANALYSES = Runtime.getRuntime().availableProcessors().div(2).coerceIn(1, 4)
        private const val WORK_NAME = "bibliotheque"
        private const val DAILY_NAME = "bibliotheque-quotidien"
        private const val KEY_DONE = "analyses"
        private const val KEY_TOTAL = "total"

        private val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        /** Lance l'analyse si elle ne tourne pas déjà, et programme un passage quotidien. */
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryWorker>().setConstraints(constraints).build(),
            )
            manager.enqueueUniquePeriodicWork(
                DAILY_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LibraryWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build(),
            )
        }

        private const val ONLINE_NAME = "complements-en-ligne"
        private const val KEY_ONLINE_ONLY = "en_ligne_seulement"

        /**
         * Relance demandée depuis les réglages : oublie les recherches déjà tentées (même celles qui n'avaient
         * rien donné) et cherche tout de suite les pochettes et paroles manquantes, dès qu'il y a du réseau.
         */
        suspend fun rescanOnline(context: Context) {
            HistoryDatabase.get(context).features().clearLookups()
            enqueueOnline(context, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueueOnline(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONLINE_NAME,
                policy,
                OneTimeWorkRequestBuilder<LibraryWorker>()
                    .setInputData(workDataOf(KEY_ONLINE_ONLY to true))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        /** Vrai tant que la relance des compléments en ligne tourne ou attend le réseau. */
        fun onlineRescanRunning(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ONLINE_NAME).map { infos ->
                infos.any { !it.state.isFinished }
            }

        private fun enqueueContinuation(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<LibraryWorker>().setConstraints(constraints).build(),
            )
        }

        /** Progression de l'analyse en cours, ou null si rien ne tourne. */
        fun progress(context: Context): Flow<LibraryProgress?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { data ->
                    val total = data.getInt(KEY_TOTAL, 0)
                    if (total > 0) LibraryProgress(data.getInt(KEY_DONE, 0), total) else null
                }
            }
    }
}
