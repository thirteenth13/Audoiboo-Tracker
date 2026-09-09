package org.audoiboo.tracker.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertSeparated(author, json)
    }

    @Test
    fun flatFantLabHierarchyUsesRootSagaAndDoesNotTreatCycleNodeAsBook() {
        val author = CatalogAuthor("fantlab", "82803", "Роман Прокофьев")
        val json = JSONObject(
            """
            {
              "cycles_blocks": {
                "1": {
                  "list": [
                    {
                      "work_name": "Звёздная Кровь",
                      "children": [
                        {
                          "work_id": 1,
                          "work_name": "Звёздная Кровь",
                          "work_type": "роман",
                          "deep": 1,
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь", "work_type": "цикл"}
                          ]
                        },
                        {
                          "work_id": 900,
                          "work_name": "Тысяча Братьев",
                          "work_type": "цикл",
                          "deep": 2,
                          "position_is_node": 1,
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь", "work_type": "цикл"}
                          ]
                        },
                        {
                          "work_id": 101,
                          "work_name": "Звёздная Кровь. Пламени Подобный",
                          "work_type": "роман",
                          "deep": 2,
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь", "work_type": "цикл"},
                            {"work_name": "Тысяча Братьев", "work_type": "цикл"}
                          ]
                        },
                        {
                          "work_id": 102,
                          "work_name": "Звёздная Кровь. Лёд-Кузнец",
                          "work_type": "роман",
                          "deep": 2,
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь", "work_type": "цикл"},
                            {"work_name": "Тысяча Братьев", "work_type": "цикл"}
                          ]
                        },
                        {
                          "work_id": 11,
                          "work_name": "Звёздная кровь-11. Колония Альфа",
                          "work_type": "роман",
                          "deep": 1,
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь", "work_type": "цикл"}
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val books = FantLabCatalogPlugin.parseCatalog(author, json)
        assertFalse(books.any { it.remoteId == "900" })
        assertSeparated(author, json)
    }

    @Test
    fun compactRootSagaWithoutWorkTypeStillPreservesSubcycle() {
        val author = CatalogAuthor("fantlab", "82803", "Роман Прокофьев")
        val json = JSONObject(
            """
            {
              "cycles_blocks": {
                "1": {
                  "list": [
                    {
                      "work_name": "Звёздная Кровь",
                      "children": [
                        {
                          "work_id": 1,
                          "work_name": "Звёздная Кровь",
                          "work_type": "роман",
                          "work_root_saga": [{"work_name": "Звёздная Кровь"}]
                        },
                        {
                          "work_id": 101,
                          "work_name": "Звёздная Кровь. Пламени Подобный",
                          "work_type": "роман",
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь"},
                            {"work_name": "Тысяча Братьев"}
                          ]
                        },
                        {
                          "work_id": 102,
                          "work_name": "Звёздная Кровь. Лёд-Кузнец",
                          "work_type": "роман",
                          "work_root_saga": [
                            {"work_name": "Звёздная Кровь"},
                            {"work_name": "Тысяча Братьев"}
                          ]
                        },
                        {
                          "work_id": 11,
                          "work_name": "Звёздная кровь-11. Колония Альфа",
                          "work_type": "роман",
                          "work_root_saga": [{"work_name": "Звёздная Кровь"}]
                        }
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertSeparated(author, json)
    }

    private fun assertSeparated(author: CatalogAuthor, json: JSONObject) {
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
