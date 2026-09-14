package org.audoiboo.tracker.tts

import java.io.File
import java.security.MessageDigest
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.json.JSONArray
import org.json.JSONObject

/** Durable input required to reconstruct a background TTS run after process death. */
internal data class TtsBackgroundBookJob(
    val sessionId: String,
    val document: BookDocument,
    val model: VoiceModelSpec,
    val outputDir: String,
    val chunkCount: Int = TtsSynthesisPlanner.build(document).chunkCount,
) {
    init {
        require(sessionId.isNotBlank())
        require(outputDir.isNotBlank())
        require(chunkCount >= 0)
    }
}

internal class TtsBackgroundJobStore(private val root: File) {
    @Synchronized
    fun save(job: TtsBackgroundBookJob) {
        root.mkdirs()
        require(root.isDirectory) { "Cannot create TTS job directory" }

        val documentJson = encodeDocument(job.document)
        val documentText = documentJson.toString()
        val documentFile = documentFileFor(job.sessionId, documentText)
        if (!documentFile.isFile) {
            val documentTemp = File(documentFile.parentFile, documentFile.name + ".tmp")
            documentTemp.writeText(documentText, Charsets.UTF_8)
            require(documentTemp.renameTo(documentFile)) {
                documentTemp.delete()
                "Cannot commit TTS background document"
            }
        }

        val target = fileFor(job.sessionId)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encode(job, documentFile.name).toString(), Charsets.UTF_8)
        require(temp.renameTo(target)) {
            temp.delete()
            "Cannot commit TTS background job"
        }
        cleanupDocuments(job.sessionId, keep = documentFile)
    }

    @Synchronized
    fun load(sessionId: String): TtsBackgroundBookJob? {
        require(sessionId.isNotBlank())
        val file = fileFor(sessionId)
        if (!file.isFile) return null
        return runCatching { decode(JSONObject(file.readText(Charsets.UTF_8)), sessionId) }.getOrNull()
    }

    @Synchronized
    fun delete(sessionId: String): Boolean {
        require(sessionId.isNotBlank())
        val target = fileFor(sessionId)
        File(target.parentFile, target.name + ".tmp").delete()
        cleanupDocuments(sessionId, keep = null)
        return !target.exists() || target.delete()
    }

    private fun encode(job: TtsBackgroundBookJob, documentFileName: String): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("sessionId", job.sessionId)
        .put("outputDir", job.outputDir)
        .put("chunkCount", job.chunkCount)
        .put("documentFile", documentFileName)
        .put("model", JSONObject()
            .put("modelId", job.model.modelId)
            .put("version", job.model.version)
            .put("language", job.model.language)
            .put("sha256", job.model.sha256)
            .put("fileName", job.model.fileName))

    private fun encodeDocument(document: BookDocument): JSONObject = JSONObject()
        .put("title", document.title)
        .put("authors", JSONArray(document.authors))
        .put("language", document.language)
        .put("series", document.series)
        .put("seriesNumber", document.seriesNumber)
        .put("chapters", JSONArray().apply {
            document.chapters.forEach { chapter ->
                put(JSONObject()
                    .put("index", chapter.index)
                    .put("title", chapter.title)
                    .put("blocks", JSONArray(chapter.blocks)))
            }
        })

    private fun decode(root: JSONObject, requestedSessionId: String): TtsBackgroundBookJob {
        require(root.optInt("version", -1) == FORMAT_VERSION) { "Unsupported TTS background job format" }
        val sessionId = root.getString("sessionId")
        require(sessionId == requestedSessionId) { "TTS background job session mismatch" }
        val modelJson = root.getJSONObject("model")
        val documentJson = if (root.has("documentFile") && !root.isNull("documentFile")) {
            val name = root.getString("documentFile")
            require(DOCUMENT_FILE_NAME.matches(name)) { "Invalid TTS background document path" }
            val file = File(rootDir(), name)
            require(file.isFile) { "TTS background document is missing" }
            JSONObject(file.readText(Charsets.UTF_8))
        } else {
            // Backward compatibility with jobs persisted before documents were split from metadata.
            root.getJSONObject("document")
        }
        val document = decodeDocument(documentJson)
        val persistedChunkCount = root.optInt("chunkCount", -1)
        return TtsBackgroundBookJob(
            sessionId = sessionId,
            document = document,
            model = VoiceModelSpec(
                modelId = modelJson.getString("modelId"),
                version = modelJson.getString("version"),
                language = modelJson.getString("language"),
                sha256 = modelJson.getString("sha256"),
                fileName = modelJson.getString("fileName"),
            ),
            outputDir = root.getString("outputDir"),
            chunkCount = if (persistedChunkCount >= 0) persistedChunkCount else TtsSynthesisPlanner.build(document).chunkCount,
        )
    }

    private fun decodeDocument(documentJson: JSONObject): BookDocument {
        val chaptersJson = documentJson.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map { index ->
            val chapter = chaptersJson.getJSONObject(index)
            BookChapter(
                index = chapter.getInt("index"),
                title = chapter.getString("title"),
                blocks = strings(chapter.getJSONArray("blocks")),
            )
        }
        return BookDocument(
            title = nullableString(documentJson, "title"),
            authors = strings(documentJson.getJSONArray("authors")),
            language = nullableString(documentJson, "language"),
            series = nullableString(documentJson, "series"),
            seriesNumber = if (documentJson.isNull("seriesNumber")) null else documentJson.getInt("seriesNumber"),
            chapters = chapters,
        )
    }

    private fun documentFileFor(sessionId: String, documentText: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(documentText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(rootDir(), "${TtsStableId.hex(sessionId)}.$digest.document.json")
    }

    private fun cleanupDocuments(sessionId: String, keep: File?) {
        val prefix = TtsStableId.hex(sessionId) + "."
        rootDir().listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) &&
                (file.name.endsWith(".document.json") || file.name.endsWith(".document.json.tmp")) &&
                file.absolutePath != keep?.absolutePath
            ) {
                file.delete()
            }
        }
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    private fun nullableString(root: JSONObject, key: String): String? =
        if (!root.has(key) || root.isNull(key)) null else root.getString(key)

    private fun rootDir(): File = root
    private fun fileFor(sessionId: String): File = File(rootDir(), TtsStableId.hex(sessionId) + ".json")

    companion object {
        private const val FORMAT_VERSION = 1
        private val DOCUMENT_FILE_NAME = Regex("^[0-9a-f]{64}\\.[0-9a-f]{64}\\.document\\.json$")
    }
}
