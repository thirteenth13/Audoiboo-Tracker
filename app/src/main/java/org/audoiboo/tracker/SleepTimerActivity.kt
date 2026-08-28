package org.audoiboo.tracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SleepTimerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { SleepTimerScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerScreen(activity: ComponentActivity) {
    var state by remember { mutableStateOf(SleepTimerStore.state(activity)) }
    fun arm(mode: SleepTimerMode, minutes: Int = 0) {
        SleepTimerStore.start(activity, mode, minutes)
        state = SleepTimerStore.State(mode, if (mode == SleepTimerMode.MINUTES) System.currentTimeMillis() + minutes * 60_000L else 0L, "", "", "")
        Toast.makeText(activity, "Таймер увімкнено", Toast.LENGTH_SHORT).show()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Таймер сну") }, navigationIcon = { IconButton({ activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bedtime, null, Modifier.size(38.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Поточний режим", style = MaterialTheme.typography.titleMedium)
                        Text(timerDescription(state), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text("За часом", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    AssistChip(onClick = { arm(SleepTimerMode.MINUTES, minutes) }, label = { Text("$minutes хв") })
                }
            }

            Text("За завершенням", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { arm(SleepTimerMode.TRACK) }, modifier = Modifier.fillMaxWidth()) { Text("До кінця поточного файла") }
            Button(onClick = { arm(SleepTimerMode.BOOK) }, modifier = Modifier.fillMaxWidth()) { Text("До кінця поточної книги") }
            Button(onClick = { arm(SleepTimerMode.SERIES) }, modifier = Modifier.fillMaxWidth()) { Text("До кінця поточної серії") }

            HorizontalDivider()
            Text("Для таймера за часом гучність плавно зменшується протягом останніх 30 секунд. Режими книги та серії відстежують фактичні переходи Media3, тому працюють і при зміні файлів.", style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                onClick = {
                    SleepTimerStore.cancel(activity)
                    state = SleepTimerStore.State(SleepTimerMode.OFF, 0L, "", "", "")
                    Toast.makeText(activity, "Таймер вимкнено", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.mode != SleepTimerMode.OFF
            ) { Text("Вимкнути таймер") }
        }
    }
}

private fun timerDescription(state: SleepTimerStore.State): String = when (state.mode) {
    SleepTimerMode.OFF -> "Вимкнений"
    SleepTimerMode.MINUTES -> {
        val minutes = ((state.targetAt - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
        "Зупинка приблизно через $minutes хв"
    }
    SleepTimerMode.TRACK -> "До кінця поточного файла"
    SleepTimerMode.BOOK -> "До кінця поточної книги"
    SleepTimerMode.SERIES -> "До кінця поточної серії"
}
