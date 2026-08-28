package org.audoiboo.tracker

import android.content.Context

internal object AudioEnhancementPrefs {
    private const val PREFS = "audio_enhancement"
    private const val VOICE_BOOST = "voice_boost"
    private const val GAIN_MB = "gain_mb"

    fun voiceBoost(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(VOICE_BOOST, false)
    fun gainMb(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(GAIN_MB, 600).coerceIn(0, 1200)

    fun save(context: Context, enabled: Boolean, gainMb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(VOICE_BOOST, enabled)
            .putInt(GAIN_MB, gainMb.coerceIn(0, 1200))
            .apply()
    }
}
