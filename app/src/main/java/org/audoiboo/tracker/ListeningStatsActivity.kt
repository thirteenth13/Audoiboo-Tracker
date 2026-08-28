package org.audoiboo.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ListeningStatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { ListeningStatsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListeningStatsScreen(activity: ComponentActivity) {
    val dailyRows by PlayerExtrasRepository.observeDaily(activity).collectAsState(initial = emptyList())
    val history by PlayerExtrasRepository.observeHistory(activity).collectAsState(initial = emptyList())
    var total by remember { mutableLongStateOf(0L) }

    LaunchedEffect(dailyRows) {
        total = PlayerExtrasRepository.stats(activity).totalMs
    }

    val daily = remember(dailyRows) { dailyRows.associate { it.day to it.listenedMs } }
    val todayKey = remember { dayKey(0) }
    val today = daily[todayKey] ?: 0L
    val week = (0..6).sumOf { daily[dayKey(it)] ?: 0L }
    val month = (0..29).sumOf { daily[dayKey(it)] ?: 0L }
    val activeDays7 = (0..6).count { (daily[dayKey(it)] ?: 0L) > 0L }
    val activeDays30 = (0..29).count { (daily[dayKey(it)] ?: 0L) > 0L }
    val bestDay = daily.maxByOrNull { it.value }
    val average7 = week / 7L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Статистика прослуховування") },
                navigationIcon = { IconButton({ activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Сьогодні", formatStatsMs(today), Modifier.weight(1f))
                StatCard("7 днів", formatStatsMs(week), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("30 днів", formatStatsMs(month), Modifier.weight(1f))
                StatCard("Загалом", formatStatsMs(total), Modifier.weight(1f))
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Активність", style = MaterialTheme.typography.titleMedium)
                    StatLine("Активних днів за 7 днів", "$activeDays7 / 7")
                    StatLine("Активних днів за 30 днів", "$activeDays30 / 30")
                    StatLine("Середнє за останні 7 днів", formatStatsMs(average7))
                    StatLine("Книг в історії", history.size.toString())
                    if (bestDay != null) StatLine("Найдовший день", "${bestDay.key} • ${formatStatsMs(bestDay.value)}")
                }
            }

            Text("Останні 7 днів", style = MaterialTheme.typography.titleMedium)
            val max = (0..6).maxOfOrNull { daily[dayKey(it)] ?: 0L }?.coerceAtLeast(1L) ?: 1L
            (6 downTo 0).forEach { ago ->
                val key = dayKey(ago)
                val value = daily[key] ?: 0L
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(shortDayLabel(ago))
                        Text(formatStatsMs(value), style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                        progress = { (value.toFloat() / max.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value)
    }
}

private fun dayKey(daysAgo: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    .format(Date(System.currentTimeMillis() - daysAgo * 86_400_000L))

private fun shortDayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> "Сьогодні"
    1 -> "Вчора"
    else -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(System.currentTimeMillis() - daysAgo * 86_400_000L))
}

private fun formatStatsMs(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "$hours год ${minutes} хв"
        else -> "$minutes хв"
    }
}
