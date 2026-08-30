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

    fun supports(url: String): Boolean =
        KnigavuheWebViewMediaCapture.isAllowedPage(url) ||
            BazaKnigWebViewMediaCapture.isAllowedPage(url) ||
            PoleknigWebViewMediaCapture.isAllowedPage(url) ||
            Lis10BookWebViewMediaCapture.isAllowedPage(url)

    suspend fun resolve(book: SourceBook): List<DownloadCandidate> {
        val context = appContext ?: return emptyList()
        if (!supports(book.url)) return emptyList()
        return suspendCancellableCoroutine { continuation ->
            fun complete(urls: List<String>, quality: String, type: DownloadType = DownloadType.STREAM) {
                if (!continuation.isActive) return
                val candidates = urls.distinct().mapIndexed { index, url ->
                    DownloadCandidate(
                        type = type,
                        url = url,
                        fileName = fileName(url),
                        quality = quality,
                        priority = 200 - index
                    )
                }
                continuation.resume(candidates)
            }

            when {
                KnigavuheWebViewMediaCapture.isAllowedPage(book.url) ->
                    KnigavuheWebViewMediaCapture(context).capture(book.url) { result ->
                        complete(result.mediaUrls, "device-webview-knigavuhe")
                    }
                BazaKnigWebViewMediaCapture.isAllowedPage(book.url) ->
                    BazaKnigWebViewMediaCapture(context).capture(book.url) { result ->
                        complete(result.mediaUrls, "device-webview-baza-knig")
                    }
                PoleknigWebViewMediaCapture.isAllowedPage(book.url) ->
                    PoleknigWebViewMediaCapture(context).capture(book.url) { result ->
                        complete(result.mediaUrls, "device-webview-poleknig", DownloadType.DIRECT_FILE)
                    }
                Lis10BookWebViewMediaCapture.isAllowedPage(book.url) ->
                    Lis10BookWebViewMediaCapture(context).capture(book.url) { result ->
                        complete(result.mediaUrls, "device-webview-lis10book")
                    }
                else -> continuation.resume(emptyList())
            }
        }
    }

    private fun fileName(url: String): String? = runCatching {
        java.net.URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()
}
