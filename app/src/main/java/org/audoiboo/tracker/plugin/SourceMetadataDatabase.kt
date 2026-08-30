package org.audoiboo.tracker.plugin

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "source_installations")
data class SourceInstallationEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val name: String,
    val pluginVersion: Int,
    val apiVersion: Int,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "series_sources",
    primaryKeys = ["canonicalSeriesId", "sourceId"],
    indices = [Index("sourceId"), Index(value = ["sourceId", "remoteKey"], unique = true), Index(value = ["sourceId", "url"], unique = true)]
)
data class SeriesSourceEntity(
    val canonicalSeriesId: String,
    val sourceId: String,
    val remoteKey: String,
    val url: String,
    val remoteTitle: String? = null,
    val relationship: String = "SAME_SERIES",
    val confidence: Float = 1f,
    val userVerified: Boolean = false,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long? = null
)

@Entity(
    tableName = "book_sources",
    indices = [
        Index("canonicalBookId"),
        Index("canonicalSeriesId"),
        Index("sourceId"),
        Index(value = ["sourceId", "remoteKey"], unique = true),
        Index(value = ["sourceId", "url"], unique = true)
    ]
)
data class BookSourceEntity(
    @androidx.room.PrimaryKey val key: String,
    val canonicalBookId: String?,
    val canonicalSeriesId: String?,
    val sourceId: String,
    val remoteKey: String,
    val url: String,
    val remoteTitle: String? = null,
    val remoteAuthor: String? = null,
    val remoteOrder: Double? = null,
    val confidence: Float = 1f,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long? = null
)

@Entity(
    tableName = "source_availability",
    primaryKeys = ["bookSourceKey", "type"],
    indices = [Index("sourceId"), Index("status"), Index("lastSeenAt")]
)
data class SourceAvailabilityEntity(
    val bookSourceKey: String,
    val sourceId: String,
    val type: String,
    val status: String,
    val uri: String? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "series_match_decisions",
    primaryKeys = ["canonicalSeriesId", "sourceId", "remoteKey"],
    indices = [Index("sourceId"), Index("decision")]
)
data class SeriesMatchDecisionEntity(
    val canonicalSeriesId: String,
    val sourceId: String,
    val remoteKey: String,
    val decision: String,
    val relationship: String? = null,
    val confidence: Float? = null,
    val decidedAt: Long = System.currentTimeMillis()
)

@Dao
interface SourceMetadataDao {
    @Query("SELECT * FROM source_installations ORDER BY name COLLATE NOCASE")
    fun observeInstallations(): Flow<List<SourceInstallationEntity>>

    @Query("SELECT * FROM source_installations WHERE sourceId=:sourceId LIMIT 1")
    suspend fun installation(sourceId: String): SourceInstallationEntity?

    @Upsert
    suspend fun upsertInstallation(value: SourceInstallationEntity)

    @Query("UPDATE source_installations SET enabled=:enabled, updatedAt=:updatedAt WHERE sourceId=:sourceId")
    suspend fun setEnabled(sourceId: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM series_sources WHERE canonicalSeriesId=:seriesId ORDER BY sourceId")
    suspend fun seriesSources(seriesId: String): List<SeriesSourceEntity>

    @Query("SELECT * FROM series_sources WHERE sourceId=:sourceId AND remoteKey=:remoteKey LIMIT 1")
    suspend fun seriesSource(sourceId: String, remoteKey: String): SeriesSourceEntity?

    @Query("SELECT * FROM series_sources WHERE sourceId=:sourceId AND url=:url LIMIT 1")
    suspend fun seriesSourceByUrl(sourceId: String, url: String): SeriesSourceEntity?

    @Upsert
    suspend fun upsertSeriesSource(value: SeriesSourceEntity)

    @Query("SELECT * FROM book_sources WHERE canonicalBookId=:bookId ORDER BY sourceId")
    suspend fun bookSources(bookId: String): List<BookSourceEntity>

    @Query("SELECT * FROM book_sources WHERE sourceId=:sourceId AND remoteKey=:remoteKey LIMIT 1")
    suspend fun bookSource(sourceId: String, remoteKey: String): BookSourceEntity?

    @Query("SELECT * FROM book_sources WHERE sourceId=:sourceId AND url=:url LIMIT 1")
    suspend fun bookSourceByUrl(sourceId: String, url: String): BookSourceEntity?

    @Upsert
    suspend fun upsertBookSource(value: BookSourceEntity)

    @Query("SELECT * FROM source_availability WHERE bookSourceKey=:bookSourceKey ORDER BY type")
    suspend fun availability(bookSourceKey: String): List<SourceAvailabilityEntity>

    @Upsert
    suspend fun upsertAvailability(value: SourceAvailabilityEntity)

    @Query("SELECT * FROM series_match_decisions WHERE canonicalSeriesId=:seriesId AND sourceId=:sourceId AND remoteKey=:remoteKey LIMIT 1")
    suspend fun matchDecision(seriesId: String, sourceId: String, remoteKey: String): SeriesMatchDecisionEntity?

    @Query("SELECT * FROM series_match_decisions WHERE sourceId=:sourceId AND remoteKey=:remoteKey ORDER BY decidedAt DESC")
    suspend fun matchDecisions(sourceId: String, remoteKey: String): List<SeriesMatchDecisionEntity>

    @Upsert
    suspend fun upsertMatchDecision(value: SeriesMatchDecisionEntity)

    @Query("DELETE FROM series_match_decisions WHERE canonicalSeriesId=:seriesId AND sourceId=:sourceId AND remoteKey=:remoteKey")
    suspend fun clearMatchDecision(seriesId: String, sourceId: String, remoteKey: String)

    @Transaction
    suspend fun registerPlugin(descriptor: SourceDescriptor, enabled: Boolean = true) {
        val old = installation(descriptor.id)
        upsertInstallation(
            SourceInstallationEntity(
                sourceId = descriptor.id,
                name = descriptor.name,
                pluginVersion = descriptor.version,
                apiVersion = descriptor.apiVersion,
                enabled = old?.enabled ?: enabled
            )
        )
    }
}

@Database(
    entities = [
        SourceInstallationEntity::class,
        SeriesSourceEntity::class,
        BookSourceEntity::class,
        SourceAvailabilityEntity::class,
        SeriesMatchDecisionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SourceMetadataDatabase : RoomDatabase() {
    abstract fun dao(): SourceMetadataDao

    companion object {
        @Volatile private var instance: SourceMetadataDatabase? = null

        fun get(context: Context): SourceMetadataDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SourceMetadataDatabase::class.java,
                "audoiboo-sources.db"
            ).build().also { instance = it }
        }
    }
}

object SourceKeys {
    fun remoteKey(remoteId: String?, url: String): String = remoteId?.trim().takeUnless { it.isNullOrBlank() } ?: normalizeUrl(url)

    fun bookSourceKey(sourceId: String, remoteKey: String): String = "$sourceId::$remoteKey"

    fun normalizeUrl(url: String): String = url.trim().removeSuffix("/")
}
