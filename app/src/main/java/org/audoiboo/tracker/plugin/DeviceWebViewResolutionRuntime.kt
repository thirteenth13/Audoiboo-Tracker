package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Host-owned device browser fallback for sources that intentionally vary content by client/network. */
object DeviceWebViewResolutionRuntime {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun supports(url: String): Boolean = KnigavuheWebViewMediaCapture.isAllowedPage(url)

    suspend fun resolve(book: SourceBook): List<DownloadCandidate> {
        val context = appContext ?: return emptyList()
        if (!supports(book.url)) return emptyList()
        return suspendCancellableCoroutine { continuation ->
            KnigavuheWebViewMediaCapture(context).capture(book.url) { result ->
                if (!continuation.isActive) return@capture
                val candidates = result.mediaUrls
                    .distinct()
                    .mapIndexed { index, url ->
                        DownloadCandidate(
                            type = DownloadType.STREAM,
                            url = url,
                            fileName = fileName(url),
                            quality = "device-webview",
                            priority = 200 - index
                        )
                    }
                continuation.resume(candidates)
            }
        }
    }

    private fun fileName(url: String): String? = runCatching {
        android.net.Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
