package org.audoiboo.tracker.plugin

/** Small in-process TTL/LRU cache for interactive catalog discovery. */
class CatalogSearchCache<T>(
    private val maxEntries: Int = 24,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry<T>(val createdAt: Long, val value: T)

    private val entries = object : LinkedHashMap<String, Entry<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<T>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(query: String): T? {
        val key = normalize(query)
        val entry = entries[key] ?: return null
        if (clock() - entry.createdAt > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(query: String, value: T) {
        entries[normalize(query)] = Entry(clock(), value)
    }

    @Synchronized
    fun invalidate(query: String) {
        entries.remove(normalize(query))
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun normalize(query: String): String =
        query.trim().lowercase().replace(Regex("\\s+"), " ")
}

object CatalogSearchCaches {
    val authorDiscovery = CatalogSearchCache<List<CatalogSourceMatch>>()
}
