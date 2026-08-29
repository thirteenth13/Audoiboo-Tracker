package org.audoiboo.tracker

import android.content.Context

internal object AudioEnhancementPrefs {
    fun voiceBoost(context: Context): Boolean = AppSettingsStore.current(context).voiceBoost

    fun gainMb(context: Context): Int = AppSettingsStore.current(context).gainMb.coerceIn(0, 1200)

    fun save(context: Context, enabled: Boolean, gainMb: Int) {
        val current = AppSettingsStore.current(context)
        AppSettingsStore.save(
            context,
            current.copy(voiceBoost = enabled, gainMb = gainMb.coerceIn(0, 1200))
        )
    }
}
