package org.audoiboo.tracker.tts

enum class TtsSessionState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
}

data class TtsSessionCheckpoint(
    val documentFingerprint: String,
    val voiceStableKey: String,
    val nextGlobalChunkIndex: Int,
    val completedChapterIndexes: Set<Int> = emptySet(),
) {
    init {
        require(documentFingerprint.isNotBlank())
        require(voiceStableKey.isNotBlank())
        require(nextGlobalChunkIndex >= 0)
        require(completedChapterIndexes.all { it >= 0 })
    }
}

data class TtsSession(
    val sessionId: String,
    val providerId: String,
    val voice: TtsVoice,
    val documentFingerprint: String,
    val speed: Float,
    val state: TtsSessionState = TtsSessionState.QUEUED,
    val nextGlobalChunkIndex: Int = 0,
    val completedChapterIndexes: Set<Int> = emptySet(),
    val lastError: String? = null,
) {
    init {
        require(sessionId.isNotBlank())
        require(providerId.isNotBlank())
        require(documentFingerprint.isNotBlank())
        require(speed in 0.5f..2.0f)
        require(nextGlobalChunkIndex >= 0)
        require(completedChapterIndexes.all { it >= 0 })
    }

    fun checkpoint(): TtsSessionCheckpoint = TtsSessionCheckpoint(
        documentFingerprint = documentFingerprint,
        voiceStableKey = voice.stableKey,
        nextGlobalChunkIndex = nextGlobalChunkIndex,
        completedChapterIndexes = completedChapterIndexes,
    )

    fun advance(nextChunkIndex: Int, completedChapterIndex: Int? = null): TtsSession {
        require(nextChunkIndex >= nextGlobalChunkIndex)
        val chapters = if (completedChapterIndex == null) completedChapterIndexes
        else completedChapterIndexes + completedChapterIndex
        return copy(
            state = TtsSessionState.RUNNING,
            nextGlobalChunkIndex = nextChunkIndex,
            completedChapterIndexes = chapters,
            lastError = null,
        )
    }

    fun fail(message: String): TtsSession {
        require(message.isNotBlank())
        return copy(state = TtsSessionState.FAILED, lastError = message)
    }

    fun complete(): TtsSession = copy(state = TtsSessionState.COMPLETED, lastError = null)
}
