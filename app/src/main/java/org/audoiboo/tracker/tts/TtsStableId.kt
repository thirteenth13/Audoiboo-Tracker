package org.audoiboo.tracker.tts

import java.security.MessageDigest

/** Stable collision-resistant identifiers derived from the opaque TTS session id. */
internal object TtsStableId {
    fun hex(sessionId: String): String {
        require(sessionId.isNotBlank())
        return digest(sessionId).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun notificationId(sessionId: String): Int {
        val bytes = digest(sessionId)
        val value = ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        val positive = value and Int.MAX_VALUE
        return if (positive == 0) 1 else positive
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
