package org.audoiboo.tracker.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsStableIdTest {
    @Test
    fun hashIsDeterministicAndCollisionSafeForPreviouslyAmbiguousIds() {
        val first = TtsStableId.hex("a:b")
        val second = TtsStableId.hex("a/b")

        assertEquals(first, TtsStableId.hex("a:b"))
        assertNotEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun workAndNotificationIdsAreStablePerSession() {
        assertNotEquals(TtsGenerationScheduler.workName("a:b"), TtsGenerationScheduler.workName("a/b"))
        assertEquals(TtsStableId.notificationId("book:42"), TtsStableId.notificationId("book:42"))
        assertTrue(TtsStableId.notificationId("book:42") > 0)
    }
}
