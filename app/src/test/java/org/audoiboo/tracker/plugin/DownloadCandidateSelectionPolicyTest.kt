package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCandidateSelectionPolicyTest {
    @Test
    fun unsupportedTypesAreDiscarded() {
        val candidates = listOf(
            DownloadCandidate(DownloadType.STREAM, "https://example.test/live.m3u8", priority = 100),
            DownloadCandidate(DownloadType.DIRECT_FILE, "https://example.test/01.mp3", priority = 10)
        )

        val selected = DownloadCandidateSelectionPolicy.preferredWithinSource(candidates)

        assertEquals(1, selected.size)
        assertEquals(DownloadType.DIRECT_FILE, selected.single().type)
    }

    @Test
    fun archiveBeatsPlaylistWithinSourceEvenWithLowerPriority() {
        val candidates = listOf(
            DownloadCandidate(DownloadType.DIRECT_FILE, "https://example.test/01.mp3", priority = 500),
            DownloadCandidate(DownloadType.DIRECT_FILE, "https://example.test/02.mp3", priority = 499),
            DownloadCandidate(DownloadType.ARCHIVE, "https://example.test/book.zip", priority = 1)
        )

        val selected = DownloadCandidateSelectionPolicy.preferredWithinSource(candidates)

        assertEquals(1, selected.size)
        assertEquals(DownloadType.ARCHIVE, selected.single().type)
    }

    @Test
    fun directPlaylistKeepsAllDistinctTracksSortedByPriority() {
        val candidates = listOf(
            DownloadCandidate(DownloadType.DIRECT_FILE, "https://example.test/02.mp3", priority = 4),
            DownloadCandidate(DownloadType.DIRECT_FILE, "https://example.test/01.mp3", priority = 5),
            DownloadCandidate(DownloadType.DIRECT_FILE, "https://example.test/01.mp3", priority = 1)
        )

        val selected = DownloadCandidateSelectionPolicy.preferredWithinSource(candidates)

        assertEquals(listOf("https://example.test/01.mp3", "https://example.test/02.mp3"), selected.map { it.url })
        assertTrue(selected.zipWithNext().all { (left, right) -> left.priority >= right.priority })
    }
}
