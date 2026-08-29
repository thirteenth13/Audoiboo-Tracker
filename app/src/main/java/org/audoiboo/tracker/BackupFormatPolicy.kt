package org.audoiboo.tracker

/** Pure validation rules for backup envelopes so restore can fail before mutating app state. */
internal object BackupFormatPolicy {
    const val CURRENT_FORMAT = 12

    fun validate(
        format: Int?,
        hasTracker: Boolean,
        trackerIsValidArray: Boolean,
        hasDownloads: Boolean = false,
        downloadsAreValid: Boolean = true,
        sectionsAreValid: Boolean = true
    ): String? {
        if (!hasTracker) return "Backup is missing tracker data"
        if (!trackerIsValidArray) return "Backup tracker data is invalid"
        if (format != null && format > CURRENT_FORMAT) {
            return "Backup format $format is newer than supported format $CURRENT_FORMAT"
        }
        if (format != null && format < 0) return "Backup format is invalid"
        if (hasDownloads && !downloadsAreValid) return "Backup downloads data is invalid"
        if (!sectionsAreValid) return "Backup contains invalid section data"
        return null
    }
}
