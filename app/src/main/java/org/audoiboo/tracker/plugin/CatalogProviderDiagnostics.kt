package org.audoiboo.tracker.plugin

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class CatalogProviderDiagnosticResult(
    val providerId: String,
    val displayName: String,
    val authorHttp: Int?,
    val bookHttp: Int?,
    val authors: Int,
    val books: Int,
    val elapsedMs: Long,
    val error: String? = null
)

object CatalogProviderDiagnostics {
    suspend fun run(
        registry: SourcePluginRegistry,
        authorQuery: String,
        bookQuery: String
    ): List<CatalogProviderDiagnosticResult> = supervisorScope {
        val author = authorQuery.trim().ifBlank { "Роман Прокофьев" }
        val book = bookQuery.trim().ifBlank { "Игра Кота" }
        val specs = listOf(
            ProviderSpec(
                id = "open-library",
                authorUrl = "https://openlibrary.org/search/authors.json?q=${encode(author)}&limit=5",
                bookUrl = "https://openlibrary.org/search.json?title=${encode(book)}&limit=10&fields=key,title,author_name,series"
            ),
            ProviderSpec(
                id = "fantlab",
                authorUrl = "https://api.fantlab.ru/search-autors?q=${encode(author)}&page=1&onlymatches=1",
                bookUrl = "https://api.fantlab.ru/search-works?q=${encode(book)}&page=1&onlymatches=1"
            ),
            ProviderSpec(
                id = "google-books",
                authorUrl = "https://www.googleapis.com/books/v1/volumes?q=${encode("inauthor:\"$author\"")}&maxResults=10",
                bookUrl = "https://www.googleapis.com/books/v1/volumes?q=${encode("intitle:\"$book\"")}&maxResults=10"
            )
        )
        // Providers run in parallel, but author/book calls for the same provider are sequential.
        // This avoids doubling pressure on rate-limited APIs (notably Google Books) and reduces
        // Open Library timeouts on slower mobile networks.
        specs.map { spec -> async { probe(registry, spec, author, book) } }.map { it.await() }
    }

    private suspend fun probe(
        registry: SourcePluginRegistry,
        spec: ProviderSpec,
        authorQuery: String,
        bookQuery: String
    ): CatalogProviderDiagnosticResult {
        val started = SystemClock.elapsedRealtime()
        val plugin = registry.byId(spec.id)
        val displayName = plugin?.descriptor?.name ?: spec.id
        if (plugin == null) {
            return CatalogProviderDiagnosticResult(spec.id, displayName, null, null, 0, 0, 0, "provider-not-registered")
        }

        val authorResult = runCatching {
            (plugin as? AuthorCatalogProvider)?.searchAuthors(authorQuery, 5)?.size ?: 0
        }
        val authorHttp = if (authorResult.isSuccess && authorResult.getOrDefault(0) > 0) {
            200
        } else {
            runCatching { httpStatus(spec.authorUrl) }.getOrNull()
        }

        val bookResult = runCatching {
            (plugin as? CatalogBookSearchProvider)?.searchBooks(bookQuery, 10)?.size ?: 0
        }
        val bookHttp = if (bookResult.isSuccess && bookResult.getOrDefault(0) > 0) {
            200
        } else {
            runCatching { httpStatus(spec.bookUrl) }.getOrNull()
        }

        val errors = buildList {
            authorResult.exceptionOrNull()?.let { add("authors=${it.javaClass.simpleName}:${it.message.orEmpty()}") }
            bookResult.exceptionOrNull()?.let { add("books=${it.javaClass.simpleName}:${it.message.orEmpty()}") }
            if (authorHttp == null) add("author-http=failed")
            if (bookHttp == null) add("book-http=failed")
            if (authorHttp != null && authorHttp !in 200..299) add("author-http=$authorHttp")
            if (bookHttp != null && bookHttp !in 200..299) add("book-http=$bookHttp")
        }

        return CatalogProviderDiagnosticResult(
            providerId = spec.id,
            displayName = displayName,
            authorHttp = authorHttp,
            bookHttp = bookHttp,
            authors = authorResult.getOrDefault(0),
            books = bookResult.getOrDefault(0),
            elapsedMs = SystemClock.elapsedRealtime() - started,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(220)
        )
    }

    private fun httpStatus(url: String): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Audoiboo-Catalog-Diagnostics/2")
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class ProviderSpec(
        val id: String,
        val authorUrl: String,
        val bookUrl: String
    )
}
