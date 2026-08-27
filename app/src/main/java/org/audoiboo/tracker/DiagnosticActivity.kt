package org.audoiboo.tracker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray

class DiagnosticActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DiagnosticScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagnosticScreen() {
    val context = LocalContext.current
    var url by remember {
        mutableStateOf("https://audioboo.org/litrpg/118927-korablev-rodion-drugaja-storona-26-lichnyj-vrag.html")
    }
    var activeUrl by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var dump by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Audoiboo book DOM", text))
        Toast.makeText(context, "DOM сторінки книги скопійовано", Toast.LENGTH_LONG).show()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Audoiboo DOM книги") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL сторінки книги") },
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    loaded = false
                    activeUrl = url.trim()
                }, enabled = url.startsWith("http")) { Text("Відкрити") }

                Button(onClick = {
                    webViewRef?.evaluateJavascript(bookDiagnosticJsStandalone) { raw ->
                        val decoded = decodeJsStringStandalone(raw)
                        dump = decoded
                        copy(decoded)
                    }
                }, enabled = loaded) { Text("DOM архів") }
            }

            if (activeUrl != null) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, finishedUrl: String) {
                                    loaded = true
                                }
                            }
                            loadUrl(activeUrl!!)
                        }
                    },
                    update = { view ->
                        webViewRef = view
                        if (view.url != activeUrl) view.loadUrl(activeUrl!!)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Text(
                    "Встав URL конкретної книги, натисни «Відкрити», дочекайся сторінки й натисни «DOM архів».",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    dump?.let { text ->
        AlertDialog(
            onDismissRequest = { dump = null },
            title = { Text("DOM архів") },
            text = { Text(text.take(4500), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { copy(text); dump = null }) { Text("Скопіювати") }
            },
            dismissButton = { TextButton(onClick = { dump = null }) { Text("Закрити") } }
        )
    }
}

private val bookDiagnosticJsStandalone = """
(function() {
  const abs = u => { try { return new URL(u, location.href).href; } catch(e) { return u || ''; } };
  const norm = s => (s || '').replace(/\s+/g, ' ').trim();
  const all = Array.from(document.querySelectorAll('a[href],button,[onclick],[data-href],[data-url]'));
  const nodes = all.map((el,i) => {
    const raw = el.getAttribute('href') || el.getAttribute('data-href') || el.getAttribute('data-url') || '';
    const text = norm(el.textContent);
    const onclick = el.getAttribute('onclick') || '';
    const parent = el.parentElement;
    const grand = parent ? parent.parentElement : null;
    return {
      i,
      tag: el.tagName,
      text,
      raw,
      href: raw ? abs(raw) : '',
      cls: el.className || '',
      id: el.id || '',
      onclick,
      html: (el.outerHTML || '').slice(0,1200),
      parentHtml: parent ? (parent.outerHTML || '').slice(0,2200) : '',
      grandParentHtml: grand ? (grand.outerHTML || '').slice(0,3200) : ''
    };
  }).filter(x => /скач|download|торрент|torrent|магнит|magnet|архив|zip|rar|7z|topper|примагнит/i.test(
      x.text + ' ' + x.raw + ' ' + x.href + ' ' + x.onclick + ' ' + x.parentHtml
  ));

  return JSON.stringify({
    type: 'BOOK',
    url: location.href,
    title: document.title,
    candidateCount: nodes.length,
    candidates: nodes.slice(0,60)
  }, null, 2);
})();
""".trimIndent()

private fun decodeJsStringStandalone(raw: String): String = try {
    JSONArray("[$raw]").getString(0)
} catch (_: Exception) {
    raw.trim('"')
}
