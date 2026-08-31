package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogSearchCacheTest {
    @Test
    fun `normalizes query and returns cached value`() {
        var now = 1000L
        val cache = CatalogSearchCache<String>(clock = { now })

        cache.put("  Roman   Prokofiev ", "cached")

        assertEquals("cached", cache.get("roman prokofiev"))
    }

    @Test
    fun `expires stale values`() {
        var now = 1000L
        val cache = CatalogSearchCache<String>(ttlMillis = 100L, clock = { now })
        cache.put("author", "cached")

        now += 101L

        assertNull(cache.get("author"))
    }

    @Test
    fun `evicts least recently used entry`() {
        var now = 1000L
        val cache = CatalogSearchCache<String>(maxEntries = 2, clock = { now })
        cache.put("one", "1")
        cache.put("two", "2")
        cache.get("one")
        cache.put("three", "3")

        assertEquals("1", cache.get("one"))
        assertNull(cache.get("two"))
        assertEquals("3", cache.get("three"))
    }
}
