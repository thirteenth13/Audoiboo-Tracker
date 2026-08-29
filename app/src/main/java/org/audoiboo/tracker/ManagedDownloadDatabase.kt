package org.audoiboo.tracker

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "managed_downloads", indices = [Index("createdAt"), Index("state")])
internal data class ManagedDownloadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val series: String,
    val author: String?,
    val bookUrl: String,
    val archiveUrl: String,
    val relativeDir: String,
    val bookDir: String,
    val fileName: String,
    val state: String,
    val downloaded: Long,
    val total: Long,
    val error: String?,
    val createdAt: Long
)

@Dao
internal interface ManagedDownloadDao {
    @Query("SELECT * FROM managed_downloads ORDER BY createdAt DESC LIMIT 200")
    suspend fun all(): List<ManagedDownloadEntity>

    @Query("SELECT * FROM managed_downloads WHERE id=:id LIMIT 1")
    suspend fun byId(id: String): ManagedDownloadEntity?

    @Query("SELECT COUNT(*) FROM managed_downloads")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ManagedDownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(values: List<ManagedDownloadEntity>)

    @Query("DELETE FROM managed_downloads WHERE id=:id")
    suspend fun delete(id: String)

    @Query("DELETE FROM managed_downloads")
    suspend fun clear()

    @Query("DELETE FROM managed_downloads WHERE id NOT IN (SELECT id FROM managed_downloads ORDER BY createdAt DESC LIMIT 200)")
    suspend fun prune()
}

@Database(entities = [ManagedDownloadEntity::class], version = 1, exportSchema = false)
internal abstract class ManagedDownloadDatabase : RoomDatabase() {
    abstract fun dao(): ManagedDownloadDao

    companion object {
        @Volatile private var instance: ManagedDownloadDatabase? = null

        fun get(context: Context): ManagedDownloadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ManagedDownloadDatabase::class.java,
                "managed-downloads.db"
            ).build().also { instance = it }
        }
    }
}

internal object ManagedDownloadRoomStore {
    private const val LEGACY_PREFS = "managed_downloads"
    private const val LEGACY_KEY = "items"
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            runBlocking(Dispatchers.IO) { migrateLegacy(app) }
            initialized = true
        }
    }

    fun list(context: Context): List<ManagedDownloadRecord> = withDao(context) { dao ->
        dao.all().map { it.toRecord() }
    }

    fun get(context: Context, id: String): ManagedDownloadRecord? = withDao(context) { dao ->
        dao.byId(id)?.toRecord()
    }

    fun save(context: Context, record: ManagedDownloadRecord) {
        withDao(context) { dao ->
            dao.upsert(record.toEntity())
            dao.prune()
        }
    }

    fun delete(context: Context, id: String) {
        withDao(context) { dao -> dao.delete(id) }
    }

    fun exportJson(context: Context): JSONArray = JSONArray().also { out ->
        list(context).forEach { record -> out.put(record.toBackupJson()) }
    }

    fun isValidBackupPayload(value: Any?): Boolean {
        val raw = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: return false
        return parseCompleteArray(raw) != null
    }

    fun restoreJson(context: Context, value: Any?) {
        val raw = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: throw IllegalArgumentException("Backup downloads data is invalid")
        val records = parseCompleteArray(raw)
            ?: throw IllegalArgumentException("Backup downloads data contains invalid records")
        withDao(context) { dao ->
            dao.clear()
            if (records.isNotEmpty()) dao.upsertAll(records.take(200).map { it.toEntity() })
        }
    }

    private fun <T> withDao(context: Context, block: suspend (ManagedDownloadDao) -> T): T {
        initialize(context)
        val dao = ManagedDownloadDatabase.get(context.applicationContext).dao()
        return runBlocking(Dispatchers.IO) { block(dao) }
    }

    private suspend fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(LEGACY_KEY, null) ?: return
        val dao = ManagedDownloadDatabase.get(context).dao()
        val roomCount = dao.count()
        var legacyParsed = false

        if (roomCount == 0) {
            val items = parseLegacy(raw)
            if (items != null) {
                legacyParsed = true
                if (items.isNotEmpty()) {
                    dao.upsertAll(items.take(200).map { it.toEntity() })
                    dao.prune()
                }
            }
        }

        if (ManagedDownloadMigrationPolicy.shouldRemoveLegacy(roomCount, legacyParsed)) {
            prefs.edit().remove(LEGACY_KEY).commit()
        }
    }

    private fun parseLegacy(raw: String): List<ManagedDownloadRecord>? = runCatching {
        parseCompleteArray(JSONArray(raw))
    }.getOrNull()

    private fun parseCompleteArray(arr: JSONArray): List<ManagedDownloadRecord>? {
        val records = parseArray(arr)
        return records.takeIf {
            ManagedDownloadMigrationPolicy.payloadIsComplete(arr.length(), records.size)
        }
    }

    private fun parseArray(arr: JSONArray): List<ManagedDownloadRecord> =
        (0 until arr.length()).mapNotNull { index ->
            val o = arr.optJSONObject(index) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = o.optString("title")
            val state = runCatching { ManagedDownloadState.valueOf(o.optString("state")) }
                .getOrDefault(ManagedDownloadState.FAILED)
            ManagedDownloadRecord(
                id = id,
                title = title,
                series = o.optString("series"),
                author = o.optString("author").takeIf { it.isNotBlank() && it != "null" },
                bookUrl = o.optString("bookUrl"),
                archiveUrl = o.optString("archiveUrl"),
                relativeDir = o.optString("relativeDir"),
                bookDir = o.optString("bookDir").takeIf { it.isNotBlank() }
                    ?: sanitizeLegacyPath(title).take(120),
                fileName = o.optString("fileName"),
                state = state,
                downloaded = o.optLong("downloaded"),
                total = o.optLong("total", -1L),
                error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
                createdAt = o.optLong("createdAt").takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }

    private fun ManagedDownloadRecord.toBackupJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("series", series)
        put("author", author)
        put("bookUrl", bookUrl)
        put("archiveUrl", archiveUrl)
        put("relativeDir", relativeDir)
        put("bookDir", bookDir)
        put("fileName", fileName)
        put("state", state.name)
        put("downloaded", downloaded)
        put("total", total)
        put("error", error)
        put("createdAt", createdAt)
    }

    private fun sanitizeLegacyPath(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Unknown" }
}

internal fun ManagedDownloadRecord.toEntity() = ManagedDownloadEntity(
    id = id,
    title = title,
    series = series,
    author = author,
    bookUrl = bookUrl,
    archiveUrl = archiveUrl,
    relativeDir = relativeDir,
    bookDir = bookDir,
    fileName = fileName,
    state = state.name,
    downloaded = downloaded,
    total = total,
    error = error,
    createdAt = createdAt
)

internal fun ManagedDownloadEntity.toRecord() = ManagedDownloadRecord(
    id = id,
    title = title,
    series = series,
    author = author,
    bookUrl = bookUrl,
    archiveUrl = archiveUrl,
    relativeDir = relativeDir,
    bookDir = bookDir,
    fileName = fileName,
    state = runCatching { ManagedDownloadState.valueOf(state) }.getOrDefault(ManagedDownloadState.FAILED),
    downloaded = downloaded,
    total = total,
    error = error,
    createdAt = createdAt
)
