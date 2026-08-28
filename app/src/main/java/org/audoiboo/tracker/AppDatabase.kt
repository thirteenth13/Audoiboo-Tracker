package org.audoiboo.tracker

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "series")
data class SeriesEntity(@PrimaryKey val id: String, val name: String, val url: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(
    tableName = "books",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("seriesId"), Index(value = ["url"], unique = true), Index("status")]
)
data class BookEntity(
    @PrimaryKey val id: String, val seriesId: String, val title: String, val url: String,
    val author: String?, val coverUrl: String?, val status: String, val archiveUrl: String?,
    val sortIndex: Int = 0, val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("bookId"), Index("tagId")]
)
data class BookTagCrossRef(val bookId: String, val tagId: Long)

@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(@PrimaryKey val position: Int, val dir: String)

@Entity(tableName = "playback_resume")
data class PlaybackResumeEntity(
    @PrimaryKey val key: String = "current",
    val dir: String,
    val title: String,
    val uri: String,
    val fileIndex: Int,
    val positionMs: Long,
    val updatedAt: Long
)

@Entity(tableName = "playback_history", indices = [Index("at")])
data class PlaybackHistoryEntity(@PrimaryKey val dir: String, val title: String, val at: Long)

@Entity(tableName = "player_bookmarks", indices = [Index("createdAt"), Index("uri")])
data class PlayerBookmarkEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val positionMs: Long,
    val note: String,
    val createdAt: Long
)

@Entity(tableName = "daily_listening")
data class DailyListeningEntity(@PrimaryKey val day: String, val listenedMs: Long)

@Entity(tableName = "listening_totals")
data class ListeningTotalEntity(@PrimaryKey val key: String = "total", val listenedMs: Long)

data class SeriesWithBooks(@Embedded val series: SeriesEntity, @Relation(parentColumn = "id", entityColumn = "seriesId") val books: List<BookEntity>)

data class BookWithTags(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(BookTagCrossRef::class, parentColumn = "bookId", entityColumn = "tagId"))
    val tags: List<TagEntity>
)

@Dao
interface LibraryDao {
    @Transaction @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE") fun observeLibrary(): Flow<List<SeriesWithBooks>>
    @Transaction @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE") suspend fun library(): List<SeriesWithBooks>
    @Query("SELECT * FROM series WHERE id=:id LIMIT 1") suspend fun seriesById(id: String): SeriesEntity?
    @Query("SELECT * FROM series WHERE url=:url LIMIT 1") suspend fun seriesByUrl(url: String): SeriesEntity?
    @Query("SELECT * FROM books ORDER BY updatedAt DESC") fun pagedBooks(): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE") fun searchBooks(query: String): PagingSource<Int, BookEntity>
    @Query("SELECT DISTINCT books.* FROM books LEFT JOIN book_tags ON books.id=book_tags.bookId LEFT JOIN tags ON tags.id=book_tags.tagId WHERE books.title LIKE '%' || :query || '%' OR books.author LIKE '%' || :query || '%' OR tags.name LIKE '%' || :query || '%' ORDER BY books.title COLLATE NOCASE") fun searchBooksAndTags(query: String): PagingSource<Int, BookEntity>
    @Query("""
        SELECT DISTINCT books.* FROM books
        LEFT JOIN book_tags ON books.id = book_tags.bookId
        LEFT JOIN tags ON tags.id = book_tags.tagId
        WHERE (:status = '' OR books.status = :status)
          AND (:tagMode = 0 OR (:tagMode = 1 AND book_tags.bookId IS NOT NULL) OR (:tagMode = 2 AND book_tags.bookId IS NULL))
          AND (:query = '' OR books.title LIKE '%' || :query || '%' OR books.author LIKE '%' || :query || '%' OR tags.name LIKE '%' || :query || '%')
        ORDER BY books.updatedAt DESC, books.title COLLATE NOCASE
    """) fun pagedFilteredBooks(query: String, status: String, tagMode: Int): PagingSource<Int, BookEntity>
    @Upsert suspend fun upsertSeries(series: SeriesEntity)
    @Upsert suspend fun upsertBooks(books: List<BookEntity>)
    @Query("DELETE FROM books WHERE seriesId = :seriesId AND id NOT IN (:keepIds)") suspend fun deleteMissingBooks(seriesId: String, keepIds: List<String>)
    @Query("DELETE FROM books WHERE seriesId = :seriesId") suspend fun deleteBooksForSeries(seriesId: String)
    @Query("DELETE FROM series WHERE id = :id") suspend fun deleteSeries(id: String)
    @Query("SELECT COUNT(*) FROM series") suspend fun seriesCount(): Int
    @Query("UPDATE books SET status=:status, updatedAt=:updatedAt WHERE id=:bookId") suspend fun updateBookStatus(bookId: String, status: String, updatedAt: Long = System.currentTimeMillis())
    @Query("UPDATE books SET archiveUrl=:archiveUrl, updatedAt=:updatedAt WHERE id=:bookId") suspend fun updateBookArchive(bookId: String, archiveUrl: String?, updatedAt: Long = System.currentTimeMillis())

    @Transaction @Query("SELECT * FROM books WHERE id=:bookId LIMIT 1") suspend fun bookWithTags(bookId: String): BookWithTags?
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE") fun observeTags(): Flow<List<TagEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(tag: TagEntity): Long
    @Query("SELECT id FROM tags WHERE name=:name COLLATE NOCASE LIMIT 1") suspend fun tagId(name: String): Long?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkTag(ref: BookTagCrossRef)
    @Query("DELETE FROM book_tags WHERE bookId=:bookId") suspend fun clearBookTags(bookId: String)
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM book_tags)") suspend fun deleteUnusedTags()

    @Query("SELECT * FROM playback_queue ORDER BY position") fun observePlaybackQueue(): Flow<List<PlaybackQueueEntity>>
    @Query("SELECT * FROM playback_queue ORDER BY position") suspend fun playbackQueue(): List<PlaybackQueueEntity>
    @Query("DELETE FROM playback_queue") suspend fun clearPlaybackQueue()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaybackQueue(items: List<PlaybackQueueEntity>)
    @Query("SELECT * FROM playback_resume WHERE `key`='current' LIMIT 1") suspend fun playbackResume(): PlaybackResumeEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaybackResume(value: PlaybackResumeEntity)

    @Query("SELECT * FROM playback_history ORDER BY at DESC LIMIT 50") fun observePlaybackHistory(): Flow<List<PlaybackHistoryEntity>>
    @Query("SELECT * FROM playback_history ORDER BY at DESC LIMIT 50") suspend fun playbackHistory(): List<PlaybackHistoryEntity>
    @Query("DELETE FROM playback_history") suspend fun clearPlaybackHistory()
    @Upsert suspend fun upsertPlaybackHistory(items: List<PlaybackHistoryEntity>)

    @Query("SELECT * FROM player_bookmarks ORDER BY createdAt DESC LIMIT 500") fun observePlayerBookmarks(): Flow<List<PlayerBookmarkEntity>>
    @Query("SELECT * FROM player_bookmarks ORDER BY createdAt DESC LIMIT 500") suspend fun playerBookmarks(): List<PlayerBookmarkEntity>
    @Query("DELETE FROM player_bookmarks") suspend fun clearPlayerBookmarks()
    @Upsert suspend fun upsertPlayerBookmarks(items: List<PlayerBookmarkEntity>)

    @Query("SELECT * FROM daily_listening ORDER BY day DESC") fun observeDailyListening(): Flow<List<DailyListeningEntity>>
    @Query("SELECT * FROM daily_listening ORDER BY day DESC") suspend fun dailyListening(): List<DailyListeningEntity>
    @Query("DELETE FROM daily_listening") suspend fun clearDailyListening()
    @Upsert suspend fun upsertDailyListening(items: List<DailyListeningEntity>)
    @Query("SELECT * FROM listening_totals WHERE `key`='total' LIMIT 1") suspend fun listeningTotal(): ListeningTotalEntity?
    @Upsert suspend fun upsertListeningTotal(value: ListeningTotalEntity)

    @Transaction
    suspend fun replacePlaybackQueue(dirs: List<String>) {
        clearPlaybackQueue()
        insertPlaybackQueue(dirs.distinct().mapIndexed { index, dir -> PlaybackQueueEntity(index, dir) })
    }

    @Transaction
    suspend fun replacePlayerExtras(
        history: List<PlaybackHistoryEntity>,
        bookmarks: List<PlayerBookmarkEntity>,
        daily: List<DailyListeningEntity>,
        totalMs: Long
    ) {
        clearPlaybackHistory()
        upsertPlaybackHistory(history.take(50))
        clearPlayerBookmarks()
        upsertPlayerBookmarks(bookmarks.take(500))
        clearDailyListening()
        upsertDailyListening(daily.take(120))
        upsertListeningTotal(ListeningTotalEntity(listenedMs = totalMs.coerceAtLeast(0L)))
    }

    @Transaction
    suspend fun setBookTags(bookId: String, names: List<String>) {
        clearBookTags(bookId)
        names.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.forEach { name ->
            val inserted = insertTag(TagEntity(name = name))
            val id = if (inserted > 0) inserted else tagId(name) ?: return@forEach
            linkTag(BookTagCrossRef(bookId, id))
        }
        deleteUnusedTags()
    }

    @Transaction
    suspend fun replaceLibrary(items: List<SeriesWithBooks>) {
        val currentIds = library().map { it.series.id }.toSet()
        val incomingIds = items.map { it.series.id }.toSet()
        (currentIds - incomingIds).forEach { deleteSeries(it) }
        val now = System.currentTimeMillis()
        items.forEach { item ->
            upsertSeries(item.series.copy(updatedAt = now))
            val normalized = item.books.mapIndexed { index, book -> book.copy(seriesId = item.series.id, sortIndex = index, updatedAt = now) }
            if (normalized.isEmpty()) deleteBooksForSeries(item.series.id)
            else {
                deleteMissingBooks(item.series.id, normalized.map { it.id })
                upsertBooks(normalized)
            }
        }
    }
}

@Database(
    entities = [
        SeriesEntity::class, BookEntity::class, TagEntity::class, BookTagCrossRef::class,
        PlaybackQueueEntity::class, PlaybackResumeEntity::class, PlaybackHistoryEntity::class,
        PlayerBookmarkEntity::class, DailyListeningEntity::class, ListeningTotalEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AudoibooDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `book_tags` (`bookId` TEXT NOT NULL, `tagId` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `tagId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_tags_bookId` ON `book_tags` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_tags_tagId` ON `book_tags` (`tagId`)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_queue` (`position` INTEGER NOT NULL, `dir` TEXT NOT NULL, PRIMARY KEY(`position`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_resume` (`key` TEXT NOT NULL, `dir` TEXT NOT NULL, `title` TEXT NOT NULL, `uri` TEXT NOT NULL, `fileIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_history` (`dir` TEXT NOT NULL, `title` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`dir`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_at` ON `playback_history` (`at`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `player_bookmarks` (`id` TEXT NOT NULL, `uri` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_player_bookmarks_createdAt` ON `player_bookmarks` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_player_bookmarks_uri` ON `player_bookmarks` (`uri`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `daily_listening` (`day` TEXT NOT NULL, `listenedMs` INTEGER NOT NULL, PRIMARY KEY(`day`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `listening_totals` (`key` TEXT NOT NULL, `listenedMs` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        @Volatile private var instance: AudoibooDatabase? = null
        fun get(context: Context): AudoibooDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AudoibooDatabase::class.java, "audoiboo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}

object LegacyLibraryImporter {
    private const val PREFS = "tracker"
    private const val KEY = "library"
    suspend fun importIfNeeded(context: Context) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        if (dao.seriesCount() > 0) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        val root = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until root.length()) {
            val s = root.optJSONObject(i) ?: continue
            val id = s.optString("id").takeIf { it.isNotBlank() } ?: continue
            dao.upsertSeries(SeriesEntity(id, s.optString("name"), s.optString("url")))
            val arr = s.optJSONArray("books") ?: JSONArray()
            dao.upsertBooks((0 until arr.length()).mapNotNull { j ->
                val b = arr.optJSONObject(j) ?: return@mapNotNull null
                val url = b.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BookEntity("$id::$url", id, b.optString("title"), url,
                    b.optString("author").takeIf { it.isNotBlank() && it != "null" },
                    b.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
                    b.optString("status", "NEW"),
                    b.optString("archiveUrl").takeIf { it.isNotBlank() && it != "null" }, j)
            })
        }
    }
}
