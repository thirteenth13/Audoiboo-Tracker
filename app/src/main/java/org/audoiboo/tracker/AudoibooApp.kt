package org.audoiboo.tracker

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class AudoibooApp : Application() {
    private lateinit var playerExtrasPrefs: SharedPreferences
    private val playerExtrasListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ContinueListeningWidget.updateAll(this)
    }

    override fun onCreate() {
        super.onCreate()
        DownloadScheduler.recover(this)
        WebDavSync.schedule(this)
        SeriesAutomationPrefs.schedule(this)
        CoverCache.enqueueTrackerCovers(this)
        playerExtrasPrefs = getSharedPreferences("player_extras", Context.MODE_PRIVATE)
        playerExtrasPrefs.registerOnSharedPreferenceChangeListener(playerExtrasListener)
        ContinueListeningWidget.updateAll(this)
    }
}
