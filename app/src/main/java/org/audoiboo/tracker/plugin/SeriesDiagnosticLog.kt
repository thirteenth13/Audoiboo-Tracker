package org.audoiboo.tracker.plugin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-process diagnostic buffer so series discovery logs can be copied without adb/logcat. */
object SeriesDiagnosticLog {
    private const val MAX_LINES = 1200
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    fun i(message: String) = append("I", message)
    fun w(message: String) = append("W", message)
    fun e(message: String, error: Throwable? = null) {
        append("E", buildString {
            append(message)
            if (error != null) append(" | ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        })
    }

    fun snapshot(): String = synchronized(lock) {
        if (lines.isEmpty()) "AudoibooSeries: журнал поки порожній" else lines.joinToString("\n")
    }

    fun clear() = synchronized(lock) { lines.clear() }

    private fun append(level: String, message: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            lines.addLast("$stamp $level AudoibooSeries: $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }
}
