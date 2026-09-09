package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import org.audoiboo.tracker.plugin.AuthorCatalog
import org.audoiboo.tracker.plugin.CatalogSeries
import org.audoiboo.tracker.plugin.CatalogSeriesHeuristics
import org.audoiboo.tracker.plugin.FantLabCatalogPlugin
import org.audoiboo.tracker.plugin.SeriesDiagnosticLog
import org.audoiboo.tracker.plugin.SourceIdentityMatcher
import org.audoiboo.tracker.plugin.SourceMetadataRepository

/** Creates separate catalog-backed Room series for FantLab subcycles of an existing parent series. */
internal object FantLabNestedSeriesSync {
    suspend fun syncAll(context: Context) {
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        val library = dao.library()

        library.filterNot { it.series.url.startsWith("catalog://fantlab/nested/", ignoreCase = true) }
            .forEach { parent ->
                val hasFantLabAnchor = parent.books.any { book ->
                    SourceMetadataRepository.sourcesForBook(context, book.id).any { it.sourceId == "fantlab" }
                }
                if (!hasFantLabAnchor) return@forEach
                syncParent(context, parent)
            }
    }

    private suspend fun syncParent(context: Context, parent: SeriesWithBooks) {
        val authorQueries = parent.books.mapNotNull { it.author }
            .flatMap { it.split(',', ';', '&') }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(SourceIdentityMatcher::normalizeAuthor)
            .take(3)
        if (authorQueries.isEmpty()) return

        val nestedByKey = linkedMapOf<String, CatalogSeries>()
        for (authorQuery in authorQueries) {
            val authors = FantLabCatalogPlugin.searchAuthors(authorQuery, 2).filter { it.confidence >= 0.78f }
            for (author in authors) {
                val grouped = CatalogSeriesHeuristics.group(FantLabCatalogPlugin.loadAuthorCatalog(author, 300))
                nestedSeriesForParent(parent.series.name, grouped.series).forEach { nested ->
                    nestedByKey.putIfAbsent(SourceIdentityMatcher.normalizeTitle(nested.title), nested)
                }
            }
        }
        if (nestedByKey.isEmpty()) return

        val dao = AudoibooDatabase.get(context).libraryDao()
        val currentLibrary = dao.library()
        nestedByKey.values.forEach { nested ->
            val normalizedTitle = SourceIdentityMatcher.normalizeTitle(nested.title)
            val existing = currentLibrary.firstOrNull {
                it.series.url.startsWith("catalog://fantlab/nested/${Uri.encode(parent.series.id)}/", ignoreCase = true) &&
                    SourceIdentityMatcher.normalizeTitle(it.series.name) == normalizedTitle
            }
            val stableSuffix = normalizedTitle.hashCode().toUInt().toString(16)
            val seriesId = existing?.series?.id ?: "catalog::fantlab:nested:${parent.series.id}:$stableSuffix"
            val seriesUrl = existing?.series?.url
                ?: "catalog://fantlab/nested/${Uri.encode(parent.series.id)}/${Uri.encode(nested.title)}"
            val previousBooks = existing?.books.orEmpty().associateBy { it.id }
            val now = System.currentTimeMillis()

            dao.upsertSeries(SeriesEntity(seriesId, nested.title, seriesUrl, now))
            val books = nested.books.mapIndexed { index, book ->
                val bookId = "$seriesId::fantlab:${book.remoteId}"
                val old = previousBooks[bookId]
                BookEntity(
                    id = bookId,
                    seriesId = seriesId,
                    title = book.title,
                    url = "catalog://fantlab/book/${Uri.encode(book.remoteId)}",
                    author = book.authors.takeIf { it.isNotEmpty() }?.joinToString() ?: nested.authors.joinToString().takeIf(String::isNotBlank),
                    coverUrl = book.coverUrl ?: old?.coverUrl,
                    status = old?.status ?: "NEW",
                    archiveUrl = old?.archiveUrl,
                    sortIndex = book.seriesNumber?.takeIf { it >= 1.0 }?.toInt()?.minus(1)?.coerceAtLeast(0) ?: index,
                    updatedAt = now
                )
            }
            if (books.isEmpty()) dao.deleteBooksForSeries(seriesId)
            else {
                dao.deleteMissingBooks(seriesId, books.map { it.id })
                dao.upsertBooks(books)
            }
            SeriesDiagnosticLog.i("RoomSeriesSync NESTED parent=${parent.series.name} child=${nested.title} books=${books.size}")
        }
    }

    internal fun nestedSeriesForParent(parentTitle: String, series: List<CatalogSeries>): List<CatalogSeries> {
        val parentKey = SourceIdentityMatcher.normalizeTitle(parentTitle)
        if (parentKey.isBlank()) return emptyList()
        return series.filter { candidate ->
            SourceIdentityMatcher.normalizeTitle(candidate.title) != parentKey &&
                candidate.books.any { book ->
                    book.seriesTitles.drop(1).any { SourceIdentityMatcher.normalizeTitle(it) == parentKey }
                }
        }
    }
}
