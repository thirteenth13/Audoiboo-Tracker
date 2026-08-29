package org.audoiboo.tracker

internal object PlayerExtrasValuePolicy {
    private val dayPattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun text(value: Any?, allowBlank: Boolean = true): String? {
        val text = value as? String ?: return null
        return text.takeIf { allowBlank || it.isNotBlank() }
    }

    fun nonNegativeLong(value: Any?): Long? {
        val number = value as? Number ?: return null
        val asDouble = number.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        val long = number.toLong()
        return long.takeIf { it >= 0L && long.toDouble() == asDouble }
    }

    fun validDay(value: Any?): String? =
        text(value, allowBlank = false)?.takeIf(dayPattern::matches)
}
