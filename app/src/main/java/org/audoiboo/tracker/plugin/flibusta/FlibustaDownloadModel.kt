package org.audoiboo.tracker.plugin.flibusta

import java.util.Locale

enum class FlibustaPayloadKind { ZIP_FB2, RAW_FB2, HTML, UNKNOWN }

enum class FlibustaFailureCode {
    UNSUPPORTED_HOST, NETWORK, FORBIDDEN, NOT_FOUND, RATE_LIMITED,
    TEMPORARILY_UNAVAILABLE, HTTP_ERROR, INVALID_BOOK_PAGE,
    NO_FB2_LINK, INVALID_PAYLOAD, REDIRECT_LOOP, TOO_MANY_INTERSTITIALS
}

sealed interface FlibustaResolveResult {
    data class Success(
        val bytes: ByteArray,
        val finalUrl: String,
        val kind: FlibustaPayloadKind,
        val contentType: String?,
        val fileName: String
    ) : FlibustaResolveResult

    data class Failure(
        val code: FlibustaFailureCode,
        val message: String,
        val httpStatus: Int? = null
    ) : FlibustaResolveResult
}

data class FlibustaHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0)
) {
    val contentType: String?
        get() = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

    val fileName: String?
        get() {
            val value = header("Content-Disposition") ?: return null
            return Regex("filename=\\\"?([^;\\\"]+)\\\"?", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.getOrNull(1)?.trim()
        }

    private fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

fun interface FlibustaSleeper { fun sleep(millis: Long) }

data class FlibustaRetryPolicy(
    val maxAttempts: Int = 4,
    val backoffMillis: List<Long> = listOf(2_000L, 5_000L, 15_000L)
) {
    fun shouldRetry(status: Int): Boolean = status == 429 || status in 500..599
    fun delay(attempt: Int): Long = backoffMillis.getOrElse(attempt) { backoffMillis.lastOrNull() ?: 1_000L }
}

internal class FlibustaCookieJar {
    private val values = linkedMapOf<String, String>()

    fun absorb(headers: Map<String, List<String>>) {
        headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { raw ->
            val pair = raw.substringBefore(';')
            val split = pair.indexOf('=')
            if (split > 0) values[pair.substring(0, split).trim()] = pair.substring(split + 1).trim()
        }
    }

    fun header(): String? = values.takeIf { it.isNotEmpty() }
        ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
}

object FlibustaPayloadClassifier {
    fun classify(bytes: ByteArray, contentType: String? = null): FlibustaPayloadKind {
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            val a = bytes[2].toInt() and 0xff
            val b = bytes[3].toInt() and 0xff
            if ((a == 3 && b == 4) || (a == 5 && b == 6) || (a == 7 && b == 8)) {
                return FlibustaPayloadKind.ZIP_FB2
            }
        }
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 4096)).toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.ROOT)
        if (contentType?.contains("text/html", true) == true ||
            sample.startsWith("<!doctype html") || sample.startsWith("<html")) {
            return FlibustaPayloadKind.HTML
        }
        if ((sample.startsWith("<?xml") && sample.contains("fictionbook")) || sample.startsWith("<fictionbook")) {
            return FlibustaPayloadKind.RAW_FB2
        }
        return FlibustaPayloadKind.UNKNOWN
    }
}
