package org.audoiboo.tracker

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.audoiboo.tracker.plugin.PluginPackageRuntime

private const val SOURCE_BROWSER_DEFAULT_HOME = "https://audioboo.org/"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginPackageRuntime.initialize(filesDir)
        setContent {
            AudoibooTheme(this) {
                SourceBrowserScreen(
                    activity = this,
                    initialUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") }
                        ?: SOURCE_BROWSER_DEFAULT_HOME
                )
            }
        }
    }

    companion object {
        const val EXTRA_URL = "source_url"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceBrowserScreen(activity: ComponentActivity, initialUrl: String) {
    val scope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var address by remember { mutableStateOf(initialUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var syncing by remember { mutableStateOf(false) }

    fun navigate(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        val target = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        address = target
        webView?.loadUrl(target)
    }

    fun addCurrentPage() {
        val url = currentUrl.trim()
        if (url.isBlank() || syncing) return
        syncing = true
        scope.launch {
            val plugin = PluginPackageRuntime.registry.forUrl(url)
            val result = runCatching { RoomSeriesSync.sync(activity, url) }.getOrNull()
            syncing = false
            when {
                result?.seriesId != null -> {
                    Toast.makeText(activity, "${result.name}: ${result.books} книг додано", Toast.LENGTH_LONG).show()
                }
                plugin == null -> {
                    Toast.makeText(activity, "Для цього сайту немає активного плагіна", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(activity, "Плагін ${plugin.descriptor.name} не зміг визначити серію з цієї сторінки", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Браузер джерел") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (webView?.canGoBack() == true) webView?.goBack() else activity.finish()
                    }) { Icon(Icons.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { webView?.loadUrl(SOURCE_BROWSER_DEFAULT_HOME) }) { Icon(Icons.Filled.Home, "Головна") }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Оновити") }
                    IconButton(onClick = ::addCurrentPage, enabled = !syncing) { Icon(Icons.Filled.Add, "Додати поточну сторінку") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                singleLine = true,
                label = { Text("URL будь-якого джерела") },
                trailingIcon = {
                    TextButton(onClick = { navigate(address) }) { Text("Відкрити") }
                }
            )
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                            override fun onPageFinished(view: WebView?, url: String?) {
                                val loaded = url.orEmpty()
                                if (loaded.isNotBlank()) {
                                    currentUrl = loaded
                                    address = loaded
                                }
                            }
                        }
                        webView = this
                        loadUrl(initialUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
