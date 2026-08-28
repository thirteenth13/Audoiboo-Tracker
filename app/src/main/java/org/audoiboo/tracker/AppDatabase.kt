package org.audoiboo.tracker

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "books",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("seriesId"), Index(value = ["url"], unique = true), Index("status")]
)
data class BookEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val title: String,
    val url: String,
    val author: String?,
    val coverUrl: String?,
    val status: String,
    val archiveUrl: String?,
    val sortIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class SeriesWithBooks(
    @Embedded val series: SeriesEntity,
    @Relation(parentColumn = "id", entityColumn = "seriesId") val books: List<BookEntity>
)

@Dao
interface LibraryDao {
    @Transaction
    @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE")
    fun observeLibrary(): Flow<List<SeriesWithBooks>>

    @Transaction
    @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE")
    suspend fun library(): List<SeriesWithBooks>

    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun pagedBooks(): PagingSource<Int, BookEntity>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE")
    fun searchBooks(query: String): PagingSource<Int, BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: SeriesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    @Query("DELETE FROM books WHERE seriesId = :seriesId")
    suspend fun deleteBooksForSeries(seriesId: String)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun deleteSeries(id: String)

    @Query("SELECT COUNT(*) FROM series")
    suspend fun seriesCount(): Int

    @Transaction
    suspend fun replaceLibrary(items: List<SeriesWithBooks>) {
        val currentIds = library().map { it.series.id }.toSet()
        val incomingIds = items.map { it.series.id }.toSet()
        (currentIds - incomingIds).forEach { deleteSeries(it) }
        val now = System.currentTimeMillis()
        items.forEach { item ->
            upsertSeries(item.series.copy(updatedAt = now))
            deleteBooksForSeries(item.series.id)
            upsertBooks(item.books.mapIndexed { index, book ->
                book.copy(seriesId = item.series.id, sortIndex = index, updatedAt = now)
            })
        }
    }
}

@Database(entities = [SeriesEntity::class, BookEntity::class], version = 1, exportSchema = false)
abstract class AudoibooDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile private var instance: AudoibooDatabase? = null
        fun get(context: Context): AudoibooDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AudoibooDatabase::class.java, "audoiboo.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

/** One-way, idempotent import. SharedPreferences remains readable during the transition. */
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
            val books = (0 until arr.length()).mapNotNull { j ->
                val b = arr.optJSONObject(j) ?: return@mapNotNull null
                val url = b.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BookEntity(
                    id = "$id::$url",
                    seriesId = id,
                    title = b.optString("title"),
                    url = url,
                    author = b.optString("author").takeIf { it.isNotBlank() && it != "null" },
                    coverUrl = b.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
                    status = b.optString("status", "NEW"),
                    archiveUrl = b.optString("archiveUrl").takeIf { it.isNotBlank() && it != "null" },
                    sortIndex = j
                )
            }
            dao.upsertBooks(books)
        }
    }
}
