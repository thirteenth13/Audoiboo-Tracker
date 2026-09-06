package org.audoiboo.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.audoiboo.tracker.plugin.SeriesDiagnosticLog

class SeriesLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { SeriesLogScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesLogScreen(activity: ComponentActivity) {
    var text by remember { mutableStateOf(SeriesDiagnosticLog.snapshot()) }

    fun refresh() { text = SeriesDiagnosticLog.snapshot() }
    fun copy() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AudoibooSeries log", text))
        Toast.makeText(activity, "Лог скопійовано", Toast.LENGTH_SHORT).show()
    }
    fun share() {
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AudoibooSeries diagnostic log")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Поділитися логом"))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Лог пошуку серій") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Внутрішній журнал AudoibooSeries. ADB не потрібен.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }) { Text("Оновити") }
                OutlinedButton(onClick = { copy() }) { Text("Копіювати") }
                OutlinedButton(onClick = { share() }) { Text("Поділитися") }
            }
            TextButton(onClick = { SeriesDiagnosticLog.clear(); refresh() }) { Text("Очистити лог") }
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Text(text, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
