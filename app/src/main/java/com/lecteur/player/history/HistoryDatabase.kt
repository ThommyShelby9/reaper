package com.lecteur.player.history

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import com.lecteur.player.analysis.FeaturesDao
import com.lecteur.player.analysis.OnlineLookupEntity
import com.lecteur.player.analysis.TrackFeaturesEntity

/** Une écoute comptée comme « jouée » (voir [countsAsPlay]). */
@Entity(tableName = "plays", indices = [Index("track_id"), Index("played_at")])
data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    /** Début de l'écoute, en millisecondes depuis 1970. */
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

@Dao
interface PlayDao {
    @Insert
    suspend fun insert(play: PlayEntity)

    @Query("SELECT track_id AS trackId, COUNT(*) AS plays, MAX(played_at) AS lastPlayedMs FROM plays GROUP BY track_id")
    fun statsFlow(): Flow<List<TrackStats>>

    @Query("SELECT * FROM plays WHERE played_at >= :fromMs")
    fun playsSince(fromMs: Long): Flow<List<PlayEntity>>

    @Query("SELECT * FROM plays")
    suspend fun all(): List<PlayEntity>
}

/** Une liste enregistrée par l'utilisateur (par exemple le récap d'une jam). */
@Entity(tableName = "playlists")
data class SavedPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "position"],
    indices = [Index("playlist_id")],
)
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val position: Int,
    @ColumnInfo(name = "track_id") val trackId: Long,
)

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insertPlaylist(playlist: SavedPlaylistEntity): Long

    @Insert
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :id")
    suspend fun deleteTracks(id: Long)

    @Query("SELECT * FROM playlists ORDER BY created_at DESC")
    fun playlistsFlow(): Flow<List<SavedPlaylistEntity>>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlist_id, position")
    fun tracksFlow(): Flow<List<PlaylistTrackEntity>>

    @Transaction
    suspend fun create(name: String, trackIds: List<Long>, createdAt: Long): Long {
        val id = insertPlaylist(SavedPlaylistEntity(name = name, createdAt = createdAt))
        insertTracks(trackIds.mapIndexed { index, trackId -> PlaylistTrackEntity(id, index, trackId) })
        return id
    }

    @Transaction
    suspend fun delete(id: Long) {
        deleteTracks(id)
        deletePlaylist(id)
    }

    @Query("SELECT id FROM playlists WHERE name = :name ORDER BY created_at LIMIT 1")
    suspend fun idByName(name: String): Long?

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlist_id = :id")
    suspend fun lastPosition(id: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :id AND track_id = :trackId")
    suspend fun countTrack(id: Long, trackId: Long): Int

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :id AND track_id = :trackId")
    suspend fun removeTrack(id: Long, trackId: Long)

    /**
     * Aime ou n'aime plus [trackId]. La liste « Favoris » est créée au premier titre aimé
     * (et recréée si l'utilisateur l'a supprimée). Renvoie vrai si le titre est désormais aimé.
     */
    @Transaction
    suspend fun toggleFavorite(trackId: Long, now: Long): Boolean {
        val id = idByName(FAVORITES_NAME) ?: insertPlaylist(SavedPlaylistEntity(name = FAVORITES_NAME, createdAt = now))
        if (countTrack(id, trackId) > 0) {
            removeTrack(id, trackId)
            return false
        }
        insertTracks(listOf(PlaylistTrackEntity(id, lastPosition(id) + 1, trackId)))
        return true
    }
}

/** Nom de la liste des titres aimés, créée toute seule au premier cœur. */
const val FAVORITES_NAME = "Favoris"

@Database(
    entities = [PlayEntity::class, SavedPlaylistEntity::class, PlaylistTrackEntity::class, TrackFeaturesEntity::class, OnlineLookupEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun plays(): PlayDao
    abstract fun playlists(): PlaylistDao
    abstract fun features(): FeaturesDao

    companion object {
        @Volatile private var instance: HistoryDatabase? = null

        /** Version 2 : ajout des listes enregistrées, sans toucher à l'historique d'écoute. */
        private val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlist_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "`track_id` INTEGER NOT NULL, PRIMARY KEY(`playlist_id`, `position`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlist_id` ON `playlist_tracks` (`playlist_id`)")
            }
        }

        /** Version 3 : caractéristiques audio des morceaux et suivi des recherches en ligne. */
        private val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_features` (`track_id` INTEGER NOT NULL, `date_added` INTEGER NOT NULL, " +
                        "`bpm` REAL, `beat_offset_ms` INTEGER, `beat_strength` REAL, `key` INTEGER, `minor` INTEGER, `energy` REAL, " +
                        "`brightness` REAL, `genre` TEXT, `analyzed_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`track_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `online_lookups` (`track_id` INTEGER NOT NULL, `cover_done` INTEGER NOT NULL, " +
                        "`lyrics_done` INTEGER NOT NULL, `tags_done` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`track_id`))",
                )
            }
        }

        /** Une seule base pour tout le processus : le service l'écrit, l'interface la lit. */
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "historique.db")
                .addMigrations(Migration1To2, Migration2To3)
                .build()
                .also { instance = it }
        }
    }
}
