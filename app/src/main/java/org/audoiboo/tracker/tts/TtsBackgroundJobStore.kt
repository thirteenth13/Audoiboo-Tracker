package org.audoiboo.tracker.tts

import java.io.File
import java.security.MessageDigest
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.json.JSONArray
import org.json.JSONObject

internal data class TtsBackgroundBookJob(
    val sessionId: String,
    val document: BookDocument,
    val model: VoiceModelSpec,
    val outputDir: String,
    val quality: TtsQuality = TtsQuality.FAST,
    val engineFamily: TtsEngineFamily = TtsEngineFamily.PIPER_VITS,
    val chunkCount: Int = TtsSynthesisPlanner.build(document).chunkCount,
) {
    init {
        require(sessionId.isNotBlank())
        require(outputDir.isNotBlank())
        require(chunkCount >= 0)
        require(
            (quality == TtsQuality.FAST && engineFamily == TtsEngineFamily.PIPER_VITS) ||
                (quality == TtsQuality.HIGH_QUALITY && engineFamily == TtsEngineFamily.SUPERTONIC)
        ) { "TTS background job quality must match its engine family" }
    }
}

internal class TtsBackgroundJobStore(private val root: File) {
    @Synchronized fun save(job: TtsBackgroundBookJob) {
        root.mkdirs(); require(root.isDirectory) { "Cannot create TTS job directory" }
        val documentText = encodeDocument(job.document).toString()
        val expectedDigest = documentDigest(documentText)
        val documentFile = documentFileFor(job.sessionId, expectedDigest)
        val existed = documentFile.exists()
        val valid = documentFile.isFile && runCatching { documentDigest(documentFile.readText(Charsets.UTF_8)) == expectedDigest }.getOrDefault(false)
        var created = false
        if (!valid) {
            val temp = File(documentFile.parentFile, documentFile.name + ".tmp")
            temp.writeText(documentText, Charsets.UTF_8)
            commitTempFile(temp, documentFile, "Cannot commit TTS background document")
            created = !existed
        }
        val target = fileFor(job.sessionId); val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encode(job, documentFile.name).toString(), Charsets.UTF_8)
        try { commitTempFile(temp, target, "Cannot commit TTS background job") }
        catch (error: Throwable) { temp.delete(); if (created) documentFile.delete(); throw error }
        cleanupDocuments(job.sessionId, documentFile)
    }

    @Synchronized fun load(sessionId: String): TtsBackgroundBookJob? {
        require(sessionId.isNotBlank()); val file = fileFor(sessionId); if (!file.isFile) return null
        return runCatching { decode(JSONObject(file.readText(Charsets.UTF_8)), sessionId) }.getOrNull()
    }

    @Synchronized fun delete(sessionId: String): Boolean {
        require(sessionId.isNotBlank()); val target=fileFor(sessionId); File(target.parentFile,target.name+".tmp").delete(); File(target.parentFile,target.name+".bak").delete()
        if (target.exists() && !target.delete()) return false
        return cleanupDocuments(sessionId, null)
    }

    private fun encode(job:TtsBackgroundBookJob, documentFileName:String)=JSONObject()
        .put("version",FORMAT_VERSION).put("sessionId",job.sessionId).put("outputDir",job.outputDir).put("quality",job.quality.name).put("engineFamily",job.engineFamily.name)
        .put("chunkCount",job.chunkCount).put("documentFile",documentFileName)
        .put("model",JSONObject().put("modelId",job.model.modelId).put("version",job.model.version).put("language",job.model.language).put("sha256",job.model.sha256).put("fileName",job.model.fileName))

    private fun encodeDocument(document:BookDocument)=JSONObject().put("title",document.title).put("authors",JSONArray(document.authors)).put("language",document.language).put("series",document.series).put("seriesNumber",document.seriesNumber)
        .put("chapters",JSONArray().apply{document.chapters.forEach{chapter->put(JSONObject().put("index",chapter.index).put("title",chapter.title).put("blocks",JSONArray(chapter.blocks)))}})

    private fun decode(root:JSONObject, requestedSessionId:String):TtsBackgroundBookJob {
        val version=root.optInt("version",-1); require(version in 1..FORMAT_VERSION){"Unsupported TTS background job format"}
        val sessionId=root.getString("sessionId"); require(sessionId==requestedSessionId){"TTS background job session mismatch"}; val modelJson=root.getJSONObject("model")
        val documentJson=if(root.has("documentFile")&&!root.isNull("documentFile")){val name=root.getString("documentFile");require(DOCUMENT_FILE_NAME.matches(name));val prefix=TtsStableId.hex(sessionId)+".";require(name.startsWith(prefix));val digest=name.removePrefix(prefix).removeSuffix(DOCUMENT_SUFFIX);val file=File(rootDir(),name);require(file.isFile);val text=file.readText(Charsets.UTF_8);require(documentDigest(text)==digest);JSONObject(text)}else root.getJSONObject("document")
        val document=decodeDocument(documentJson); val persistedChunkCount=root.optInt("chunkCount",-1)
        val quality=if(version>=2) TtsQuality.valueOf(root.getString("quality")) else TtsQuality.FAST
        val engine=if(version>=2) TtsEngineFamily.valueOf(root.getString("engineFamily")) else TtsEngineFamily.PIPER_VITS
        return TtsBackgroundBookJob(sessionId,document,VoiceModelSpec(modelJson.getString("modelId"),modelJson.getString("version"),modelJson.getString("language"),modelJson.getString("sha256"),modelJson.getString("fileName")),root.getString("outputDir"),quality,engine,if(persistedChunkCount>=0)persistedChunkCount else TtsSynthesisPlanner.build(document).chunkCount)
    }

    private fun decodeDocument(j:JSONObject):BookDocument { val chapters=j.getJSONArray("chapters"); return BookDocument(nullableString(j,"title"),strings(j.getJSONArray("authors")),nullableString(j,"language"),nullableString(j,"series"),if(j.isNull("seriesNumber"))null else j.getInt("seriesNumber"),(0 until chapters.length()).map{idx->val c=chapters.getJSONObject(idx);BookChapter(c.getInt("index"),c.getString("title"),strings(c.getJSONArray("blocks")))}) }
    private fun documentFileFor(sessionId:String,digest:String)=File(rootDir(),"${TtsStableId.hex(sessionId)}.$digest$DOCUMENT_SUFFIX")
    private fun documentDigest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun commitTempFile(temp:File,target:File,msg:String){val backup=File(target.parentFile,target.name+".bak");require(!backup.exists()||backup.delete());if(temp.renameTo(target))return;if(!target.exists()){temp.delete();error(msg)};require(target.renameTo(backup)){msg};try{require(temp.renameTo(target)){msg};backup.delete()}catch(e:Throwable){target.delete();backup.renameTo(target);throw e}}
    private fun cleanupDocuments(sessionId:String,keep:File?):Boolean{val prefix=TtsStableId.hex(sessionId)+".";var ok=true;rootDir().listFiles()?.forEach{f->if(f.name.startsWith(prefix)&&(f.name.endsWith(DOCUMENT_SUFFIX)||f.name.endsWith(DOCUMENT_SUFFIX+".tmp")||f.name.endsWith(DOCUMENT_SUFFIX+".bak"))&&f.absolutePath!=keep?.absolutePath)ok=f.delete()&&ok};return ok}
    private fun strings(a:JSONArray)= (0 until a.length()).map{a.getString(it)}
    private fun nullableString(j:JSONObject,key:String)=if(!j.has(key)||j.isNull(key))null else j.getString(key)
    private fun rootDir()=root
    private fun fileFor(sessionId:String)=File(rootDir(),TtsStableId.hex(sessionId)+".json")
    companion object { private const val FORMAT_VERSION=2; private const val DOCUMENT_SUFFIX=".document.json"; private val DOCUMENT_FILE_NAME=Regex("^[0-9a-f]{64}\\.[0-9a-f]{64}\\.document\\.json$") }
}
