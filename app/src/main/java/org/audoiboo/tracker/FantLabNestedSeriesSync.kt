package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import org.audoiboo.tracker.plugin.AuthorCatalog
import org.audoiboo.tracker.plugin.CatalogSeries
import org.audoiboo.tracker.plugin.FantLabCatalogPlugin
import org.audoiboo.tracker.plugin.SeriesDiagnosticLog
import org.audoiboo.tracker.plugin.SourceIdentityMatcher
import org.audoiboo.tracker.plugin.SourceMetadataRepository

/** Creates separate catalog-backed Room series for FantLab subcycles of an existing parent series. */
internal object FantLabNestedSeriesSync {
    suspend fun syncAll(context: Context) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val library = dao.library()

        library.filterNot { it.series.url.startsWith("catalog://fantlab/nested/", ignoreCase = true) }
            .forEach { parent ->
                val hasFantLabSeries = SourceMetadataRepository.sourcesForSeries(context, parent.series.id)
                    .any { it.sourceId == "fantlab" }
                val hasFantLabBook = if (hasFantLabSeries) true else parent.books.any { book ->
                    SourceMetadataRepository.sourcesForBook(context, book.id).any { it.sourceId == "fantlab" }
                }
                if (!hasFantLabBook) return@forEach
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
        if (authorQueries.isEmpty()) {
            SeriesDiagnosticLog.i("RoomSeriesSync NESTED_SKIP parent=${parent.series.name} reason=no-author")
            return
        }

        val nestedByKey = linkedMapOf<String, CatalogSeries>()
        for (authorQuery in authorQueries) {
            val authors = FantLabCatalogPlugin.searchAuthors(authorQuery, 2).filter { it.confidence >= 0.78f }
            for (author in authors) {
                val catalog = FantLabCatalogPlugin.loadAuthorCatalog(author, 300)
                nestedSeriesForParent(parent.series.name, catalog).forEach { nested ->
                    nestedByKey.putIfAbsent(SourceIdentityMatcher.normalizeTitle(nested.title), nested)
                }
            }
        }
        if (nestedByKey.isEmpty()) {
            SeriesDiagnosticLog.i("RoomSeriesSync NESTED_SKIP parent=${parent.series.name} reason=no-subcycles")
            return
        }

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
                    author = book.authors.takeIf { it.isNotEmpty() }?.joinToString()
                        ?: nested.authors.joinToString().takeIf(String::isNotBlank),
                    coverUrl = book.coverUrl ?: old?.coverUrl,
                    status = old?.status ?: "NEW",
                    archiveUrl = old?.archiveUrl,
                    sortIndex = index,
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

    /**
     * Build subcycles directly from every FantLab book hierarchy instead of relying on grouping by
     * seriesTitles.first(). Real FantLab payloads are not consistent about whether the parent or the
     * immediate subcycle is listed first, so the old grouping-only path could see the nested books
     * but never create the nested series.
     */
    internal fun nestedSeriesForParent(parentTitle: String, catalog: AuthorCatalog): List<CatalogSeries> {
        val parentKey = SourceIdentityMatcher.normalizeTitle(parentTitle)
        if (parentKey.isBlank()) return emptyList()

        val titleByKey = linkedMapOf<String, String>()
        catalog.books.forEach { book ->
            val hierarchy = book.seriesTitles
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(SourceIdentityMatcher::normalizeTitle)
            val keys = hierarchy.map(SourceIdentityMatcher::normalizeTitle)
            if (parentKey !in keys) return@forEach
            hierarchy.forEach { title ->
                val key = SourceIdentityMatcher.normalizeTitle(title)
                if (key.isNotBlank() && key != parentKey) titleByKey.putIfAbsent(key, title)
            }
        }

        return titleByKey.mapNotNull { (nestedKey, nestedTitle) ->
            val books = catalog.books.filter { book ->
                val keys = book.seriesTitles.map(SourceIdentityMatcher::normalizeTitle)
                parentKey in keys && nestedKey in keys
            }.distinctBy { it.providerId + ":" + it.remoteId }
                .sortedWith(
                    compareBy<org.audoiboo.tracker.plugin.CatalogBook> { it.seriesNumber ?: Double.MAX_VALUE }
                        .thenBy { it.firstPublishYear ?: Int.MAX_VALUE }
                        .thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }
                )
            if (books.isEmpty()) return@mapNotNull null
            CatalogSeries(
                title = nestedTitle,
                authors = books.flatMap { it.authors }.filter(String::isNotBlank)
                    .distinctBy(SourceIdentityMatcher::normalizeAuthor),
                books = books
            )
        }.sortedBy { SourceIdentityMatcher.normalizeTitle(it.title) }
    }
}
