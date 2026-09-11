package org.audoiboo.tracker.tts

import java.io.File
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.json.JSONArray
import org.json.JSONObject

/** Durable input required to reconstruct a background TTS run after process death. */
internal data class TtsBackgroundBookJob(
    val sessionId: String,
    val document: BookDocument,
    val model: VoiceModelSpec,
    val outputDir: String,
) {
    init {
        require(sessionId.isNotBlank())
        require(outputDir.isNotBlank())
    }
}

internal class TtsBackgroundJobStore(private val root: File) {
    @Synchronized
    fun save(job: TtsBackgroundBookJob) {
        root.mkdirs()
        require(root.isDirectory) { "Cannot create TTS job directory" }
        val target = fileFor(job.sessionId)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encode(job).toString(), Charsets.UTF_8)
        if (target.exists()) require(target.delete()) { "Cannot replace TTS background job" }
        require(temp.renameTo(target)) { "Cannot commit TTS background job" }
    }

    @Synchronized
    fun load(sessionId: String): TtsBackgroundBookJob? {
        val file = fileFor(sessionId)
        if (!file.isFile) return null
        return runCatching { decode(JSONObject(file.readText(Charsets.UTF_8)), sessionId) }.getOrNull()
    }

    @Synchronized
    fun delete(sessionId: String): Boolean {
        val target = fileFor(sessionId)
        File(target.parentFile, target.name + ".tmp").delete()
        return !target.exists() || target.delete()
    }

    private fun encode(job: TtsBackgroundBookJob): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("sessionId", job.sessionId)
        .put("outputDir", job.outputDir)
        .put("model", JSONObject()
            .put("modelId", job.model.modelId)
            .put("version", job.model.version)
            .put("language", job.model.language)
            .put("sha256", job.model.sha256)
            .put("fileName", job.model.fileName))
        .put("document", JSONObject()
            .put("title", job.document.title)
            .put("authors", JSONArray(job.document.authors))
            .put("language", job.document.language)
            .put("series", job.document.series)
            .put("seriesNumber", job.document.seriesNumber)
            .put("chapters", JSONArray().apply {
                job.document.chapters.forEach { chapter ->
                    put(JSONObject()
                        .put("index", chapter.index)
                        .put("title", chapter.title)
                        .put("blocks", JSONArray(chapter.blocks)))
                }
            }))

    private fun decode(root: JSONObject, requestedSessionId: String): TtsBackgroundBookJob {
        require(root.optInt("version", -1) == FORMAT_VERSION) { "Unsupported TTS background job format" }
        val sessionId = root.getString("sessionId")
        require(sessionId == requestedSessionId) { "TTS background job session mismatch" }
        val modelJson = root.getJSONObject("model")
        val documentJson = root.getJSONObject("document")
        val chaptersJson = documentJson.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map { index ->
            val chapter = chaptersJson.getJSONObject(index)
            BookChapter(
                index = chapter.getInt("index"),
                title = chapter.getString("title"),
                blocks = strings(chapter.getJSONArray("blocks")),
            )
        }
        return TtsBackgroundBookJob(
            sessionId = sessionId,
            document = BookDocument(
                title = nullableString(documentJson, "title"),
                authors = strings(documentJson.getJSONArray("authors")),
                language = nullableString(documentJson, "language"),
                series = nullableString(documentJson, "series"),
                seriesNumber = if (documentJson.isNull("seriesNumber")) null else documentJson.getInt("seriesNumber"),
                chapters = chapters,
            ),
            model = VoiceModelSpec(
                modelId = modelJson.getString("modelId"),
                version = modelJson.getString("version"),
                language = modelJson.getString("language"),
                sha256 = modelJson.getString("sha256"),
                fileName = modelJson.getString("fileName"),
            ),
            outputDir = root.getString("outputDir"),
        )
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    private fun nullableString(root: JSONObject, key: String): String? =
        if (!root.has(key) || root.isNull(key)) null else root.getString(key)

    private fun fileFor(sessionId: String): File = File(root, safe(sessionId) + ".json")
    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val FORMAT_VERSION = 1
    }
}
