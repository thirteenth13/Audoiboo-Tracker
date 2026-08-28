package org.audoiboo.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

internal enum class SleepTimerMode { OFF, MINUTES, TRACK, BOOK, SERIES }

internal object SleepTimerStore {
    private const val PREFS = "sleep_timer"
    private const val MODE = "mode"
    private const val TARGET_AT = "target_at"
    private const val START_MEDIA = "start_media"
    private const val START_BOOK = "start_book"
    private const val START_SERIES = "start_series"

    data class State(
        val mode: SleepTimerMode,
        val targetAt: Long,
        val startMediaId: String,
        val startBook: String,
        val startSeries: String
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): State {
        val p = prefs(context)
        val mode = runCatching { SleepTimerMode.valueOf(p.getString(MODE, SleepTimerMode.OFF.name) ?: SleepTimerMode.OFF.name) }.getOrDefault(SleepTimerMode.OFF)
        return State(mode, p.getLong(TARGET_AT, 0L), p.getString(START_MEDIA, "").orEmpty(), p.getString(START_BOOK, "").orEmpty(), p.getString(START_SERIES, "").orEmpty())
    }

    fun save(context: Context, state: State) {
        prefs(context).edit()
            .putString(MODE, state.mode.name)
            .putLong(TARGET_AT, state.targetAt)
            .putString(START_MEDIA, state.startMediaId)
            .putString(START_BOOK, state.startBook)
            .putString(START_SERIES, state.startSeries)
            .apply()
    }

    fun clear(context: Context) = save(context, State(SleepTimerMode.OFF, 0L, "", "", ""))

    fun start(context: Context, mode: SleepTimerMode, minutes: Int = 0) {
        val intent = Intent(context, SleepTimerService::class.java)
            .putExtra(SleepTimerService.EXTRA_MODE, mode.name)
            .putExtra(SleepTimerService.EXTRA_MINUTES, minutes)
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel(context: Context) {
        context.startService(Intent(context, SleepTimerService::class.java).setAction(SleepTimerService.ACTION_CANCEL))
    }
}

class SleepTimerService : Service() {
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var state = SleepTimerStore.State(SleepTimerMode.OFF, 0L, "", "", "")
    private var reachedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (state.mode != SleepTimerMode.OFF) handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        state = SleepTimerStore.state(this)
        connectController()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelTimer()
            return START_NOT_STICKY
        }
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { SleepTimerMode.valueOf(it) }.getOrNull() }
        if (mode != null && mode != SleepTimerMode.OFF) {
            val minutes = intent.getIntExtra(EXTRA_MINUTES, 0).coerceAtLeast(0)
            arm(mode, minutes)
        } else if (state.mode != SleepTimerMode.OFF) {
            startForeground(NOTIFICATION_ID, notification())
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            runCatching { f.get() }.onSuccess { c ->
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = evaluateBoundary(mediaItem)
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) evaluateEnded()
                    }
                })
                if (state.mode != SleepTimerMode.OFF && state.startMediaId.isBlank()) captureStartIdentity()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun arm(mode: SleepTimerMode, minutes: Int) {
        val target = if (mode == SleepTimerMode.MINUTES) System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L else 0L
        state = SleepTimerStore.State(mode, target, "", "", "")
        captureStartIdentity()
        SleepTimerStore.save(this, state)
        reachedAt = 0L
        startForeground(NOTIFICATION_ID, notification())
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun captureStartIdentity() {
        val c = controller ?: return
        val item = currentLibraryItem(c.currentMediaItem?.mediaId)
        state = state.copy(
            startMediaId = c.currentMediaItem?.mediaId.orEmpty(),
            startBook = item?.bookTitle.orEmpty().ifBlank { item?.relativePath.orEmpty() },
            startSeries = item?.series.orEmpty()
        )
        SleepTimerStore.save(this, state)
    }

    private fun currentLibraryItem(mediaId: String?): PlayerLibraryItem? {
        if (mediaId.isNullOrBlank()) return null
        return PlayerLibrary.all(this).firstOrNull { it.uri == mediaId }
    }

    private fun evaluateBoundary(mediaItem: MediaItem?) {
        if (state.mode == SleepTimerMode.OFF || reachedAt > 0L) return
        if (state.startMediaId.isBlank()) {
            captureStartIdentity()
            return
        }
        val currentId = mediaItem?.mediaId.orEmpty()
        val item = currentLibraryItem(currentId)
        val currentBook = item?.bookTitle.orEmpty().ifBlank { item?.relativePath.orEmpty() }
        val currentSeries = item?.series.orEmpty()
        when (state.mode) {
            SleepTimerMode.TRACK -> if (currentId.isNotBlank() && currentId != state.startMediaId) reachTarget()
            SleepTimerMode.BOOK -> if (currentBook.isNotBlank() && state.startBook.isNotBlank() && currentBook != state.startBook) reachTarget()
            SleepTimerMode.SERIES -> if (currentSeries != state.startSeries && (currentSeries.isNotBlank() || state.startSeries.isNotBlank())) reachTarget()
            else -> Unit
        }
    }

    private fun evaluateEnded() {
        when (state.mode) {
            SleepTimerMode.TRACK, SleepTimerMode.BOOK -> reachTarget()
            SleepTimerMode.SERIES -> {
                // Keep the guard alive briefly: PlayerActivity may load the next queued book after STATE_ENDED.
                reachedAt = System.currentTimeMillis()
                controller?.pause()
            }
            else -> Unit
        }
    }

    private fun tick() {
        val c = controller
        val now = System.currentTimeMillis()
        if (state.mode == SleepTimerMode.MINUTES && state.targetAt > 0L) {
            val remaining = state.targetAt - now
            if (remaining <= 0L) {
                reachTarget()
                return
            }
            if (c != null) c.volume = if (remaining <= 30_000L) (remaining / 30_000f).coerceIn(0.05f, 1f) else 1f
        }
        if (state.mode == SleepTimerMode.SERIES && reachedAt > 0L) {
            c?.pause()
            if (now - reachedAt >= 10_000L) finishTimer()
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification())
    }

    private fun reachTarget() {
        if (reachedAt > 0L) return
        reachedAt = System.currentTimeMillis()
        controller?.pause()
        controller?.volume = 1f
        if (state.mode == SleepTimerMode.SERIES) return
        finishTimer()
    }

    private fun finishTimer() {
        controller?.pause()
        controller?.volume = 1f
        SleepTimerStore.clear(this)
        state = SleepTimerStore.state(this)
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelTimer() {
        controller?.volume = 1f
        SleepTimerStore.clear(this)
        state = SleepTimerStore.state(this)
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): android.app.Notification {
        val text = when (state.mode) {
            SleepTimerMode.MINUTES -> {
                val remaining = (state.targetAt - System.currentTimeMillis()).coerceAtLeast(0L)
                "Зупинка через ${(remaining + 59_999L) / 60_000L} хв"
            }
            SleepTimerMode.TRACK -> "Зупинка після поточного файла"
            SleepTimerMode.BOOK -> "Зупинка після поточної книги"
            SleepTimerMode.SERIES -> "Зупинка після поточної серії"
            SleepTimerMode.OFF -> "Таймер вимкнений"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, SleepTimerService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Audoiboo — таймер сну")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Вимкнути", cancel)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Таймер сну", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        controller?.volume = 1f
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_CANCEL = "org.audoiboo.tracker.CANCEL_SLEEP_TIMER"
        internal const val EXTRA_MODE = "mode"
        internal const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL_ID = "audoiboo_sleep_timer"
        private const val NOTIFICATION_ID = 7301
    }
}
