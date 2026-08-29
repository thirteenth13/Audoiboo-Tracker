package org.audoiboo.tracker

/** Pure validation used by playback-position persistence and backup restore. */
internal object TrackPositionPolicy {
    fun normalize(value: Any?): Long? = when (value) {
        is Byte -> value.toLong().takeIf { it >= 0L }
        is Short -> value.toLong().takeIf { it >= 0L }
        is Int -> value.toLong().takeIf { it >= 0L }
        is Long -> value.takeIf { it >= 0L }
        is Float -> value.takeIf { it.isFinite() && it >= 0f }?.toLong()
        is Double -> value.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        else -> null
    }

    fun validKey(uri: String): Boolean = uri.isNotBlank() && uri.length <= 8192
}
