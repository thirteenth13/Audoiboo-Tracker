package org.audoiboo.tracker.plugin

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

const val DEFAULT_PLUGIN_CATALOG_URL = "https://raw.githubusercontent.com/thirteenth13/Audoiboo-Tracker/main/plugins/catalog.json"
private const val PLUGIN_CATALOG_FORMAT_VERSION = 1
private const val MAX_CATALOG_BYTES = 512L * 1024L
private const val MAX_UPDATE_REDIRECTS = 5

data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val version: Int,
    val apiVersion: Int,
    val packageUrl: String,
    val sha256: String,
    val description: String? = null
)

data class PluginUpdate(
    val entry: PluginCatalogEntry,
    val installedVersion: Int
)

sealed interface PluginUpdateCheckResult {
    data class Success(
        val updates: List<PluginUpdate>,
        val installable: List<PluginCatalogEntry> = emptyList(),
        val entries: List<PluginCatalogEntry> = emptyList()
    ) : PluginUpdateCheckResult

    data class Failed(val reason: String) : PluginUpdateCheckResult
}

fun interface PluginCatalogFetcher {
    fun fetch(url: String, maxBytes: Long): String
}

fun interface PluginCatalogDecoder {
    fun decode(json: String): List<PluginCatalogEntry>
}

fun interface PluginUpdateDownloader {
    fun download(url: String, target: File, maxBytes: Long): Long
}

object AndroidJsonPluginCatalogDecoder : PluginCatalogDecoder {
    override fun decode(json: String): List<PluginCatalogEntry> {
        val root = JSONObject(json)
        val formatVersion = root.optInt("formatVersion", 0)
        require(formatVersion == PLUGIN_CATALOG_FORMAT_VERSION) { "Unsupported plugin catalog format" }
        val array = root.optJSONArray("plugins") ?: error("Plugin catalog has no plugins array")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    PluginCatalogEntry(
                        id = item.getString("id"),
                        name = item.optString("name", item.getString("id")),
                        version = item.getInt("version"),
                        apiVersion = item.getInt("apiVersion"),
                        packageUrl = item.getString("url"),
                        sha256 = item.getString("sha256").lowercase(),
                        description = item.optString("description").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }
}

object HostPluginUpdateTransport : PluginCatalogFetcher, PluginUpdateDownloader {
    override fun fetch(url: String, maxBytes: Long): String {
        val bytes = request(url, maxBytes)
        return bytes.toString(Charsets.UTF_8)
    }

    override fun download(url: String, target: File, maxBytes: Long): Long {
        val bytes = request(url, maxBytes)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { it.write(bytes) }
        return bytes.size.toLong()
    }

    private fun request(url: String, maxBytes: Long): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        var current = validateHttpsUrl(url)
        repeat(MAX_UPDATE_REDIRECTS + 1) { redirectCount ->
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Audoiboo-Tracker/plugin-updater")
                setRequestProperty("Accept", "application/json, application/octet-stream, */*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount >= MAX_UPDATE_REDIRECTS) error("Too many update redirects")
                    val location = connection.getHeaderField("Location") ?: error("Redirect without Location")
                    current = validateHttpsUrl(URI(current).resolve(location).toString())
                    return@repeat
                }
                if (code !in 200..299) error("Update server returned HTTP $code")
                val declared = connection.contentLengthLong
                if (declared > maxBytes) error("Update response exceeds size limit")
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) error("Update response exceeds size limit")
                        output.write(buffer, 0, read)
                    }
                    return output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Unable to fetch update")
    }

    private fun validateHttpsUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "Plugin updates require HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Plugin update URL has no host" }
        require(uri.userInfo.isNullOrBlank()) { "Plugin update URL must not contain credentials" }
        return uri.toString()
    }
}

object PluginUpdatePolicy {
    private val sha256Regex = Regex("^[0-9a-f]{64}$")

    fun validateEntry(entry: PluginCatalogEntry): String? {
        if (entry.id.isBlank()) return "Catalog entry id is blank"
        if (entry.version <= 0) return "Catalog version must be positive"
        if (entry.apiVersion != SOURCE_PLUGIN_API_VERSION) return "Catalog entry uses unsupported plugin API"
        if (!sha256Regex.matches(entry.sha256)) return "Catalog SHA-256 is invalid"
        val uri = runCatching { URI(entry.packageUrl) }.getOrNull() ?: return "Catalog package URL is invalid"
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) {
            return "Catalog package URL must be HTTPS without credentials"
        }
        if (!PluginPackagePolicy.isPluginPackageName(uri.path.substringAfterLast('/'))) {
            return "Catalog package URL must point to .$PLUGIN_PACKAGE_EXTENSION"
        }
        return null
    }

    fun availableUpdates(
        entries: List<PluginCatalogEntry>,
        registrations: List<SourcePluginRegistration>
    ): List<PluginUpdate> {
        val installed = registrations
            .filter { it.origin == PluginOrigin.PACKAGE }
            .mapNotNull { registration -> registration.descriptor?.let { registration.packageId to it.version } }
            .toMap()
        return newestValidEntries(entries)
            .mapNotNull { entry ->
                val current = installed[entry.id] ?: return@mapNotNull null
                entry.takeIf { it.version > current }?.let { PluginUpdate(it, current) }
            }
            .sortedBy { it.entry.name.lowercase() }
    }

    fun availableInstalls(
        entries: List<PluginCatalogEntry>,
        registrations: List<SourcePluginRegistration>
    ): List<PluginCatalogEntry> {
        val reservedIds = registrations.mapTo(hashSetOf()) { it.packageId }
        return newestValidEntries(entries)
            .filterNot { it.id in reservedIds }
            .sortedBy { it.name.lowercase() }
    }

    private fun newestValidEntries(entries: List<PluginCatalogEntry>): List<PluginCatalogEntry> =
        entries
            .filter { validateEntry(it) == null }
            .groupBy { it.id }
            .mapNotNull { (_, versions) -> versions.maxByOrNull { it.version } }
}

class PluginUpdateService(
    private val catalogFetcher: PluginCatalogFetcher = HostPluginUpdateTransport,
    private val downloader: PluginUpdateDownloader = HostPluginUpdateTransport,
    private val catalogDecoder: PluginCatalogDecoder = AndroidJsonPluginCatalogDecoder
) {
    fun check(
        registrations: List<SourcePluginRegistration>,
        catalogUrl: String = DEFAULT_PLUGIN_CATALOG_URL
    ): PluginUpdateCheckResult = runCatching {
        val json = catalogFetcher.fetch(catalogUrl, MAX_CATALOG_BYTES)
        val entries = catalogDecoder.decode(json)
        PluginUpdateCheckResult.Success(
            updates = PluginUpdatePolicy.availableUpdates(entries, registrations),
            installable = PluginUpdatePolicy.availableInstalls(entries, registrations),
            entries = entries
        )
    }.getOrElse { PluginUpdateCheckResult.Failed(it.message ?: "Plugin update check failed") }

    fun downloadVerified(update: PluginUpdate, cacheDir: File): Result<File> =
        downloadVerified(update.entry, cacheDir)

    fun downloadVerified(entry: PluginCatalogEntry, cacheDir: File): Result<File> = runCatching {
        val validationError = PluginUpdatePolicy.validateEntry(entry)
        require(validationError == null) { validationError ?: "Invalid catalog entry" }
        val target = File(cacheDir, "plugin-catalog-${entry.id}-${entry.version}.abplugin")
        target.delete()
        downloader.download(entry.packageUrl, target, PluginArchiveLimits().maxCompressedBytes)
        require(target.isFile && target.length() > 0L) { "Downloaded plugin package is empty" }
        val digest = sha256(target)
        require(digest == entry.sha256) { "Plugin update checksum mismatch" }
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
