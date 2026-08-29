package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActiveDownloadRegistryTest {
    @Test
    fun onlyOneExecutionCanRegisterForSameId() {
        val registry = ActiveDownloadRegistry<Any>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(24)
        val wins = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)

        repeat(24) {
            pool.execute {
                start.await()
                if (registry.tryRegister("book-1", Any())) wins.incrementAndGet()
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertTrue(registry.contains("book-1"))
        assertTrue(wins.get() == 1)
    }

    @Test
    fun unregisterRequiresExactOwner() {
        val registry = ActiveDownloadRegistry<Any>()
        val owner = Any()
        val other = Any()

        assertTrue(registry.tryRegister("book-1", owner))
        assertFalse(registry.unregister("book-1", other))
        assertTrue(registry.contains("book-1"))
        assertTrue(registry.unregister("book-1", owner))
        assertTrue(registry.isEmpty())
    }

    @Test
    fun differentIdsCanRunConcurrently() {
        val registry = ActiveDownloadRegistry<Any>()

        assertTrue(registry.tryRegister("book-1", Any()))
        assertTrue(registry.tryRegister("book-2", Any()))
        assertTrue(registry.contains("book-1"))
        assertTrue(registry.contains("book-2"))
    }
}
