package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Room-native tag cache for synchronous player-library reads. */
internal object PlayerTagStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    @Volatile private var initialized = false
    @Volatile private var ready = false

    fun observe(): StateFlow<Map<String, List<String>>> = state.asStateFlow()
    fun isReady(): Boolean = ready

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            RoomTagSync.syncFromLegacy(app)
            refresh(app)
            ready = true
        }
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val dirs = PlayerLibrary.all(app).map { RoomTagSync.normalizeDir(it.relativePath) }.distinct()
        state.value = RoomTagSync.tagsForDirs(app, dirs)
            .mapKeys { (dir, _) -> RoomTagSync.normalizeDir(dir) }
    }

    fun tags(context: Context, dir: String): List<String> {
        initialize(context)
        val key = RoomTagSync.normalizeDir(dir)
        return if (ready) state.value[key].orEmpty() else legacyTags(context, key)
    }

    fun setTags(context: Context, dir: String, tags: List<String>) {
        initialize(context)
        val key = RoomTagSync.normalizeDir(dir)
        val clean = tags.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase)
        state.value = state.value + (key to clean)
        val app = context.applicationContext
        scope.launch {
            if (!RoomTagSync.setTagsForDir(app, key, clean)) {
                refresh(app)
            }
        }
    }

    fun setCached(dir: String, tags: List<String>) {
        val key = RoomTagSync.normalizeDir(dir)
        state.value = state.value + (key to tags.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase))
    }

    private fun legacyTags(context: Context, dir: String): List<String> = runCatching {
        val raw = context.applicationContext.getSharedPreferences("player_extras", Context.MODE_PRIVATE)
            .getString("book_tags", "{}")
        val root = JSONObject(raw ?: "{}")
        val a = root.optJSONArray(dir) ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
}
