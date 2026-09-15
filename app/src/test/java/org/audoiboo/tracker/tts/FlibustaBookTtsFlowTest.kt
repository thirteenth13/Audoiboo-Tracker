package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.audoiboo.tracker.plugin.flibusta.FlibustaFailureCode
import org.audoiboo.tracker.plugin.flibusta.FlibustaPayloadKind
import org.audoiboo.tracker.plugin.flibusta.FlibustaResolveResult
import org.junit.Assert.*
import org.junit.Test

class FlibustaBookTtsFlowTest {
    @Test fun `resolved FB2 is imported and passed to Sherpa preparation`() {
        var preparedLanguage:String?=null; var preparedTitle:String?=null; var preparedQuality:TtsQuality?=null
        val flow=FlibustaBookTtsFlow(resolveFb2={success("uk")},prepareTts={document,speed,quality->preparedLanguage=document.language;preparedTitle=document.title;preparedQuality=quality;Result.success(prepared(document.language?:"uk",speed,quality))})
        val result=flow.prepare("https://flibusta.site/b/42",1.15f,TtsQuality.HIGH_QUALITY).getOrThrow()
        assertEquals("uk",preparedLanguage);assertEquals("Тестова книга",preparedTitle);assertEquals(TtsQuality.HIGH_QUALITY,preparedQuality);assertEquals("42.fb2",result.fileName);assertEquals(1.15f,result.tts.session.speed);assertEquals(TtsQuality.HIGH_QUALITY,result.tts.session.quality)
    }
    @Test fun `Flibusta FB2 can synthesize into player library items`()=runBlocking {
        val flow=FlibustaBookTtsFlow(resolveFb2={success("uk")},prepareTts={document,speed,quality->Result.success(prepared(document,speed,quality))})
        val p=flow.prepare("https://flibusta.site/b/42",1.05f).getOrThrow(); val root=Files.createTempDirectory("flibusta-tts-e2e").toFile()
        val provider=object:TtsProvider{override val id="sherpa-onnx";override val supportedLanguages=setOf("uk");override suspend fun getVoices(language:String)=listOf(p.tts.voice);override suspend fun synthesize(request:TtsSynthesisRequest):TtsSynthesisResult{val f=File(request.outputPath);Pcm16Wav.write(SherpaAudio(FloatArray(2400){.1f},24000),f);return TtsSynthesisResult(f.absolutePath,24000,100)}}
        val generated=TtsBookGenerator(TtsChapterGenerator(provider,File(root,"work"))).generate(p.document,p.tts.session,File(root,"out"));val items=TtsPlayerLibraryBridge.items(p.document,generated)
        assertEquals(TtsSessionState.COMPLETED,generated.session.state);assertTrue(generated.chapters.single().audioFile.isFile);assertEquals("Тестова книга",items.single().bookTitle);assertTrue(items.single().relativePath.startsWith("Audoiboo/TTS/"))
    }
    @Test fun `different sessions never share physical chapter output directory`(){val root=File("tts-output");val a=FlibustaBookTtsFlow.outputDirectory(root,"session-a");val b=FlibustaBookTtsFlow.outputDirectory(root,"session-b");assertNotEquals(a.path,b.path);assertEquals(File(root,TtsStableId.hex("session-a")).path,a.path)}
    @Test fun `resolver failure stops before TTS preparation`(){var called=false;val flow=FlibustaBookTtsFlow(resolveFb2={FlibustaResolveResult.Failure(FlibustaFailureCode.NOT_FOUND,"HTTP 404",404)},prepareTts={_,_,_->called=true;error("must not run")});val r=flow.prepare("https://flibusta.site/b/404");assertTrue(r.isFailure);assertTrue(!called)}
    @Test fun `invalid downloaded payload stops before TTS preparation`(){var called=false;val flow=FlibustaBookTtsFlow(resolveFb2={FlibustaResolveResult.Success("not an fb2 document".toByteArray(),"https://flibusta.one/b/7/fb2",FlibustaPayloadKind.RAW_FB2,"application/xml","7.fb2")},prepareTts={_,_,_->called=true;error("must not run")});val r=flow.prepare("https://flibusta.one/b/7");assertTrue(r.isFailure);assertTrue(!called)}

    private fun success(language:String)=FlibustaResolveResult.Success(validFb2(language),"https://flibusta.site/b/42/fb2",FlibustaPayloadKind.RAW_FB2,"application/xml","42.fb2")
    private fun prepared(language:String,speed:Float,quality:TtsQuality=TtsQuality.FAST):PreparedSherpaBookTts{val high=quality==TtsQuality.HIGH_QUALITY;val model=VoiceModelSpec("test-$language","v1",language,"1".repeat(64),if(high) SupertonicAndroidAdapter.DURATION_PREDICTOR else "model.int8.onnx");val voice=TtsVoice("voice-$language","Test voice",language,model.modelId,model.version,0);val session=TtsSession("session-1","sherpa-onnx",voice,"f".repeat(64),speed,quality,if(high)TtsEngineFamily.SUPERTONIC else TtsEngineFamily.PIPER_VITS);return PreparedSherpaBookTts(model,voice,session,1,quality,session.engineFamily)}
    private fun prepared(document:BookDocument,speed:Float,quality:TtsQuality=TtsQuality.FAST):PreparedSherpaBookTts{val language=document.language?:"uk";val model=VoiceModelSpec("test-$language","v1",language,"1".repeat(64),"model.int8.onnx");val voice=TtsVoice("voice-$language","Test voice",language,model.modelId,model.version,0);val plan=TtsSynthesisPlanner.build(document);val session=TtsSession("session-e2e","sherpa-onnx",voice,plan.documentFingerprint,speed,quality,TtsEngineFamily.PIPER_VITS);return PreparedSherpaBookTts(model,voice,session,plan.chunkCount,quality,TtsEngineFamily.PIPER_VITS)}
    private fun validFb2(language:String):ByteArray="""<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Тестова книга</book-title><lang>$language</lang><author><first-name>Іван</first-name><last-name>Автор</last-name></author></title-info></description><body><section><title><p>Перший розділ</p></title><p>Це текст для синтезу мовлення.</p></section></body></FictionBook>""".toByteArray()
}
