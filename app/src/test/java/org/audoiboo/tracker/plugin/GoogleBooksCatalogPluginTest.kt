package org.audoiboo.tracker.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleBooksCatalogPluginTest {
    @Test
    fun parsesAuthorBooksAndUsesSeriesOrderWhenAvailable() {
        val author = CatalogAuthor("google-books", "roman-prokofev", "Roman Prokofiev")
        val json = JSONObject(
            """
            {
              "items": [
                {
                  "id": "volume-10",
                  "volumeInfo": {
                    "title": "Star Blood. Book 10",
                    "authors": ["Roman Prokofiev"],
                    "publishedDate": "2025-04-12",
                    "imageLinks": {"thumbnail": "http://books.google.com/cover.jpg"},
                    "seriesInfo": {
                      "bookDisplayNumber": "10",
                      "volumeSeries": [{"seriesId": "series-1", "orderNumber": 10}]
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        )

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
        val json = JSONObject(
            """
            {
              "items": [
                {
                  "id": "wrong",
                  "volumeInfo": {
                    "title": "Cycle 1",
                    "authors": ["Completely Different Person"]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(GoogleBooksCatalogPlugin.parseBooks(author, json).isEmpty())
    }
}
