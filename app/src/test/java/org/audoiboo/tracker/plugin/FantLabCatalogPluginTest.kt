package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FantLabCatalogPluginTest {
    @Test
    fun parsesAuthorSearchUsingRussianAndOriginalNames() {
        val results = FantLabCatalogPlugin.parseAuthorSearch(
            SourceIdentityMatcher.normalizeTitle("Роман Прокофьев"),
            JSONArray("""[
              {"autor_id":"123","rusname":"Роман Прокофьев","name":"Roman Prokofiev","pseudo_names":"","workcount":"42"},
              {"autor_id":456,"rusname":"Другой Автор","name":"Other Author","pseudo_names":""}
            ]""")
        )
        assertEquals(1, results.size)
        assertEquals("123", results.single().remoteId)
        assertEquals("Роман Прокофьев", results.single().name)
        assertEquals(42, results.single().workCount)
        assertTrue(results.single().alternativeNames.contains("Roman Prokofiev"))
    }

    @Test
    fun acceptsBareAndWrappedSearchResponseShapes() {
        val bare = FantLabCatalogPlugin.parseSearchArray("""[{"autor_id":82803}]""")
        val wrapped = FantLabCatalogPlugin.parseSearchArray("""{"matches":[{"autor_id":82803}],"total":1}""")
        assertNotNull(bare)
        assertNotNull(wrapped)
        assertEquals(82803, bare!!.getJSONObject(0).getInt("autor_id"))
        assertEquals(82803, wrapped!!.getJSONObject(0).getInt("autor_id"))
    }

    @Test
    fun parsesCyclesAndStandaloneWorks() {
        val author = CatalogAuthor("fantlab", "123", "Роман Прокофьев")
        val json = JSONObject("""{
          "cycles_blocks":{"1":{"list":[{"work_id":900,"work_name":"Звездная кровь","children":[
            {"work_id":"901","work_name":"Звездная кровь 10","work_year":"2026","authors":[{"name":"Роман Прокофьев"}]},
            {"work_id":902,"work_name":"Звездная кровь 2","work_year":2021,"authors":[{"name":"Роман Прокофьев"}]}
          ]}]}},
          "works_blocks":{"2":{"list":[
            {"work_id":903,"work_name":"Отдельная книга","work_year":2019,"authors":[{"name":"Роман Прокофьев"}]},
            {"work_id":"901","work_name":"Звездная кровь 10","work_year":"2026","authors":[{"name":"Роман Прокофьев"}]}
          ]}}
        }""")
        val books = FantLabCatalogPlugin.parseCatalog(author, json)
        assertEquals(3, books.size)
        val cycle = books.filter { it.seriesTitles == listOf("Звездная кровь") }
        assertEquals(listOf(2.0, 10.0), cycle.sortedBy { it.seriesNumber }.map { it.seriesNumber })
        assertEquals(listOf("902", "901"), cycle.sortedBy { it.seriesNumber }.map { it.remoteId })
        assertEquals(2026, cycle.single { it.remoteId == "901" }.firstPublishYear)
        assertEquals("Отдельная книга", books.single { it.remoteId == "903" }.title)
    }

    @Test
    fun cycleMembersStayInsideLimitForProlificAuthor() {
        val author = CatalogAuthor("fantlab", "60", "Сергей Лукьяненко")
        val standalone = (1..205).joinToString(",") { index ->
            // Keep the ordinal away from the suffix: titles ending in a bare number are intentionally
            // interpreted by CatalogSeriesHeuristics as an inferred numbered series.
            """{"work_id":${1000 + index},"work_name":"Отдельное $index произведение","work_year":2000,"authors":[{"name":"Сергей Лукьяненко"}]}"""
        }
        val json = JSONObject("""{
          "cycles_blocks":{"1":{"list":[{"work_id":60,"work_name":"Дозоры","children":[
            {"work_id":1,"work_name":"Ночной Дозор","work_year":1998,"authors":[{"name":"Сергей Лукьяненко"}]},
            {"work_id":2,"work_name":"Сумеречный Дозор","work_year":2003,"authors":[{"name":"Сергей Лукьяненко"}]}
          ]}]}},
          "works_blocks":{"2":{"list":[$standalone]}}
        }""")

        val limited = FantLabCatalogPlugin.parseCatalog(author, json).take(200)
        val grouped = CatalogSeriesHeuristics.group(AuthorCatalog(author, limited))

        assertTrue(limited.any { it.seriesTitles.isNotEmpty() })
        assertEquals(1, grouped.series.size)
        assertEquals("Дозоры", grouped.series.single().title)
        assertEquals(2, grouped.series.single().books.size)
    }
}
