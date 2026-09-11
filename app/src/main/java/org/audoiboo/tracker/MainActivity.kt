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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.HostPluginHttpTransport
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.tts.FlibustaBookTtsFlow

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
    var preparingTts by remember { mutableStateOf(false) }

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
        webView?.settings?.userAgentString?.let(HostPluginHttpTransport::updateBrowserUserAgent)
        runCatching { CookieManager.getInstance().flush() }
        syncing = true
        scope.launch {
            val plugin = PluginPackageRuntime.registry.forUrl(url)
            val result = runCatching { RoomSeriesSync.sync(activity, url) }.getOrNull()
            syncing = false
            when {
                result?.seriesId != null -> {
                    Toast.makeText(activity, "${result.name}: ${result.books} книг додано", Toast.LENGTH_LONG).show()
                }
                result?.review != null -> {
                    Toast.makeText(
                        activity,
                        "${result.name}: серію знайдено (${result.books} книг), але збіг із бібліотекою потребує підтвердження",
                        Toast.LENGTH_LONG
                    ).show()
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

    fun startFlibustaTts() {
        val url = currentUrl.trim()
        if (!isFlibustaBookUrl(url) || preparingTts) return
        preparingTts = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val output = File(activity.filesDir, "tts/output")
                FlibustaBookTtsFlow.create(activity).enqueue(activity, url, output)
            }
            preparingTts = false
            result.onSuccess { session ->
                Toast.makeText(
                    activity,
                    "Озвучення поставлено в чергу • ${session.voice.displayName}",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    activity,
                    "Не вдалося запустити озвучення: ${error.message ?: "невідома помилка"}",
                    Toast.LENGTH_LONG
                ).show()
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
                    if (isFlibustaBookUrl(currentUrl)) {
                        IconButton(onClick = ::startFlibustaTts, enabled = !preparingTts) {
                            Icon(Icons.Filled.VolumeUp, "Озвучити книгу локально")
                        }
                    }
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
            if (syncing || preparingTts) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        HostPluginHttpTransport.updateBrowserUserAgent(settings.userAgentString)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.settings?.userAgentString?.let(HostPluginHttpTransport::updateBrowserUserAgent)
                                runCatching { CookieManager.getInstance().flush() }
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

internal fun isFlibustaBookUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase() ?: return@runCatching false
    val supportedHost = host == "flibusta.site" || host == "flibusta.one" || host == "flibusta.name" ||
        host.endsWith(".flibusta.site") || host.endsWith(".flibusta.one") || host.endsWith(".flibusta.name")
    supportedHost && Regex("^/b/[^/]+/?$").matches(uri.path.orEmpty())
}.getOrDefault(false)
