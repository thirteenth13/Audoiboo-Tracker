package org.audoiboo.tracker.tts

import java.io.File
import java.security.MessageDigest

data class VoiceModelSpec(
    val modelId: String,
    val version: String,
    val language: String,
    val sha256: String,
    val fileName: String,
) {
    init {
        require(modelId.isNotBlank())
        require(version.isNotBlank())
        require(language.isNotBlank())
        require(sha256.length == 64)
        require(fileName.isNotBlank())
    }

    val stableKey: String get() = "$modelId:$version:${language.lowercase()}"
}

class VoiceModelManager(private val modelsRoot: File) {
    fun modelDir(spec: VoiceModelSpec): File = File(modelsRoot, safe(spec.modelId) + "/" + safe(spec.version))
    fun modelFile(spec: VoiceModelSpec): File = File(modelDir(spec), spec.fileName)

    fun isInstalled(spec: VoiceModelSpec): Boolean {
        val file = modelFile(spec)
        return file.isFile && digest(file).equals(spec.sha256, ignoreCase = true)
    }

    fun verify(spec: VoiceModelSpec): Result<File> = runCatching {
        val file = modelFile(spec)
        require(file.isFile) { "Voice model file is missing" }
        require(digest(file).equals(spec.sha256, ignoreCase = true)) { "Voice model checksum mismatch" }
        file
    }

    fun remove(spec: VoiceModelSpec): Boolean = modelDir(spec).deleteRecursively()

    companion object {
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) md.update(buffer, 0, count)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
