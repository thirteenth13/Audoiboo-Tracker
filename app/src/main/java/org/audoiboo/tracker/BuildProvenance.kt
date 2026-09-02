package org.audoiboo.tracker

object BuildProvenance {
    val shortCommit: String get() = BuildConfig.BUILD_COMMIT.take(12)
    val label: String get() = "${BuildConfig.VERSION_NAME} • ${shortCommit} • CI ${BuildConfig.BUILD_RUN}"
}
