package org.audoiboo.tracker.plugin

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Entity(
    tableName = "author_alias_cache",
    primaryKeys = ["aliasNorm"],
    indices = [Index("canonicalAuthor")]
)
data class AuthorAliasEntity(
    val aliasNorm: String,
    val canonicalAuthor: String,
    val source: String,
    val confidence: Float,
    val found: Boolean,
    val updatedAt: Long
)

@Dao
interface AuthorAliasDao {
    @Query("SELECT * FROM author_alias_cache WHERE aliasNorm=:aliasNorm LIMIT 1")
    suspend fun get(aliasNorm: String): AuthorAliasEntity?

    @Query("SELECT * FROM author_alias_cache WHERE canonicalAuthor=:canonicalAuthor AND found=1")
    suspend fun aliasesOf(canonicalAuthor: String): List<AuthorAliasEntity>

    @Upsert
    suspend fun upsert(value: AuthorAliasEntity)

    @Upsert
    suspend fun upsertAll(values: List<AuthorAliasEntity>)

    @Query("DELETE FROM author_alias_cache WHERE updatedAt < :threshold")
    suspend fun purgeOlderThan(threshold: Long)
}

class AuthorAliasCache(
    private val dao: AuthorAliasDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000

        fun sourceRank(source: String): Int = when (source.lowercase()) {
            "fantlab" -> 3
            "flibusta" -> 2
            "heuristic" -> 1
            else -> 0
        }
    }

    data class Hit(
        val canonicalAuthor: String,
        val source: String,
        val confidence: Float,
        val found: Boolean
    )

    suspend fun lookup(aliasNorm: String): Hit? {
        val value = dao.get(aliasNorm) ?: return null
        val ttl = if (value.found) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
        if (now() - value.updatedAt > ttl) return null
        return Hit(value.canonicalAuthor, value.source, value.confidence, value.found)
    }

    suspend fun storePositive(aliasNorm: String, canonicalAuthor: String, source: String, confidence: Float) {
        if (aliasNorm.isBlank() || canonicalAuthor.isBlank()) return
        val existing = dao.get(aliasNorm)
        if (existing != null && existing.found) {
            val existingRank = sourceRank(existing.source)
            val incomingRank = sourceRank(source)
            if (existingRank > incomingRank || (existingRank == incomingRank && existing.confidence > confidence)) return
        }
        dao.upsert(
            AuthorAliasEntity(
                aliasNorm = aliasNorm,
                canonicalAuthor = canonicalAuthor,
                source = source,
                confidence = confidence.coerceIn(0f, 1f),
                found = true,
                updatedAt = now()
            )
        )
    }

    suspend fun storeNegative(aliasNorm: String, source: String) {
        if (aliasNorm.isBlank()) return
        val existing = dao.get(aliasNorm)
        if (existing?.found == true) return
        dao.upsert(AuthorAliasEntity(aliasNorm, "", source, 0f, false, now()))
    }

    suspend fun aliasesOf(canonicalAuthor: String): List<String> =
        dao.aliasesOf(canonicalAuthor).filter { it.found }.map { it.aliasNorm }
}

data class FlibustaAuthorInfo(
    val realName: String?,
    val pseudonyms: List<String>,
    val alternativeNames: List<String>
) {
    fun allNames(): List<String> = (listOfNotNull(realName) + pseudonyms + alternativeNames)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
}

/** Best-effort author enrichment. Failure or mirror downtime never fails series refresh. */
class FlibustaAuthorClient(
    private val baseUrls: List<String> = listOf("https://flibusta.is"),
    private val maxBytes: Long = 512L * 1024
) {
    suspend fun lookupAuthor(query: String): FlibustaAuthorInfo? = runCatching {
        val clean = query.trim()
        if (clean.isBlank()) return null
        for (base in baseUrls) {
            val root = base.trimEnd('/')
            val search = get("$root/booksearch?ask=${encode(clean)}&cha=on") ?: continue
            val authorId = Regex("""/a/(\\d+)""").find(search)?.groupValues?.getOrNull(1) ?: continue
            get("$root/a/$authorId")?.let(::parseAuthorPage)?.takeIf { it.allNames().isNotEmpty() }?.let { return it }
            get("$root/opds/author/$authorId")?.let(::parseOpds)?.takeIf { it.allNames().isNotEmpty() }?.let { return it }
        }
        null
    }.getOrNull()

    internal fun parseAuthorPage(html: String): FlibustaAuthorInfo? {
        val text = htmlToText(html)
        val heading = Regex("""(?is)<h1[^>]*>(.*?)</h1>""").find(html)?.groupValues?.getOrNull(1)
            ?.let(::htmlToText)?.trim()?.takeIf(String::isNotBlank)
        val realName = Regex("""(?i)(?:настоящее\\s+имя|реальное\\s+имя)\\s*[:—-]\\s*([^\\n|;]+)""")
            .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        val pseudoRaw = Regex("""(?i)(?:псевдоним(?:ы)?|псевдонім(?:и)?)\\s*[:—-]\\s*([^\\n|]+)""")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val pseudos = pseudoRaw.split(',', ';').map(String::trim).filter(String::isNotBlank)
        val canonical = realName ?: heading
        if (canonical.isNullOrBlank() && pseudos.isEmpty()) return null
        val alternatives = listOfNotNull(heading).filterNot { it.equals(canonical, ignoreCase = true) }
        return FlibustaAuthorInfo(canonical, pseudos, alternatives)
    }

    internal fun parseOpds(xml: String): FlibustaAuthorInfo? {
        val name = Regex("""(?is)<author>.*?<name>(.*?)</name>.*?</author>""").find(xml)
            ?.groupValues?.getOrNull(1)?.let(::htmlDecode)?.trim()
            ?: Regex("""(?is)<title>(.*?)</title>""").find(xml)?.groupValues?.getOrNull(1)?.let(::htmlDecode)?.trim()
        return name?.takeIf(String::isNotBlank)?.let { FlibustaAuthorInfo(it, emptyList(), emptyList()) }
    }

    private suspend fun get(url: String): String? {
        val response = HostPluginHttpTransport.get(
            PluginHttpRequest(url, mapOf("Accept" to "text/html, application/atom+xml, application/xml")),
            maxBytes
        )
        return response.body.takeIf { response.statusCode in 200..299 }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun htmlToText(value: String): String = htmlDecode(
        value.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n+"), "\n").trim()

    private fun htmlDecode(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
}

data class ResolvedAuthor(
    val canonical: String,
    val allNames: Set<String>,
    val source: String,
    val confidence: Float
)

class AuthorAliasResolver(
    private val cache: AuthorAliasCache,
    private val flibustaLookup: suspend (String) -> FlibustaAuthorInfo?,
    private val fantlabAliasesOf: suspend (String) -> List<String>
) {
    suspend fun expandForMatching(rawAuthors: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (raw in rawAuthors) {
            val normalized = SourceIdentityMatcher.normalizeAuthor(raw)
            if (normalized.isBlank()) continue
            out += normalized
            resolve(raw)?.allNames?.forEach { name ->
                SourceIdentityMatcher.normalizeAuthor(name).takeIf(String::isNotBlank)?.let(out::add)
            }
        }
        return out.toList()
    }

    suspend fun resolve(rawAuthor: String): ResolvedAuthor? {
        val aliasNorm = SourceIdentityMatcher.normalizeAuthor(rawAuthor)
        if (aliasNorm.isBlank()) return null

        cache.lookup(aliasNorm)?.let { hit ->
            if (!hit.found) return null
            val names = linkedSetOf(hit.canonicalAuthor)
            names += cache.aliasesOf(hit.canonicalAuthor)
            names += rawAuthor
            return ResolvedAuthor(hit.canonicalAuthor, names, hit.source, hit.confidence)
        }

        val fantlabNames = runCatching { fantlabAliasesOf(rawAuthor) }.getOrDefault(emptyList())
            .map(String::trim).filter(String::isNotBlank).distinct()
        if (fantlabNames.isNotEmpty()) {
            val canonical = fantlabNames.first()
            fantlabNames.forEachIndexed { index, name ->
                cache.storePositive(
                    SourceIdentityMatcher.normalizeAuthor(name),
                    canonical,
                    "fantlab",
                    if (index == 0) 1f else 0.90f
                )
            }
            cache.storePositive(aliasNorm, canonical, "fantlab", 0.90f)
            return ResolvedAuthor(canonical, (fantlabNames + rawAuthor).toSet(), "fantlab", 1f)
        }

        val flibusta = runCatching { flibustaLookup(rawAuthor) }.getOrNull()
        if (flibusta != null && flibusta.allNames().isNotEmpty()) {
            val canonical = flibusta.realName ?: flibusta.allNames().first()
            flibusta.allNames().forEach { name ->
                cache.storePositive(SourceIdentityMatcher.normalizeAuthor(name), canonical, "flibusta", 0.75f)
            }
            cache.storePositive(aliasNorm, canonical, "flibusta", 0.75f)
            return ResolvedAuthor(canonical, (flibusta.allNames() + rawAuthor).toSet(), "flibusta", 0.75f)
        }

        cache.storeNegative(aliasNorm, "flibusta")
        return null
    }

    companion object {
        fun forContext(context: Context): AuthorAliasResolver {
            val cache = AuthorAliasCache(SourceMetadataDatabase.get(context).authorAliasDao())
            val flibusta = FlibustaAuthorClient()
            return AuthorAliasResolver(
                cache = cache,
                flibustaLookup = flibusta::lookupAuthor,
                fantlabAliasesOf = { query ->
                    FantLabCatalogPlugin.searchAuthors(query, 1).firstOrNull()?.let { author ->
                        listOf(author.name) + author.alternativeNames
                    }.orEmpty()
                }
            )
        }
    }
}
