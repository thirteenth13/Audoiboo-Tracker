package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Room v6 store for speeds, broken-track state and per-series resume. */
internal object PlayerStateStore {
    private const val PREFS = "player_extras"
    private const val MIGRATION_PREFS = "room_migration"
    private const val MIGRATION_KEY = "player_state_v6"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bookSpeedsState = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val seriesSpeedsState = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val brokenState = MutableStateFlow<Set<String>>(emptySet())
    private val seriesResumeState = MutableStateFlow<Map<String, SeriesResumeEntity>>(emptyMap())
    @Volatile private var initialized = false
    @Volatile private var ready = false

    fun isReady(): Boolean = ready
    fun observeBookSpeeds(): StateFlow<Map<String, Float>> = bookSpeedsState.asStateFlow()
    fun observeSeriesSpeeds(): StateFlow<Map<String, Float>> = seriesSpeedsState.asStateFlow()
    fun observeBroken(): StateFlow<Set<String>> = brokenState.asStateFlow()
    fun observeSeriesResume(): StateFlow<Map<String, SeriesResumeEntity>> = seriesResumeState.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            migrateLegacyIfNeeded(app)
            refresh(app)
            ready = true
            val dao = AudoibooDatabase.get(app).libraryDao()
            launch { dao.observeBookSpeeds().collect { rows -> bookSpeedsState.value = rows.associate { it.dir to it.speed } } }
            launch { dao.observeSeriesSpeeds().collect { rows -> seriesSpeedsState.value = rows.associate { it.series to it.speed } } }
            launch { dao.observeBrokenTracks().collect { rows -> brokenState.value = rows.mapTo(linkedSetOf()) { it.uri } } }
            launch { dao.observeSeriesResume().collect { rows -> seriesResumeState.value = rows.associateBy { it.series } } }
        }
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        bookSpeedsState.value = dao.bookSpeeds().associate { it.dir to it.speed }
        seriesSpeedsState.value = dao.seriesSpeeds().associate { it.series to it.speed }
        brokenState.value = dao.brokenTracks().mapTo(linkedSetOf()) { it.uri }
        seriesResumeState.value = dao.seriesResumeEntries().associateBy { it.series }
    }

    fun bookSpeed(context: Context, dir: String): Float? {
        initialize(context)
        return if (ready) bookSpeedsState.value[dir] else legacyBookSpeeds(context)[dir]
    }

    fun seriesSpeed(context: Context, series: String): Float? {
        initialize(context)
        return if (ready) seriesSpeedsState.value[series] else legacySeriesSpeeds(context)[series]
    }

    fun setBookSpeed(context: Context, dir: String, speed: Float) {
        if (dir.isBlank()) return
        initialize(context)
        val value = speed.coerceIn(.5f, 3f)
        bookSpeedsState.value = bookSpeedsState.value + (dir to value)
        scope.launch { AudoibooDatabase.get(context.applicationContext).libraryDao().upsertBookSpeed(PlayerBookSpeedEntity(dir, value)) }
    }

    fun clearBookSpeed(context: Context, dir: String) {
        initialize(context)
        bookSpeedsState.value = bookSpeedsState.value - dir
        scope.launch { AudoibooDatabase.get(context.applicationContext).libraryDao().deleteBookSpeed(dir) }
    }

    fun setSeriesSpeed(context: Context, series: String, speed: Float) {
        if (series.isBlank()) return
        initialize(context)
        val value = speed.coerceIn(.5f, 3f)
        seriesSpeedsState.value = seriesSpeedsState.value + (series to value)
        scope.launch { AudoibooDatabase.get(context.applicationContext).libraryDao().upsertSeriesSpeed(PlayerSeriesSpeedEntity(series, value)) }
    }

    fun brokenUris(context: Context): Set<String> {
        initialize(context)
        return if (ready) brokenState.value else legacyBroken(context)
    }

    fun markBroken(context: Context, uri: String) {
        if (uri.isBlank()) return
        initialize(context)
        brokenState.value = brokenState.value + uri
        scope.launch { AudoibooDatabase.get(context.applicationContext).libraryDao().upsertBrokenTrack(BrokenTrackEntity(uri)) }
    }

    fun clearBroken(context: Context) {
        initialize(context)
        brokenState.value = emptySet()
        scope.launch { AudoibooDatabase.get(context.applicationContext).libraryDao().clearBrokenTracks() }
    }

    fun seriesResume(context: Context, series: String): SeriesResumeEntity? {
        initialize(context)
        return if (ready) seriesResumeState.value[series] else legacySeriesResume(context)[series]
    }

    fun saveSeriesResume(context: Context, value: SeriesResumeEntity) {
        if (value.series.isBlank() || value.dir.isBlank()) return
        initialize(context)
        seriesResumeState.value = seriesResumeState.value + (value.series to value)
        scope.launch { AudoibooDatabase.get(context.applicationContext).libraryDao().upsertSeriesResume(value) }
    }

    suspend fun exportJson(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        JSONObject()
            .put("bookSpeeds", JSONObject().apply { dao.bookSpeeds().forEach { put(it.dir, it.speed.toDouble()) } })
            .put("seriesSpeeds", JSONObject().apply { dao.seriesSpeeds().forEach { put(it.series, it.speed.toDouble()) } })
            .put("brokenUris", JSONArray().apply { dao.brokenTracks().forEach { put(it.uri) } })
            .put("seriesResume", JSONObject().apply {
                dao.seriesResumeEntries().forEach { row ->
                    put(row.series, JSONObject().put("dir", row.dir).put("title", row.title).put("uri", row.uri).put("at", row.at))
                }
            })
    }

    suspend fun restoreJson(context: Context, json: JSONObject?) = withContext(Dispatchers.IO) {
        if (json == null) return@withContext
        val app = context.applicationContext
        val dao = AudoibooDatabase.get(app).libraryDao()
        val db = AudoibooDatabase.get(app).openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM player_book_speeds")
            db.execSQL("DELETE FROM player_series_speeds")
            db.execSQL("DELETE FROM broken_tracks")
            db.execSQL("DELETE FROM series_resume")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        parseSpeeds(json.optJSONObject("bookSpeeds")).forEach { (key, value) -> dao.upsertBookSpeed(PlayerBookSpeedEntity(key, value)) }
        parseSpeeds(json.optJSONObject("seriesSpeeds")).forEach { (key, value) -> dao.upsertSeriesSpeed(PlayerSeriesSpeedEntity(key, value)) }
        parseBroken(json.optJSONArray("brokenUris")).forEach { dao.upsertBrokenTrack(BrokenTrackEntity(it)) }
        parseSeriesResume(json.optJSONObject("seriesResume")).values.forEach { dao.upsertSeriesResume(it) }
        context.applicationContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_KEY, true).apply()
        refresh(app)
    }

    suspend fun restoreFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        importLegacy(app, overwrite = true)
        app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_KEY, true).apply()
        refresh(app)
    }

    private suspend fun migrateLegacyIfNeeded(context: Context) {
        val flags = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(MIGRATION_KEY, false)) return
        importLegacy(context, overwrite = false)
        flags.edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    private suspend fun importLegacy(context: Context, overwrite: Boolean) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val book = legacyBookSpeeds(context)
        val series = legacySeriesSpeeds(context)
        val broken = legacyBroken(context)
        val resumes = legacySeriesResume(context)
        val existingBook = dao.bookSpeeds().associateBy { it.dir }
        val existingSeries = dao.seriesSpeeds().associateBy { it.series }
        val existingBroken = dao.brokenTracks().associateBy { it.uri }
        val existingResume = dao.seriesResumeEntries().associateBy { it.series }
        book.forEach { (key, value) -> if (overwrite || key !in existingBook) dao.upsertBookSpeed(PlayerBookSpeedEntity(key, value)) }
        series.forEach { (key, value) -> if (overwrite || key !in existingSeries) dao.upsertSeriesSpeed(PlayerSeriesSpeedEntity(key, value)) }
        broken.forEach { uri -> if (overwrite || uri !in existingBroken) dao.upsertBrokenTrack(BrokenTrackEntity(uri)) }
        resumes.forEach { (key, value) -> if (overwrite || key !in existingResume) dao.upsertSeriesResume(value) }
    }

    private fun legacyBookSpeeds(context: Context): Map<String, Float> = parseSpeeds(
        runCatching { JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("book_speeds", "{}")) }.getOrNull()
    )

    private fun legacySeriesSpeeds(context: Context): Map<String, Float> = parseSpeeds(
        runCatching { JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("series_speeds", "{}")) }.getOrNull()
    )

    private fun legacyBroken(context: Context): Set<String> = parseBroken(
        runCatching { JSONArray(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("broken_uris", "[]")) }.getOrNull()
    ).toSet()

    private fun legacySeriesResume(context: Context): Map<String, SeriesResumeEntity> = parseSeriesResume(
        runCatching { JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("series_resume", "{}")) }.getOrNull()
    )

    private fun parseSpeeds(obj: JSONObject?): Map<String, Float> {
        if (obj == null) return emptyMap()
        return obj.keys().asSequence().mapNotNull { key ->
            key.takeIf(String::isNotBlank)?.let { it to obj.optDouble(key, 1.0).toFloat().coerceIn(.5f, 3f) }
        }.toMap()
    }

    private fun parseBroken(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.distinct()
    }

    private fun parseSeriesResume(obj: JSONObject?): Map<String, SeriesResumeEntity> {
        if (obj == null) return emptyMap()
        return obj.keys().asSequence().mapNotNull { series ->
            val o = obj.optJSONObject(series) ?: return@mapNotNull null
            val dir = o.optString("dir").takeIf(String::isNotBlank) ?: return@mapNotNull null
            series.takeIf(String::isNotBlank)?.let {
                it to SeriesResumeEntity(it, dir, o.optString("title", dir), o.optString("uri"), o.optLong("at", 0L))
            }
        }.toMap()
    }
}
