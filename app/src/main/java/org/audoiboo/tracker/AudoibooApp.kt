package org.audoiboo.tracker

import android.app.Application

class AudoibooApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadScheduler.recover(this)
        WebDavSync.schedule(this)
        SeriesAutomationPrefs.schedule(this)
    }
}
