package org.audoiboo.tracker.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleBooksCatalogPluginTest {
    @Test
    fun parsesAuthorBooksAndUsesSeriesOrderWhenAvailable() {
        val author = CatalogAuthor("google-books", "roman-prokofev", "Roman Prokofiev")
        val json = JSONObject().apply {
            put("items", org.json.JSONArray().put(
                JSONObject().apply {
                    put("id", "volume-10")
                    put("volumeInfo", JSONObject().apply {
                        put("title", "Star Blood. Book 10")
                        put("authors", org.json.JSONArray().put("Roman Prokofiev"))
                        put("publishedDate", "2025-04-12")
                        put("imageLinks", JSONObject().put("thumbnail", "http://books.google.com/cover.jpg"))
                        put("seriesInfo", JSONObject().apply {
                            put("bookDisplayNumber", "10")
                            put("volumeSeries", org.json.JSONArray().put(
                                JSONObject().apply {
                                    put("seriesId", "series-1")
                                    put("orderNumber", 10)
                                }
                            ))
                        })
                    })
                }
            ))
        }

        val books = GoogleBooksCatalogPlugin.parseBooks(author, json)

        assertEquals(1, books.size)
        assertEquals("volume-10", books.single().remoteId)
        assertEquals("Star Blood", books.single().seriesTitles.single())
        assertEquals(10.0, books.single().seriesNumber ?: -1.0, 0.0)
        assertEquals(2025, books.single().firstPublishYear)
        assertTrue(books.single().coverUrl!!.startsWith("https://"))
    }

    @Test
    fun ignoresClearlyDifferentAuthors() {
        val author = CatalogAuthor("google-books", "author-a", "Author A")
        val json = JSONObject().apply {
            put("items", org.json.JSONArray().put(
                JSONObject().apply {
                    put("id", "wrong")
                    put("volumeInfo", JSONObject().apply {
                        put("title", "Cycle 1")
                        put("authors", org.json.JSONArray().put("Completely Different Person"))
                    })
                }
            ))
        }

        assertTrue(GoogleBooksCatalogPlugin.parseBooks(author, json).isEmpty())
    }
}
