package com.lecteur.player.analysis

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Version de l'algorithme d'analyse : l'augmenter relance l'analyse de toute la bibliothèque. */
const val ANALYSIS_VERSION = 1

/** Résultat de l'analyse d'un morceau (null partout si l'analyse a échoué). */
@Entity(tableName = "track_features")
data class TrackFeaturesEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    /** Date d'ajout du fichier au moment de l'analyse : si elle change, le fichier a été remplacé. */
    @ColumnInfo(name = "date_added") val dateAddedSec: Long,
    val bpm: Float?,
    @ColumnInfo(name = "beat_offset_ms") val beatOffsetMs: Long?,
    @ColumnInfo(name = "beat_strength") val beatStrength: Float?,
    val key: Int?,
    val minor: Boolean?,
    val energy: Float?,
    val brightness: Float?,
    /** Famille de genre retenue (étiquette du fichier, ou tags en ligne). */
    val genre: String?,
    @ColumnInfo(name = "analyzed_at") val analyzedAt: Long,
    val version: Int,
) {
    fun toFeatures(): AudioFeatures? {
        return AudioFeatures(
            bpm = bpm ?: return null,
            beatOffsetMs = beatOffsetMs ?: 0L,
            beatStrength = beatStrength ?: 0f,
            key = key ?: return null,
            minor = minor ?: false,
            energy = energy ?: 0f,
            brightness = brightness ?: 0f,
        )
    }
}

/** Ce qui a déjà été cherché en ligne pour un morceau, pour ne pas redemander sans cesse. */
@Entity(tableName = "online_lookups")
data class OnlineLookupEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "cover_done") val coverDone: Boolean = false,
    @ColumnInfo(name = "lyrics_done") val lyricsDone: Boolean = false,
    @ColumnInfo(name = "tags_done") val tagsDone: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

@Dao
interface FeaturesDao {
    @Upsert
    suspend fun upsert(features: TrackFeaturesEntity)

    @Query("SELECT * FROM track_features")
    fun featuresFlow(): Flow<List<TrackFeaturesEntity>>

    @Query("SELECT * FROM track_features")
    suspend fun allFeatures(): List<TrackFeaturesEntity>

    @Query("UPDATE track_features SET genre = :genre WHERE track_id = :trackId")
    suspend fun setGenre(trackId: Long, genre: String?)

    @Upsert
    suspend fun upsertLookup(lookup: OnlineLookupEntity)

    @Query("SELECT * FROM online_lookups")
    suspend fun allLookups(): List<OnlineLookupEntity>

    /** Oublie les recherches en ligne déjà faites : tout sera recherché à nouveau. */
    @Query("DELETE FROM online_lookups")
    suspend fun clearLookups()

    @Query("SELECT * FROM online_lookups WHERE track_id = :trackId")
    suspend fun lookup(trackId: Long): OnlineLookupEntity?
}
