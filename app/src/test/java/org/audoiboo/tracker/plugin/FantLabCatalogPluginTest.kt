package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FantLabCatalogPluginTest {
    @Test
    fun parsesAuthorSearchUsingRussianAndOriginalNames() {
        val results = FantLabCatalogPlugin.parseAuthorSearch(
            SourceIdentityMatcher.normalizeTitle("Роман Прокофьев"),
            JSONArray(
                """[
                  {"autor_id":"123","rusname":"Роман Прокофьев","name":"Roman Prokofiev","pseudo_names":"","workcount":"42"},
                  {"autor_id":456,"rusname":"Другой Автор","name":"Other Author","pseudo_names":""}
                ]"""
            )
        )

        assertEquals(1, results.size)
        assertEquals("123", results.single().remoteId)
        assertEquals("Роман Прокофьев", results.single().name)
        assertEquals(42, results.single().workCount)
        assertTrue(results.single().alternativeNames.contains("Roman Prokofiev"))
    }

    @Test
    fun parsesCyclesAndStandaloneWorks() {
        val author = CatalogAuthor("fantlab", "123", "Роман Прокофьев")
        val json = JSONObject(
            """{
              "cycles_blocks": {
                "1": {
                  "list": [
                    {
                      "work_id": 900,
                      "work_name": "Звездная кровь",
                      "children": [
                        {"work_id":"901","work_name":"Звездная кровь 10","work_year":"2026","authors":[{"name":"Роман Прокофьев"}]},
                        {"work_id":902,"work_name":"Звездная кровь 2","work_year":2021,"authors":[{"name":"Роман Прокофьев"}]}
                      ]
                    }
                  ]
                }
              },
              "works_blocks": {
                "2": {
                  "list": [
                    {"work_id":903,"work_name":"Отдельная книга","work_year":2019,"authors":[{"name":"Роман Прокофьев"}]},
                    {"work_id":"901","work_name":"Звездная кровь 10","work_year":"2026","authors":[{"name":"Роман Прокофьев"}]}
                  ]
                }
              }
            }"""
        )

        val books = FantLabCatalogPlugin.parseCatalog(author, json)

        assertEquals(3, books.size)
        val cycle = books.filter { it.seriesTitles == listOf("Звездная кровь") }
        assertEquals(listOf(2.0, 10.0), cycle.sortedBy { it.seriesNumber }.map { it.seriesNumber })
        assertEquals(listOf("902", "901"), cycle.sortedBy { it.seriesNumber }.map { it.remoteId })
        assertEquals(2026, cycle.single { it.remoteId == "901" }.firstPublishYear)
        assertEquals("Отдельная книга", books.single { it.remoteId == "903" }.title)
    }
}
