package org.audoiboo.tracker.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FantLabNestedCycleTest {
    @Test
    fun nestedCycleBecomesSeparateSeriesAndKeepsParentChain() {
        val author = CatalogAuthor(
            providerId = "fantlab",
            remoteId = "82803",
            name = "Роман Прокофьев"
        )
        val json = JSONObject(
            """
            {
              "cycles_blocks": {
                "1": {
                  "list": [
                    {
                      "work_name": "Звёздная Кровь",
                      "children": [
                        {"work_id": 1, "work_name": "Звёздная Кровь", "work_year": 2023},
                        {
                          "work_name": "Тысяча Братьев",
                          "children": [
                            {"work_id": 101, "work_name": "Звёздная Кровь. Пламени Подобный", "work_year": 2025},
                            {"work_id": 102, "work_name": "Звёздная Кровь. Лёд-Кузнец", "work_year": 2025}
                          ]
                        },
                        {"work_id": 11, "work_name": "Звёздная кровь-11. Колония Альфа", "work_year": 2026}
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val catalog = AuthorCatalog(author, FantLabCatalogPlugin.parseCatalog(author, json))
        val grouped = CatalogSeriesHeuristics.group(catalog)

        val parent = grouped.series.first { SourceIdentityMatcher.normalizeTitle(it.title) == "звездная кровь" }
        val nested = grouped.series.first { SourceIdentityMatcher.normalizeTitle(it.title) == "тысяча братьев" }

        assertEquals(2, parent.books.size)
        assertEquals(listOf("1", "11"), parent.books.map { it.remoteId })
        assertEquals(2, nested.books.size)
        assertEquals(listOf("101", "102"), nested.books.map { it.remoteId })
        assertTrue(nested.books.all { it.seriesTitles.first() == "Тысяча Братьев" })
        assertTrue(nested.books.all { it.seriesTitles.drop(1).contains("Звёздная Кровь") })
    }
}
