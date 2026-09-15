package org.audoiboo.tracker.plugin.flibusta

class FlibustaDownloadResolver(
    private val transport: FlibustaTransport = HttpUrlConnectionFlibustaTransport(),
    private val sleeper: FlibustaSleeper = FlibustaSleeper { Thread.sleep(it) },
    private val retryPolicy: FlibustaRetryPolicy = FlibustaRetryPolicy(),
    private val maxHtmlHops: Int = 3
) {
    fun resolveFb2(bookUrl: String): FlibustaResolveResult {
        val parser = FlibustaParserRegistry.forUrl(bookUrl)
            ?: return failure(FlibustaFailureCode.UNSUPPORTED_HOST, "Unsupported Flibusta host")
        val cookies = FlibustaCookieJar()
        val pageResponse = getWithRetry(bookUrl, cookies)
            ?: return failure(FlibustaFailureCode.NETWORK, "Failed to load book page")
        if (pageResponse.statusCode !in 200..299) return httpFailure(pageResponse)

        val html = pageResponse.body.asHtml()
            ?: return failure(FlibustaFailureCode.INVALID_BOOK_PAGE, "Book page is not HTML")
        var page = parser.parseBookPage(html, pageResponse.finalUrl)
        var candidate = chooseFb2(page) ?: directSiteFallback(page)
            ?: return failure(FlibustaFailureCode.NO_FB2_LINK, "FB2 link was not found")
        val visited = linkedSetOf<String>()

        repeat(maxHtmlHops.coerceIn(1, 8)) {
            if (!visited.add(normalizeUrl(candidate.url))) {
                return failure(FlibustaFailureCode.REDIRECT_LOOP, "FB2 interstitial loop")
            }
            val waitSeconds = maxOf(page.waitSeconds, candidate.waitSeconds).coerceIn(0, 120)
            if (waitSeconds > 0) sleeper.sleep(waitSeconds * 1_000L)

            val response = getWithRetry(
                candidate.url,
                cookies,
                mapOf("Referer" to page.canonicalUrl)
            ) ?: return failure(FlibustaFailureCode.NETWORK, "Failed to download FB2")
            if (response.statusCode !in 200..299) return httpFailure(response)

            when (val kind = FlibustaPayloadClassifier.classify(response.body, response.contentType)) {
                FlibustaPayloadKind.ZIP_FB2, FlibustaPayloadKind.RAW_FB2 -> {
                    return FlibustaResolveResult.Success(
                        response.body,
                        response.finalUrl,
                        kind,
                        response.contentType,
                        response.fileName ?: defaultFileName(page.remoteId, kind)
                    )
                }
                FlibustaPayloadKind.HTML -> {
                    val nextHtml = response.body.asHtml()
                        ?: return failure(FlibustaFailureCode.INVALID_PAYLOAD, "Invalid HTML interstitial")
                    val nextParser = FlibustaParserRegistry.forUrl(response.finalUrl) ?: parser
                    page = nextParser.parseBookPage(nextHtml, response.finalUrl)
                    candidate = chooseFb2(page) ?: directSiteFallback(page)
                        ?: return failure(FlibustaFailureCode.NO_FB2_LINK, "Interstitial has no FB2 link")
                }
                FlibustaPayloadKind.UNKNOWN ->
                    return failure(FlibustaFailureCode.INVALID_PAYLOAD, "Response is not FB2/ZIP/HTML")
            }
        }
        return failure(FlibustaFailureCode.TOO_MANY_INTERSTITIALS, "Too many HTML interstitials")
    }

    private fun chooseFb2(page: FlibustaBookPage): FlibustaDownloadLink? = page.downloads
        .asSequence()
        .filter { it.format == FlibustaFormat.FB2 && it.trustedByParser }
        .sortedWith(compareByDescending<FlibustaDownloadLink> { sameHost(it.url, page.canonicalUrl) }
            .thenBy { it.waitSeconds })
        .firstOrNull()

    private fun directSiteFallback(page: FlibustaBookPage): FlibustaDownloadLink? {
        if (page.variant != FlibustaVariant.SITE) return null
        val id = page.remoteId ?: return null
        return FlibustaDownloadLink(FlibustaFormat.FB2, "https://flibusta.site/b/$id/fb2")
    }

    private fun getWithRetry(
        url: String,
        cookies: FlibustaCookieJar,
        extraHeaders: Map<String, String> = emptyMap()
    ): FlibustaHttpResponse? {
        repeat(retryPolicy.maxAttempts.coerceIn(1, 6)) { attempt ->
            val headers = linkedMapOf(
                "Accept" to "application/fb2+zip, application/zip, application/xml, text/xml, text/html;q=0.8, */*;q=0.5",
                "User-Agent" to "Audoiboo-Tracker/FlibustaExperiment"
            )
            cookies.header()?.let { headers["Cookie"] = it }
            headers.putAll(extraHeaders)
            val response = runCatching { transport.get(url, headers) }.getOrNull()
            if (response != null) {
                cookies.absorb(response.headers)
                if (!retryPolicy.shouldRetry(response.statusCode) || attempt == retryPolicy.maxAttempts - 1) {
                    return response
                }
            } else if (attempt == retryPolicy.maxAttempts - 1) return null
            sleeper.sleep(retryPolicy.delay(attempt))
        }
        return null
    }

    private fun httpFailure(response: FlibustaHttpResponse): FlibustaResolveResult.Failure {
        val code = when (response.statusCode) {
            403 -> FlibustaFailureCode.FORBIDDEN
            404 -> FlibustaFailureCode.NOT_FOUND
            429 -> FlibustaFailureCode.RATE_LIMITED
            in 500..599 -> FlibustaFailureCode.TEMPORARILY_UNAVAILABLE
            else -> FlibustaFailureCode.HTTP_ERROR
        }
        return FlibustaResolveResult.Failure(code, "HTTP ${response.statusCode}", response.statusCode)
    }

    private fun failure(code: FlibustaFailureCode, message: String) =
        FlibustaResolveResult.Failure(code, message)
}

private fun ByteArray.asHtml(): String? {
    val text = runCatching { toString(Charsets.UTF_8) }.getOrNull() ?: return null
    val head = text.take(2048).lowercase()
    return text.takeIf { head.contains("<html") || head.contains("<!doctype") || head.contains("<head") || head.contains("<body") }
}

private fun defaultFileName(remoteId: String?, kind: FlibustaPayloadKind): String {
    val id = remoteId?.takeIf { it.isNotBlank() } ?: "book"
    return if (kind == FlibustaPayloadKind.ZIP_FB2) "$id.fb2.zip" else "$id.fb2"
}
