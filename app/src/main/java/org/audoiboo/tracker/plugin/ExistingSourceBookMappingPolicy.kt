package org.audoiboo.tracker.plugin

/**
 * Reuses an already persisted source-book mapping only when it still points at a book in the
 * current canonical series and that canonical row has not already been consumed by another source
 * observation in the same snapshot.
 */
internal object ExistingSourceBookMappingPolicy {
    fun resolve(
        mappedCanonicalBookId: String?,
        canonicalBookIds: Set<String>,
        usedCanonicalBookIds: Set<String>
    ): String? = mappedCanonicalBookId
        ?.takeIf { it in canonicalBookIds && it !in usedCanonicalBookIds }
}
