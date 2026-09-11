package org.audoiboo.tracker.tts

import java.io.File
import java.util.Properties

/** Durable checkpoint store for resumable TTS jobs. */
class TtsSessionStore(
    private val root: File,
) {
    @Synchronized
    fun save(session: TtsSession) {
        require(session.sessionId.isNotBlank())
        root.mkdirs()
        require(root.isDirectory) { "Cannot create TTS session directory" }
        val target = fileFor(session.sessionId)
        val temp = File(target.parentFile, target.name + ".tmp")
        val props = Properties().apply {
            setProperty("version", FORMAT_VERSION.toString())
            setProperty("sessionId", session.sessionId)
            setProperty("providerId", session.providerId)
            setProperty("voice.id", session.voice.id)
            setProperty("voice.displayName", session.voice.displayName)
            setProperty("voice.language", session.voice.language)
            setProperty("voice.modelId", session.voice.modelId)
            setProperty("voice.modelVersion", session.voice.modelVersion)
            session.voice.speakerId?.let { setProperty("voice.speakerId", it.toString()) }
            setProperty("documentFingerprint", session.documentFingerprint)
            setProperty("speed", session.speed.toString())
            setProperty("state", session.state.name)
            setProperty("nextGlobalChunkIndex", session.nextGlobalChunkIndex.toString())
            setProperty("completedChapterIndexes", session.completedChapterIndexes.sorted().joinToString(","))
            session.lastError?.let { setProperty("lastError", it) }
        }
        temp.outputStream().buffered().use { props.store(it, null) }
        if (target.exists()) require(target.delete()) { "Cannot replace TTS session checkpoint" }
        require(temp.renameTo(target)) { "Cannot commit TTS session checkpoint" }
    }

    @Synchronized
    fun load(sessionId: String): TtsSession? {
        require(sessionId.isNotBlank())
        val file = fileFor(sessionId)
        if (!file.isFile) return null
        return runCatching {
            val props = Properties().apply { file.inputStream().buffered().use(::load) }
            require(props.getProperty("version")?.toIntOrNull() == FORMAT_VERSION) { "Unsupported TTS session format" }
            val storedSessionId = required(props, "sessionId")
            require(storedSessionId == sessionId) { "TTS session id mismatch" }
            val voice = TtsVoice(
                id = required(props, "voice.id"),
                displayName = required(props, "voice.displayName"),
                language = required(props, "voice.language"),
                modelId = required(props, "voice.modelId"),
                modelVersion = required(props, "voice.modelVersion"),
                speakerId = props.getProperty("voice.speakerId")?.toIntOrNull(),
            )
            val completed = props.getProperty("completedChapterIndexes")
                .orEmpty()
                .split(',')
                .mapNotNull { it.trim().takeIf(String::isNotBlank)?.toIntOrNull() }
                .toSet()
            TtsSession(
                sessionId = storedSessionId,
                providerId = required(props, "providerId"),
                voice = voice,
                documentFingerprint = required(props, "documentFingerprint"),
                speed = required(props, "speed").toFloat(),
                state = TtsSessionState.valueOf(required(props, "state")),
                nextGlobalChunkIndex = required(props, "nextGlobalChunkIndex").toInt(),
                completedChapterIndexes = completed,
                lastError = props.getProperty("lastError"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun delete(sessionId: String): Boolean {
        require(sessionId.isNotBlank())
        val target = fileFor(sessionId)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.delete()
        return !target.exists() || target.delete()
    }

    private fun fileFor(sessionId: String): File = File(root, TtsStableId.hex(sessionId) + ".properties")

    private fun required(props: Properties, key: String): String =
        props.getProperty(key)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing TTS session field: $key")

    companion object {
        private const val FORMAT_VERSION = 1
    }
}
