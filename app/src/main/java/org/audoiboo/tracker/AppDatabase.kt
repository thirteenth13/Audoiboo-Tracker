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
    @Query("SELECT * FROM books ORDER BY updatedAt DESC") fun pagedBooks(): PagingSource<Int, BookEntity>
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE") fun searchBooks(query: String): PagingSource<Int, BookEntity>
    @Query("SELECT DISTINCT books.* FROM books LEFT JOIN book_tags ON books.id=book_tags.bookId LEFT JOIN tags ON tags.id=book_tags.tagId WHERE books.title LIKE '%' || :query || '%' OR books.author LIKE '%' || :query || '%' OR tags.name LIKE '%' || :query || '%' ORDER BY books.title COLLATE NOCASE") fun searchBooksAndTags(query: String): PagingSource<Int, BookEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSeries(series: SeriesEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBooks(books: List<BookEntity>)
    @Query("DELETE FROM books WHERE seriesId = :seriesId") suspend fun deleteBooksForSeries(seriesId: String)
    @Query("DELETE FROM series WHERE id = :id") suspend fun deleteSeries(id: String)
    @Query("SELECT COUNT(*) FROM series") suspend fun seriesCount(): Int

    @Transaction @Query("SELECT * FROM books WHERE id=:bookId LIMIT 1") suspend fun bookWithTags(bookId: String): BookWithTags?
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE") fun observeTags(): Flow<List<TagEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(tag: TagEntity): Long
    @Query("SELECT id FROM tags WHERE name=:name COLLATE NOCASE LIMIT 1") suspend fun tagId(name: String): Long?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkTag(ref: BookTagCrossRef)
    @Query("DELETE FROM book_tags WHERE bookId=:bookId") suspend fun clearBookTags(bookId: String)
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM book_tags)") suspend fun deleteUnusedTags()

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
            deleteBooksForSeries(item.series.id)
            upsertBooks(item.books.mapIndexed { index, book -> book.copy(seriesId = item.series.id, sortIndex = index, updatedAt = now) })
        }
    }
}

@Database(entities = [SeriesEntity::class, BookEntity::class, TagEntity::class, BookTagCrossRef::class], version = 2, exportSchema = false)
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
        @Volatile private var instance: AudoibooDatabase? = null
        fun get(context: Context): AudoibooDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AudoibooDatabase::class.java, "audoiboo.db")
                .addMigrations(MIGRATION_1_2)
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
