package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.SourceBook

/**
 * Convenience factory for Room migration code that creates a minimal provider book from an
 * existing canonical row. Keeping this named overload avoids relying on SourceBook constructor
 * parameter order, which is intentionally richer than the three fields needed here.
 */
internal fun SourceBook(
    sourceId: String,
    url: String,
    title: String,
    seriesTitle: String? = null
): SourceBook = SourceBook(
    sourceId = sourceId,
    url = url,
    title = title,
    seriesTitle = seriesTitle
)
