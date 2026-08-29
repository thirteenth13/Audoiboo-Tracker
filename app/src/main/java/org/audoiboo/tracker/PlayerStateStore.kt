package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
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
        return bookSpeedsState.value[dir]
    }

    fun seriesSpeed(context: Context, series: String): Float? {
        initialize(context)
        return seriesSpeedsState.value[series]
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
        return brokenState.value
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
        return seriesResumeState.value[series]
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
        val bookSpeeds = parseSpeeds(requiredObject(json, "bookSpeeds"))
        val seriesSpeeds = parseSpeeds(requiredObject(json, "seriesSpeeds"))
        val brokenUris = parseBroken(requiredArray(json, "brokenUris"))
        val seriesResume = parseSeriesResume(requiredObject(json, "seriesResume"))

        val app = context.applicationContext
        val room = AudoibooDatabase.get(app)
        val dao = room.libraryDao()
        room.withTransaction {
            val db = room.openHelper.writableDatabase
            db.execSQL("DELETE FROM player_book_speeds")
            db.execSQL("DELETE FROM player_series_speeds")
            db.execSQL("DELETE FROM broken_tracks")
            db.execSQL("DELETE FROM series_resume")
            bookSpeeds.forEach { (key, value) -> dao.upsertBookSpeed(PlayerBookSpeedEntity(key, value)) }
            seriesSpeeds.forEach { (key, value) -> dao.upsertSeriesSpeed(PlayerSeriesSpeedEntity(key, value)) }
            brokenUris.forEach { dao.upsertBrokenTrack(BrokenTrackEntity(it)) }
            seriesResume.values.forEach { dao.upsertSeriesResume(it) }
        }
        refresh(app)
    }

    private fun requiredObject(root: JSONObject, key: String): JSONObject? {
        if (!root.has(key) || root.isNull(key)) return null
        return root.opt(key) as? JSONObject
            ?: throw IllegalArgumentException("Backup player state $key is invalid")
    }

    private fun requiredArray(root: JSONObject, key: String): JSONArray? {
        if (!root.has(key) || root.isNull(key)) return null
        return root.opt(key) as? JSONArray
            ?: throw IllegalArgumentException("Backup player state $key is invalid")
    }

    private fun parseSpeeds(obj: JSONObject?): Map<String, Float> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Float>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(PlayerStateValuePolicy.validKey(key)) { "Backup player speed key is invalid" }
            val speed = PlayerStateValuePolicy.speed(obj.opt(key))
                ?: throw IllegalArgumentException("Backup player speed is invalid for $key")
            out[key] = speed
        }
        return out
    }

    private fun parseBroken(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until a.length()) {
            val uri = PlayerStateValuePolicy.text(a.opt(i), allowBlank = false)
                ?: throw IllegalArgumentException("Backup broken track URI is invalid")
            out += uri
        }
        return out.toList()
    }

    private fun parseSeriesResume(obj: JSONObject?): Map<String, SeriesResumeEntity> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, SeriesResumeEntity>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val series = keys.next()
            require(PlayerStateValuePolicy.validKey(series)) { "Backup series resume key is invalid" }
            val value = obj.opt(series)
            val o = value as? JSONObject
                ?: throw IllegalArgumentException("Backup series resume entry is invalid for $series")
            val dir = PlayerStateValuePolicy.text(o.opt("dir"), allowBlank = false)
                ?: throw IllegalArgumentException("Backup series resume dir is invalid for $series")
            val title = if (!o.has("title") || o.isNull("title")) dir else
                PlayerStateValuePolicy.text(o.opt("title"))
                    ?: throw IllegalArgumentException("Backup series resume title is invalid for $series")
            val uri = if (!o.has("uri") || o.isNull("uri")) "" else
                PlayerStateValuePolicy.text(o.opt("uri"))
                    ?: throw IllegalArgumentException("Backup series resume URI is invalid for $series")
            val at = if (!o.has("at") || o.isNull("at")) 0L else
                PlayerStateValuePolicy.timestamp(o.opt("at"))
                    ?: throw IllegalArgumentException("Backup series resume timestamp is invalid for $series")
            out[series] = SeriesResumeEntity(series, dir, title, uri, at)
        }
        return out
    }
}
