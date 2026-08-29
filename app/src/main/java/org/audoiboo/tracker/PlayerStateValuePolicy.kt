package org.audoiboo.tracker

internal object PlayerStateValuePolicy {
    fun validKey(value: String): Boolean = value.isNotBlank()

    fun speed(value: Any?): Float? {
        val number = value as? Number ?: return null
        val result = number.toDouble()
        if (!result.isFinite() || result < .5 || result > 3.0) return null
        return result.toFloat()
    }

    fun timestamp(value: Any?): Long? {
        val number = value as? Number ?: return null
        val result = number.toDouble()
        if (!result.isFinite() || result < 0.0 || result > Long.MAX_VALUE.toDouble()) return null
        val asLong = result.toLong()
        return asLong.takeIf { asLong.toDouble() == result }
    }

    fun text(value: Any?, allowBlank: Boolean = true): String? {
        val text = value as? String ?: return null
        return text.takeIf { allowBlank || it.isNotBlank() }
    }
}
