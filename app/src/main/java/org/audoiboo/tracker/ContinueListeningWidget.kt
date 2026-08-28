package org.audoiboo.tracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class ContinueListeningWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAll(context)
    }

    companion object {
        internal const val ACTION_REFRESH = "org.audoiboo.tracker.UPDATE_CONTINUE_WIDGET"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueListeningWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val resume = PlayerExtras.resume(context)
            val snapshot = PlayerExtras.snapshot(context)
            val views = RemoteViews(context.packageName, R.layout.widget_continue_listening)
            if (resume == null) {
                views.setTextViewText(R.id.widget_title, "Audoiboo Tracker")
                views.setTextViewText(R.id.widget_subtitle, "Вибери аудіокнигу для прослуховування")
                views.setTextViewText(R.id.widget_progress, "")
                views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent(context, appWidgetId))
            } else {
                views.setTextViewText(R.id.widget_title, resume.title.ifBlank { "Продовжити слухати" })
                val track = PlayerLibrary.all(context).firstOrNull { it.uri == resume.uri }
                val series = track?.series?.takeIf { it.isNotBlank() }
                views.setTextViewText(R.id.widget_subtitle, series ?: "Продовжити слухати")
                val progressText = snapshot?.takeIf { it.dir == resume.dir }?.let { s ->
                    val trackName = track?.name?.takeIf { it.isNotBlank() }
                    val time = formatTime(s.positionMs)
                    listOfNotNull(trackName, time).joinToString(" • ")
                }.orEmpty()
                views.setTextViewText(R.id.widget_progress, progressText)
                val open = Intent(context, PlayerActivity::class.java)
                    .putExtra("relativeDir", resume.dir)
                    .putExtra("title", resume.title)
                val pending = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pending)
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun mainPendingIntent(context: Context, requestCode: Int): PendingIntent {
            return PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun formatTime(ms: Long): String {
            val total = ms.coerceAtLeast(0L) / 1000L
            val h = total / 3600L
            val m = (total % 3600L) / 60L
            val s = total % 60L
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
