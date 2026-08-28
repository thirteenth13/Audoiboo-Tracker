package org.audoiboo.tracker

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

internal data class PlayerLibraryItem(
    val uri: String,
    val name: String,
    val relativePath: String,
    val bookTitle: String? = null,
    val series: String? = null,
    val author: String? = null
)

internal object PlayerLibrary {
    private const val PREFS = "player_library"
    private const val KEY = "items"

    @Synchronized
    fun register(context: Context, uri: Uri, name: String, relativePath: String, bookTitle: String? = null, series: String? = null, author: String? = null) {
        val items = all(context).toMutableList()
        val item = PlayerLibraryItem(uri.toString(), name, relativePath, bookTitle, series, author)
        val index = items.indexOfFirst { it.uri == item.uri }
        if (index >= 0) items[index] = item else items.add(item)
        save(context, items)
    }

    @Synchronized
    fun replaceAll(context: Context, items: List<PlayerLibraryItem>) = save(context, items.distinctBy { it.uri })

    fun all(context: Context): List<PlayerLibraryItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlayerLibraryItem(
                    uri = o.optString("uri"),
                    name = o.optString("name"),
                    relativePath = o.optString("relativePath"),
                    bookTitle = o.optString("bookTitle").takeIf { it.isNotBlank() && it != "null" },
                    series = o.optString("series").takeIf { it.isNotBlank() && it != "null" },
                    author = o.optString("author").takeIf { it.isNotBlank() && it != "null" }
                )
            }.filter { it.uri.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    fun forPath(context: Context, relativeDir: String?): List<PlayerLibraryItem> {
        val all = all(context)
        return if (relativeDir.isNullOrBlank()) all else all.filter { it.relativePath.contains(relativeDir, ignoreCase = true) }
    }

    private fun save(context: Context, items: List<PlayerLibraryItem>) {
        val arr = JSONArray()
        items.takeLast(5000).forEach { r ->
            arr.put(JSONObject().put("uri", r.uri).put("name", r.name).put("relativePath", r.relativePath)
                .put("bookTitle", r.bookTitle).put("series", r.series).put("author", r.author))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var audioPrefs: SharedPreferences
    private val audioPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyVoiceBoost() }

    override fun onCreate() {
        super.onCreate()
        audioPrefs = getSharedPreferences("audio_enhancement", Context.MODE_PRIVATE)
        audioPrefs.registerOnSharedPreferenceChangeListener(audioPrefsListener)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    attachVoiceBoost(audioSessionId)
                }
            })
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    private fun attachVoiceBoost(audioSessionId: Int) {
        loudnessEnhancer?.runCatching { release() }
        loudnessEnhancer = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        applyVoiceBoost()
    }

    private fun applyVoiceBoost() {
        val enhancer = loudnessEnhancer ?: return
        runCatching {
            enhancer.setTargetGain(AudioEnhancementPrefs.gainMb(this))
            enhancer.enabled = AudioEnhancementPrefs.voiceBoost(this)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        audioPrefs.unregisterOnSharedPreferenceChangeListener(audioPrefsListener)
        loudnessEnhancer?.runCatching { release() }
        loudnessEnhancer = null
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(folderItem(ROOT_ID, "Audoiboo Tracker"), params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = PlayerLibrary.all(this@PlaybackService)
            val children = when {
                parentId == ROOT_ID -> all.map { it.series?.takeIf(String::isNotBlank) ?: NO_SERIES }
                    .distinct().sortedBy { it.lowercase() }.map { series -> folderItem(seriesId(series), series) }
                parentId.startsWith(SERIES_PREFIX) -> {
                    val series = Uri.decode(parentId.removePrefix(SERIES_PREFIX))
                    booksForSeries(all, series).map { (title, items) -> bookFolder(series, title, items) }
                }
                parentId.startsWith(BOOK_PREFIX) -> {
                    val (series, title) = decodeBookId(parentId) ?: ("" to "")
                    all.filter { librarySeries(it) == series && libraryBookTitle(it) == title }
                        .sortedBy { naturalAudioKey(it.name) }.map(::mediaItem)
                }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(children.size)
            val to = (from + pageSize).coerceAtMost(children.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(children.subList(from, to), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val all = PlayerLibrary.all(this@PlaybackService)
            val item = when {
                mediaId == ROOT_ID -> folderItem(ROOT_ID, "Audoiboo Tracker")
                mediaId.startsWith(SERIES_PREFIX) -> Uri.decode(mediaId.removePrefix(SERIES_PREFIX)).let { folderItem(mediaId, it) }
                mediaId.startsWith(BOOK_PREFIX) -> decodeBookId(mediaId)?.let { (series, title) ->
                    val matches = all.filter { librarySeries(it) == series && libraryBookTitle(it) == title }
                    if (matches.isNotEmpty()) bookFolder(series, title, matches) else null
                }
                else -> all.firstOrNull { it.uri == mediaId }?.let(::mediaItem)
            }
            return if (item != null) Futures.immediateFuture(LibraryResult.ofItem(item, null))
            else Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val all = PlayerLibrary.all(this@PlaybackService)
            val resolved = mediaItems.flatMap { requested ->
                when {
                    requested.mediaId.startsWith(BOOK_PREFIX) -> {
                        val (series, title) = decodeBookId(requested.mediaId) ?: return@flatMap emptyList()
                        all.filter { librarySeries(it) == series && libraryBookTitle(it) == title }
                            .sortedBy { naturalAudioKey(it.name) }.map(::mediaItem)
                    }
                    else -> all.firstOrNull { it.uri == requested.mediaId }?.let { listOf(mediaItem(it)) }
                        ?: if (requested.localConfiguration != null) listOf(requested) else emptyList()
                }
            }
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        private const val ROOT_ID = "audoiboo:root"
        private const val SERIES_PREFIX = "audoiboo:series:"
        private const val BOOK_PREFIX = "audoiboo:book:"
        private const val NO_SERIES = "Без серії"

        internal fun mediaItem(item: PlayerLibraryItem): MediaItem = MediaItem.Builder()
            .setUri(item.uri)
            .setMediaId(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.name)
                    .setAlbumTitle(item.bookTitle ?: item.series)
                    .setArtist(item.author)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build()
            ).build()

        private fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).build()
            ).build()

        private fun seriesId(series: String) = SERIES_PREFIX + Uri.encode(series)
        private fun bookId(series: String, title: String) = BOOK_PREFIX + Uri.encode(series) + "/" + Uri.encode(title)
        private fun decodeBookId(id: String): Pair<String, String>? {
            val value = id.removePrefix(BOOK_PREFIX)
            val slash = value.indexOf('/')
            if (slash < 0) return null
            return Uri.decode(value.substring(0, slash)) to Uri.decode(value.substring(slash + 1))
        }
        private fun librarySeries(item: PlayerLibraryItem) = item.series?.takeIf(String::isNotBlank) ?: NO_SERIES
        private fun libraryBookTitle(item: PlayerLibraryItem) = item.bookTitle?.takeIf(String::isNotBlank)
            ?: item.relativePath.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { "Книга" }
        private fun booksForSeries(all: List<PlayerLibraryItem>, series: String): List<Pair<String, List<PlayerLibraryItem>>> =
            all.filter { librarySeries(it) == series }.groupBy(::libraryBookTitle).entries
                .sortedWith(compareBy<Map.Entry<String, List<PlayerLibraryItem>>> { PlayerLogic.parseBookNumber(it.key) ?: Int.MAX_VALUE }.thenBy { it.key.lowercase() })
                .map { it.key to it.value }
        private fun bookFolder(series: String, title: String, items: List<PlayerLibraryItem>): MediaItem = MediaItem.Builder()
            .setMediaId(bookId(series, title))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(items.firstOrNull()?.author)
                    .setAlbumTitle(series.takeUnless { it == NO_SERIES })
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build()
            ).build()
        private fun naturalAudioKey(name: String) = name.lowercase().replace(Regex("(\\d+)")) { it.value.padStart(12, '0') }
    }
}
