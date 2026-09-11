package org.audoiboo.tracker.plugin

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNetworkFallbackPolicyTest {
    @Test
    fun retriesDirectAndWrappedEofFailures() {
        assertTrue(HostPluginHttpTransport.shouldRetryTruncatedResponse(EOFException("truncated")))
        assertTrue(
            HostPluginHttpTransport.shouldRetryTruncatedResponse(
                IOException("outer", EOFException("inner"))
            )
        )
    }

    @Test
    fun doesNotRetryUnrelatedNetworkFailures() {
        assertFalse(HostPluginHttpTransport.shouldRetryTruncatedResponse(IOException("generic")))
        assertFalse(HostPluginHttpTransport.shouldRetryTruncatedResponse(ConnectException("blocked")))
    }

    @Test
    fun audiobooFallbackTriesSpecificQueryThenAuthorOnly() {
        assertEquals(
            listOf(
                "Сергей Лукьяненко Ночной Дозор",
                "Сергей Лукьяненко"
            ),
            audiobooFallbackQueries("  Сергей Лукьяненко  ", " Ночной Дозор ")
        )
    }

    @Test
    fun audiobooFallbackDoesNotDuplicateAuthorWhenBookIsBlank() {
        assertEquals(
            listOf("Сергей Лукьяненко"),
            audiobooFallbackQueries("Сергей Лукьяненко", "   ")
        )
    }
}
