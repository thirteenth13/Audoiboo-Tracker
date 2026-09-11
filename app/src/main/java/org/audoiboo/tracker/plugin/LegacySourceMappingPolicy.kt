package org.audoiboo.tracker.plugin

/**
 * Decides whether a legacy source row is safe to expose as a canonical-book source.
 * Null/blank or stale canonical ids remain stored for later repair/review, but must not
 * leak into book UI/download resolution as if they were valid mappings.
 */
internal object LegacySourceMappingPolicy {
    fun isUsableCanonicalBookId(canonicalBookId: String?, existingCanonicalBookIds: Set<String>): Boolean {
        val id = canonicalBookId?.takeIf { it.isNotBlank() } ?: return false
        return id in existingCanonicalBookIds
    }

    fun canonicalBookIdForRead(canonicalBookId: String?, existingCanonicalBookIds: Set<String>): String? =
        canonicalBookId?.takeIf { isUsableCanonicalBookId(it, existingCanonicalBookIds) }
}
