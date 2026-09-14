package org.audoiboo.tracker.tts

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.UUID
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Downloads, verifies and safely installs pinned Sherpa voice/runtime packages. */
class SherpaVoiceInstaller(private val modelManager: VoiceModelManager, private val openStream:(String)->InputStream=::openHttpStream) {
 fun ensureInstalled(pkg:SherpaVoicePackage):Result<VoiceModelSpec> = installedSpec(pkg)?.let{Result.success(it)}?:install(pkg)
 fun installedSpec(pkg:SherpaVoicePackage):VoiceModelSpec?=runCatching{
  val dir=modelManager.modelDir(pkg.modelId,pkg.version);val mf=File(dir,INSTALL_MANIFEST);if(!mf.isFile||!hasRequiredRuntimeFiles(dir,pkg))return null
  val m=Properties().apply{mf.inputStream().buffered().use(::load)}
  if(m.getProperty("modelId")!=pkg.modelId||m.getProperty("version")!=pkg.version||m.getProperty("archiveSha256")?.equals(pkg.archiveSha256,true)!=true||m.getProperty("modelFileName")!=pkg.modelFileName||m.getProperty("engineFamily",TtsEngineFamily.PIPER_VITS.name)!=pkg.engineFamily.name)return null
  val modelSha=m.getProperty("modelSha256")?.takeIf{it.matches(SHA256)}?:return null
  if(pkg.engineFamily==TtsEngineFamily.SUPERTONIC){for(name in SUPERTONIC_RUNTIME_FILES){val expected=m.getProperty(runtimeHashKey(name))?.takeIf{it.matches(SHA256)}?:return null;if(!VoiceModelManager.digest(File(dir,name)).equals(expected,true))return null}}
  val spec=VoiceModelSpec(pkg.modelId,pkg.version,pkg.language,modelSha,pkg.modelFileName);modelManager.verify(spec).getOrThrow();spec
 }.getOrNull()
 fun install(pkg:SherpaVoicePackage):Result<VoiceModelSpec>=runCatching{
  val finalDir=modelManager.modelDir(pkg.modelId,pkg.version);val parent=requireNotNull(finalDir.parentFile);check(parent.mkdirs()||parent.isDirectory)
  val archive=File(parent,".${finalDir.name}.${UUID.randomUUID()}.tar.bz2.part");val staging=File(parent,".${finalDir.name}.${UUID.randomUUID()}.staging");val backup=File(parent,".${finalDir.name}.${UUID.randomUUID()}.backup");var moved=false;var published=false
  try{download(pkg,archive);require(VoiceModelManager.digest(archive).equals(pkg.archiveSha256,true)){"Voice package checksum mismatch"};check(staging.mkdirs());extract(pkg,archive,staging);require(hasRequiredRuntimeFiles(staging,pkg)){"Voice package is missing required runtime files"}
   val spec=VoiceModelSpec(pkg.modelId,pkg.version,pkg.language,VoiceModelManager.digest(File(staging,pkg.modelFileName)),pkg.modelFileName);writeManifest(staging,pkg,spec)
   if(finalDir.exists()){check(finalDir.renameTo(backup));moved=true};try{check(staging.renameTo(finalDir));published=true;modelManager.verify(spec).getOrThrow();checkNotNull(installedSpec(pkg));if(backup.exists())check(backup.deleteRecursively());moved=false;spec}catch(e:Throwable){if(published&&finalDir.exists())finalDir.deleteRecursively();if(moved&&backup.exists()){check(backup.renameTo(finalDir));moved=false};throw e}
  }finally{archive.delete();if(staging.exists())staging.deleteRecursively();if(!moved&&backup.exists())backup.deleteRecursively()}
 }
 private fun hasRequiredRuntimeFiles(dir:File,pkg:SherpaVoicePackage)=when(pkg.engineFamily){TtsEngineFamily.PIPER_VITS->{val model=File(dir,pkg.modelFileName);val tokens=File(dir,"tokens.txt");val espeak=File(dir,"espeak-ng-data");model.isFile&&model.length()>0&&tokens.isFile&&tokens.length()>0&&espeak.isDirectory&&espeak.walkTopDown().any{it.isFile&&it.length()>0}};TtsEngineFamily.SUPERTONIC->SUPERTONIC_RUNTIME_FILES.all{File(dir,it).let{f->f.isFile&&f.length()>0}}}
 private fun writeManifest(dir:File,pkg:SherpaVoicePackage,spec:VoiceModelSpec){val p=Properties().apply{setProperty("modelId",pkg.modelId);setProperty("version",pkg.version);setProperty("archiveSha256",pkg.archiveSha256.lowercase());setProperty("modelFileName",pkg.modelFileName);setProperty("modelSha256",spec.sha256.lowercase());setProperty("engineFamily",pkg.engineFamily.name);if(pkg.engineFamily==TtsEngineFamily.SUPERTONIC)SUPERTONIC_RUNTIME_FILES.forEach{name->setProperty(runtimeHashKey(name),VoiceModelManager.digest(File(dir,name)).lowercase())}};File(dir,INSTALL_MANIFEST).outputStream().buffered().use{p.store(it,"Audoiboo verified Sherpa voice installation")}}
 private fun download(pkg:SherpaVoicePackage,d:File){var total=0L;openStream(pkg.archiveUrl).buffered().use{i->BufferedOutputStream(FileOutputStream(d)).use{o->val b=ByteArray(DEFAULT_BUFFER_SIZE);while(true){val n=i.read(b);if(n<0)break;if(n==0)continue;total+=n;require(total<=pkg.archiveSizeBytes);o.write(b,0,n)}}};require(total==pkg.archiveSizeBytes){"Voice package size mismatch: expected ${pkg.archiveSizeBytes}, got $total"}}
 private fun extract(pkg:SherpaVoicePackage,a:File,d:File){var entries=0;var total=0L;val root=d.canonicalFile;a.inputStream().buffered().use{fi->BZip2CompressorInputStream(fi,true).use{bz->TarArchiveInputStream(bz).use{tar->while(true){val e=tar.nextEntry?:break;entries++;require(entries<=MAX_ENTRIES);require(!e.isSymbolicLink&&!e.isLink);val rel=safeRelativePath(e.name)?:continue;val out=File(root,rel).canonicalFile;require(out.path==root.path||out.path.startsWith(root.path+File.separator));if(e.isDirectory){check(out.mkdirs()||out.isDirectory);continue};require(e.isFile);require(e.size in 0..MAX_SINGLE_FILE_BYTES);total+=e.size;require(total<=MAX_EXTRACTED_BYTES);val par=requireNotNull(out.parentFile);check(par.mkdirs()||par.isDirectory);out.outputStream().buffered().use{o->var left=e.size;val b=ByteArray(DEFAULT_BUFFER_SIZE);while(left>0){val n=tar.read(b,0,minOf(b.size.toLong(),left).toInt());require(n>0);o.write(b,0,n);left-=n}}}}}}};require(entries>0);require(File(d,pkg.modelFileName).isFile)}
 internal fun safeRelativePath(raw:String):String?{val n=raw.replace('\\','/').trimStart('/');require(!raw.startsWith('/')&&!raw.startsWith('\\'));val parts=n.split('/').filter{it.isNotBlank()&&it!="."};require(parts.none{it==".."});if(parts.isEmpty())return null;val first=parts.first();val known=first.startsWith("vits-piper-")||first.startsWith("sherpa-onnx-supertonic-");val s=if(known&&parts.size>1)parts.drop(1)else parts;return s.takeIf{it.isNotEmpty()}?.joinToString(File.separator)}
 companion object{private const val INSTALL_MANIFEST=".audoiboo-model.properties";private val SHA256=Regex("[0-9a-fA-F]{64}");internal val SUPERTONIC_RUNTIME_FILES=listOf("duration_predictor.int8.onnx","text_encoder.int8.onnx","vector_estimator.int8.onnx","vocoder.int8.onnx","tts.json","unicode_indexer.bin","voice.bin");private fun runtimeHashKey(name:String)="runtimeSha256.${name.replace('.','_')}";private const val MAX_ENTRIES=512;private const val MAX_SINGLE_FILE_BYTES=128L*1024*1024;private const val MAX_EXTRACTED_BYTES=256L*1024*1024
  private fun openHttpStream(url:String):InputStream{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=20_000;c.readTimeout=60_000;c.instanceFollowRedirects=true;c.setRequestProperty("User-Agent","Audoiboo-Tracker");val s=c.responseCode;require(s in 200..299);return object:InputStream(){private val d=c.inputStream;override fun read()=d.read();override fun read(b:ByteArray,o:Int,l:Int)=d.read(b,o,l);override fun close(){runCatching{d.close()};c.disconnect()}}}
 }
}
