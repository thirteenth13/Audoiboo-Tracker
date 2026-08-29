package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.AudoibooDatabase

object SourceMetadataRepository {
    const val AUDIOBOO_SOURCE_ID = "audioboo"

    suspend fun registerBuiltInPlugins(context: Context, plugins: Collection<SourcePlugin>) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        plugins.forEach { dao.registerPlugin(it.descriptor) }
    }

    suspend fun backfillAudiobooMappings(context: Context) = withContext(Dispatchers.IO) {
        val library = AudoibooDatabase.get(context).libraryDao().library()
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()

        library.forEach { item ->
            val seriesRemoteKey = SourceKeys.remoteKey(null, item.series.url)
            val existingSeries = dao.seriesSource(AUDIOBOO_SOURCE_ID, seriesRemoteKey)
            dao.upsertSeriesSource(
                SeriesSourceEntity(
                    canonicalSeriesId = item.series.id,
                    sourceId = AUDIOBOO_SOURCE_ID,
                    remoteKey = seriesRemoteKey,
                    url = item.series.url,
                    remoteTitle = item.series.name,
                    relationship = "SAME_SERIES",
                    confidence = 1f,
                    userVerified = existingSeries?.userVerified ?: true,
                    firstSeenAt = existingSeries?.firstSeenAt ?: now,
                    lastSeenAt = now,
                    lastCheckedAt = existingSeries?.lastCheckedAt
                )
            )

            item.books.forEach { book ->
                val remoteKey = SourceKeys.remoteKey(null, book.url)
                val key = SourceKeys.bookSourceKey(AUDIOBOO_SOURCE_ID, remoteKey)
                val existingBook = dao.bookSource(AUDIOBOO_SOURCE_ID, remoteKey)
                dao.upsertBookSource(
                    BookSourceEntity(
                        key = key,
                        canonicalBookId = book.id,
                        canonicalSeriesId = item.series.id,
                        sourceId = AUDIOBOO_SOURCE_ID,
                        remoteKey = remoteKey,
                        url = book.url,
                        remoteTitle = book.title,
                        remoteAuthor = book.author,
                        confidence = 1f,
                        firstSeenAt = existingBook?.firstSeenAt ?: now,
                        lastSeenAt = now,
                        lastCheckedAt = existingBook?.lastCheckedAt
                    )
                )

                book.archiveUrl?.takeIf { it.isNotBlank() }?.let { archiveUrl ->
                    val existingAvailability = dao.availability(key).firstOrNull { it.type == DownloadType.ARCHIVE.name }
                    dao.upsertAvailability(
                        SourceAvailabilityEntity(
                            bookSourceKey = key,
                            sourceId = AUDIOBOO_SOURCE_ID,
                            type = DownloadType.ARCHIVE.name,
                            status = "AVAILABLE",
                            uri = archiveUrl,
                            firstSeenAt = existingAvailability?.firstSeenAt ?: now,
                            lastSeenAt = now,
                            lastCheckedAt = now
                        )
                    )
                }
            }
        }
    }

    suspend fun sourcesForBook(context: Context, canonicalBookId: String): List<BookSourceEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().bookSources(canonicalBookId)
    }

    suspend fun sourcesForSeries(context: Context, canonicalSeriesId: String): List<SeriesSourceEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().seriesSources(canonicalSeriesId)
    }

    suspend fun availabilityFor(context: Context, bookSourceKey: String): List<SourceAvailabilityEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().availability(bookSourceKey)
    }
}
