package org.audoiboo.tracker

import java.util.concurrent.Semaphore

/** Caps resource-heavy network/extraction work while preserving one execution per download id. */
internal class DownloadConcurrencyGate(maxConcurrent: Int) {
    private val permits = Semaphore(maxConcurrent.coerceAtLeast(1), true)

    fun tryAcquire(): Boolean = permits.tryAcquire()

    fun release() {
        permits.release()
    }
}
