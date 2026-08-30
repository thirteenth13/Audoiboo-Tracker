package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PluginRuntimeHealthTest {
    @Test
    fun failuresPersistAndReachThreshold() = withTempDir { root ->
        var now = 10L
        val first = PluginRuntimeHealth(root, failureThreshold = 3, clockMillis = { now++ })

        val one = first.recordFailure("source", 2, "first")
        val two = first.recordFailure("source", 2, "second")

        assertEquals(1, one.failures)
        assertEquals(2, two.failures)
        assertFalse(first.shouldQuarantine(two))

        val afterRestart = PluginRuntimeHealth(root, failureThreshold = 3, clockMillis = { now++ })
        val three = afterRestart.recordFailure("source", 2, "third")
        assertEquals(3, three.failures)
        assertTrue(afterRestart.shouldQuarantine(three))
        assertEquals("third", three.lastReason)
    }

    @Test
    fun successResetsFailureStreakForVersion() = withTempDir { root ->
        val health = PluginRuntimeHealth(root, failureThreshold = 3)
        health.recordFailure("source", 1, "boom")
        assertEquals(1, health.read("source", 1)?.failures)

        health.recordSuccess("source", 1)

        assertNull(health.read("source", 1))
        assertEquals(1, health.recordFailure("source", 1, "again").failures)
    }

    @Test
    fun versionsHaveIndependentFailureCounters() = withTempDir { root ->
        val health = PluginRuntimeHealth(root)
        health.recordFailure("source", 1, "v1")
        health.recordFailure("source", 2, "v2")
        health.recordFailure("source", 2, "v2 again")

        assertEquals(1, health.read("source", 1)?.failures)
        assertEquals(2, health.read("source", 2)?.failures)
        health.clear("source", 2)
        assertEquals(1, health.read("source", 1)?.failures)
        assertNull(health.read("source", 2))
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-runtime-health-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
