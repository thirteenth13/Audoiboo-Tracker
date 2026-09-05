package org.audoiboo.tracker.plugin

import android.os.SystemClock
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
    ): List<CatalogProviderDiagnosticResult> {
        val author = authorQuery.trim().ifBlank { "Роман Прокофьев" }
        val book = bookQuery.trim().ifBlank { "Игра Кота" }
        return listOf(
            probe(
                registry = registry,
                providerId = "open-library",
                authorUrl = "https://openlibrary.org/search/authors.json?q=${encode(author)}&limit=5",
                bookUrl = "https://openlibrary.org/search.json?title=${encode(book)}&limit=10&fields=key,title,author_name,series"
            ),
            probe(
                registry = registry,
                providerId = "fantlab",
                authorUrl = "https://api.fantlab.ru/search-autors?q=${encode(author)}&page=1&onlymatches=1",
                bookUrl = "https://api.fantlab.ru/search-works?q=${encode(book)}&page=1&onlymatches=1"
            ),
            probe(
                registry = registry,
                providerId = "google-books",
                authorUrl = "https://www.googleapis.com/books/v1/volumes?q=${encode("inauthor:\"$author\"")}&maxResults=10",
                bookUrl = "https://www.googleapis.com/books/v1/volumes?q=${encode("intitle:\"$book\"")}&maxResults=10"
            )
        )
    }

    private suspend fun probe(
        registry: SourcePluginRegistry,
        providerId: String,
        authorUrl: String,
        bookUrl: String
    ): CatalogProviderDiagnosticResult {
        val started = SystemClock.elapsedRealtime()
        val plugin = registry.byId(providerId)
        val displayName = plugin?.descriptor?.name ?: providerId
        if (plugin == null) {
            return CatalogProviderDiagnosticResult(providerId, displayName, null, null, 0, 0, 0, "provider-not-registered")
        }
        return try {
            val authorHttp = httpStatus(authorUrl)
            val bookHttp = httpStatus(bookUrl)
            val authorProvider = plugin as? AuthorCatalogProvider
            val bookProvider = plugin as? CatalogBookSearchProvider
            val authors = authorProvider?.searchAuthors(authorQuery = extractQuery(authorUrl), limit = 5)?.size ?: 0
            val books = bookProvider?.searchBooks(query = extractBookQuery(bookUrl), limit = 10)?.size ?: 0
            CatalogProviderDiagnosticResult(
                providerId = providerId,
                displayName = displayName,
                authorHttp = authorHttp,
                bookHttp = bookHttp,
                authors = authors,
                books = books,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        } catch (t: Throwable) {
            CatalogProviderDiagnosticResult(
                providerId = providerId,
                displayName = displayName,
                authorHttp = runCatching { httpStatus(authorUrl) }.getOrNull(),
                bookHttp = runCatching { httpStatus(bookUrl) }.getOrNull(),
                authors = 0,
                books = 0,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                error = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(180)
            )
        }
    }

    private fun httpStatus(url: String): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Audoiboo-Catalog-Diagnostics/1")
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun extractQuery(url: String): String = when {
        "openlibrary.org" in url -> decodeParam(url, "q")
        "fantlab.ru" in url -> decodeParam(url, "q")
        "googleapis.com" in url -> decodeParam(url, "q").removePrefix("inauthor:\"").removeSuffix("\"")
        else -> ""
    }

    private fun extractBookQuery(url: String): String = when {
        "openlibrary.org" in url -> decodeParam(url, "title")
        "fantlab.ru" in url -> decodeParam(url, "q")
        "googleapis.com" in url -> decodeParam(url, "q").removePrefix("intitle:\"").removeSuffix("\"")
        else -> ""
    }

    private fun decodeParam(url: String, name: String): String = runCatching {
        URI(url).rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            .orEmpty()
    }.getOrDefault("")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
