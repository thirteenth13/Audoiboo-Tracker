package org.audoiboo.tracker

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray

private data class Book(
    val title: String,
    val url: String,
    val read: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TrackerScreen() } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrackerScreen() {
    var seriesUrl by remember {
        mutableStateOf("https://audioboo.org/xfsearch/cikl/%D0%94%D1%80%D1%83%D0%B3%D0%B0%D1%8F%20%D1%81%D1%82%D0%BE%D1%80%D0%BE%D0%BD%D0%B0/")
    }
    var syncing by remember { mutableStateOf(false) }
    var books by remember { mutableStateOf(emptyList<Book>()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Audoiboo Tracker") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = seriesUrl,
                onValueChange = { seriesUrl = it },
                label = { Text("URL серії") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { syncing = true }) { Text("Синхронізувати") }
            Spacer(Modifier.height(12.dp))

            if (syncing) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    val js = """
                                        (function() {
                                          const abs = u => { try { return new URL(u, location.href).href; } catch(e) { return null; } };
                                          const out = [];
                                          const seen = new Set();
                                          const links = Array.from(document.querySelectorAll('a[href$=".html"], a[href*="/litrpg/"]'));
                                          for (const a of links) {
                                            const href = abs(a.getAttribute('href'));
                                            const title = (a.textContent || '').replace(/\\s+/g,' ').trim();
                                            if (!href || !title || title.length < 3 || seen.has(href)) continue;
                                            seen.add(href);
                                            out.push({title:title, url:href});
                                          }
                                          return JSON.stringify(out);
                                        })();
                                    """.trimIndent()
                                    view.evaluateJavascript(js) { raw ->
                                        runCatching {
                                            val decoded = raw.removeSurrounding("\"")
                                                .replace("\\\"", "\"")
                                                .replace("\\\\", "\\")
                                            val arr = JSONArray(decoded)
                                            val parsed = buildList {
                                                for (i in 0 until arr.length()) {
                                                    val o = arr.getJSONObject(i)
                                                    add(Book(o.getString("title"), o.getString("url")))
                                                }
                                            }
                                            books = parsed
                                        }
                                        syncing = false
                                    }
                                }
                            }
                            loadUrl(seriesUrl)
                        }
                    }
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(books) { book ->
                        ListItem(
                            headlineContent = { Text(book.title) },
                            supportingContent = { Text(book.url, maxLines = 1) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
