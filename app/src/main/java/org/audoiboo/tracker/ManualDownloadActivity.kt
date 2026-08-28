package org.audoiboo.tracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class ManualDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { ManualDownloadScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualDownloadScreen(activity: ComponentActivity) {
    var title by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var bookUrl by remember { mutableStateOf("") }
    var archiveUrl by remember { mutableStateOf("") }
    val valid = title.isNotBlank() && series.isNotBlank() && archiveUrl.startsWith("http")

    Scaffold(topBar = { TopAppBar(title = { Text("Ручне додавання") }, navigationIcon = { IconButton(onClick = activity::finish) { Icon(Icons.Filled.ArrowBack, "Назад") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fallback-режим, якщо JSoup і WebView не змогли знайти архів. Встав пряме HTTP(S)-посилання на ZIP/RAR/7z.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(title, { title = it }, label = { Text("Назва книги") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(series, { series = it }, label = { Text("Серія") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(author, { author = it }, label = { Text("Автор (необов’язково)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(bookUrl, { bookUrl = it }, label = { Text("Сторінка книги / Referer") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(archiveUrl, { archiveUrl = it }, label = { Text("Пряме посилання на архів") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.weight(1f))
            Button(
                enabled = valid,
                onClick = {
                    ManagedDownloads.enqueue(activity, title.trim(), series.trim(), author.trim().takeIf(String::isNotBlank), bookUrl.trim().ifBlank { archiveUrl.trim() }, archiveUrl.trim())
                    Toast.makeText(activity, "Додано до черги завантажень", Toast.LENGTH_SHORT).show()
                    activity.finish()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Додати до завантажень") }
        }
    }
}
