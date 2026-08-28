package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
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
        }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }

    companion object {
        fun mediaItem(item: PlayerLibraryItem): MediaItem = MediaItem.Builder()
            .setUri(item.uri)
            .setMediaId(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.name)
                    .setAlbumTitle(item.bookTitle ?: item.series)
                    .setArtist(item.author)
                    .build()
            ).build()
    }
}
