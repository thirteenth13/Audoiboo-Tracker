package org.audoiboo.tracker.tts

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.UUID
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Downloads, verifies and safely installs pinned Sherpa voice/runtime packages. */
class SherpaVoiceInstaller(
    private val modelManager: VoiceModelManager,
    private val openStream: (String) -> InputStream = ::openHttpStream,
) {
    fun ensureInstalled(pkg: SherpaVoicePackage): Result<VoiceModelSpec> =
        installedSpec(pkg)?.let { Result.success(it) } ?: install(pkg)

    fun installedSpec(pkg: SherpaVoicePackage): VoiceModelSpec? = runCatching {
        val dir = modelManager.modelDir(pkg.modelId, pkg.version)
        val manifestFile = File(dir, INSTALL_MANIFEST)
        if (!manifestFile.isFile || !hasRequiredRuntimeFiles(dir, pkg)) return null
        val manifest = Properties().apply { manifestFile.inputStream().buffered().use { load(it) } }
        if (manifest.getProperty("modelId") != pkg.modelId || manifest.getProperty("version") != pkg.version ||
            manifest.getProperty("archiveSha256")?.equals(pkg.archiveSha256, true) != true ||
            manifest.getProperty("modelFileName") != pkg.modelFileName ||
            manifest.getProperty("engineFamily", TtsEngineFamily.PIPER_VITS.name) != pkg.engineFamily.name) return null
        val modelSha = manifest.getProperty("modelSha256")?.takeIf { it.matches(SHA256) } ?: return null
        val spec = VoiceModelSpec(pkg.modelId, pkg.version, pkg.language, modelSha, pkg.modelFileName)
        modelManager.verify(spec).getOrThrow()
        spec
    }.getOrNull()

    fun install(pkg: SherpaVoicePackage): Result<VoiceModelSpec> = runCatching {
        val finalDir = modelManager.modelDir(pkg.modelId, pkg.version)
        val parent = requireNotNull(finalDir.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create voice model directory" }
        val archive = File(parent, ".${finalDir.name}.${UUID.randomUUID()}.tar.bz2.part")
        val staging = File(parent, ".${finalDir.name}.${UUID.randomUUID()}.staging")
        val backup = File(parent, ".${finalDir.name}.${UUID.randomUUID()}.backup")
        var movedExisting = false
        var publishedNew = false
        try {
            download(pkg, archive)
            require(VoiceModelManager.digest(archive).equals(pkg.archiveSha256, true)) { "Voice package checksum mismatch" }
            check(staging.mkdirs()) { "Cannot create voice model staging directory" }
            extract(pkg, archive, staging)
            require(hasRequiredRuntimeFiles(staging, pkg)) { "Voice package is missing required runtime files" }
            val spec = VoiceModelSpec(pkg.modelId, pkg.version, pkg.language, VoiceModelManager.digest(File(staging, pkg.modelFileName)), pkg.modelFileName)
            writeInstallManifest(staging, pkg, spec)
            if (finalDir.exists()) { check(finalDir.renameTo(backup)); movedExisting = true }
            try {
                check(staging.renameTo(finalDir)); publishedNew = true
                modelManager.verify(spec).getOrThrow(); checkNotNull(installedSpec(pkg))
                if (backup.exists()) check(backup.deleteRecursively()); movedExisting = false; spec
            } catch (failure: Throwable) {
                if (publishedNew && finalDir.exists()) finalDir.deleteRecursively()
                if (movedExisting && backup.exists()) { check(backup.renameTo(finalDir)); movedExisting = false }
                throw failure
            }
        } finally {
            archive.delete(); if (staging.exists()) staging.deleteRecursively(); if (!movedExisting && backup.exists()) backup.deleteRecursively()
        }
    }

    private fun hasRequiredRuntimeFiles(dir: File, pkg: SherpaVoicePackage): Boolean = when (pkg.engineFamily) {
        TtsEngineFamily.PIPER_VITS -> {
            val model = File(dir, pkg.modelFileName); val tokens = File(dir, "tokens.txt"); val espeak = File(dir, "espeak-ng-data")
            model.isFile && tokens.isFile && espeak.isDirectory && espeak.walkTopDown().any { it.isFile }
        }
        TtsEngineFamily.SUPERTONIC -> SUPERTONIC_RUNTIME_FILES.all { File(dir, it).isFile }
    }

    private fun writeInstallManifest(dir: File, pkg: SherpaVoicePackage, spec: VoiceModelSpec) {
        val properties = Properties().apply {
            setProperty("modelId", pkg.modelId); setProperty("version", pkg.version); setProperty("archiveSha256", pkg.archiveSha256.lowercase())
            setProperty("modelFileName", pkg.modelFileName); setProperty("modelSha256", spec.sha256.lowercase()); setProperty("engineFamily", pkg.engineFamily.name)
        }
        File(dir, INSTALL_MANIFEST).outputStream().buffered().use { properties.store(it, "Audoiboo verified Sherpa voice installation") }
    }

    private fun download(pkg: SherpaVoicePackage, destination: File) {
        var total = 0L
        openStream(pkg.archiveUrl).use { raw -> BufferedInputStream(raw).use { input -> BufferedOutputStream(FileOutputStream(destination)).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count == 0) continue; total += count; require(total <= pkg.archiveSizeBytes); output.write(buffer, 0, count) }
        } } }
        require(total == pkg.archiveSizeBytes) { "Voice package size mismatch: expected ${pkg.archiveSizeBytes}, got $total" }
    }

    private fun extract(pkg: SherpaVoicePackage, archive: File, destination: File) {
        var entries = 0; var totalBytes = 0L; val destinationRoot = destination.canonicalFile
        archive.inputStream().buffered().use { fileInput -> BZip2CompressorInputStream(fileInput, true).use { bzip -> TarArchiveInputStream(bzip).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break; entries++; require(entries <= MAX_ENTRIES); require(!entry.isSymbolicLink && !entry.isLink)
                val relative = safeRelativePath(entry.name) ?: continue; val output = File(destinationRoot, relative).canonicalFile
                require(output.path == destinationRoot.path || output.path.startsWith(destinationRoot.path + File.separator))
                if (entry.isDirectory) { check(output.mkdirs() || output.isDirectory); continue }
                require(entry.isFile); require(entry.size in 0..MAX_SINGLE_FILE_BYTES); totalBytes += entry.size; require(totalBytes <= MAX_EXTRACTED_BYTES)
                val parent = requireNotNull(output.parentFile); check(parent.mkdirs() || parent.isDirectory)
                output.outputStream().buffered().use { out -> var remaining = entry.size; val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (remaining > 0) { val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); require(count > 0); out.write(buffer, 0, count); remaining -= count } }
            }
        } } }
        require(entries > 0); require(File(destination, pkg.modelFileName).isFile)
    }

    internal fun safeRelativePath(rawName: String): String? {
        val normalized = rawName.replace('\\', '/').trimStart('/'); require(!rawName.startsWith('/') && !rawName.startsWith('\\'))
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }; require(parts.none { it == ".." }); if (parts.isEmpty()) return null
        val first = parts.first(); val known = first.startsWith("vits-piper-") || first.startsWith("sherpa-onnx-supertonic-"); val stripped = if (known && parts.size > 1) parts.drop(1) else parts
        return stripped.takeIf { it.isNotEmpty() }?.joinToString(File.separator)
    }

    companion object {
        private const val INSTALL_MANIFEST = ".audoiboo-model.properties"
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        internal val SUPERTONIC_RUNTIME_FILES = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")
        private const val MAX_ENTRIES = 512; private const val MAX_SINGLE_FILE_BYTES = 128L * 1024 * 1024; private const val MAX_EXTRACTED_BYTES = 256L * 1024 * 1024
        private fun openHttpStream(url: String): InputStream {
            val connection = URL(url).openConnection() as HttpURLConnection; connection.connectTimeout = 20_000; connection.readTimeout = 60_000; connection.instanceFollowRedirects = true; connection.setRequestProperty("User-Agent", "Audoiboo-Tracker")
            val status = connection.responseCode; require(status in 200..299) { "Voice package download failed with HTTP $status" }
            return object : InputStream() { private val delegate = connection.inputStream; override fun read() = delegate.read(); override fun read(b: ByteArray, off: Int, len: Int) = delegate.read(b, off, len); override fun close() { runCatching { delegate.close() }; connection.disconnect() } }
        }
    }
}
