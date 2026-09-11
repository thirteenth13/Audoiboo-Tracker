package org.audoiboo.tracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.audoiboo.tracker.plugin.NetworkFallbackSettings
import org.audoiboo.tracker.plugin.NetworkProxyConfig
import org.audoiboo.tracker.plugin.NetworkProxyType

class NetworkSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { NetworkSettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkSettingsScreen(activity: ComponentActivity) {
    val initial = remember { NetworkFallbackSettings.current(activity) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var type by remember { mutableStateOf(initial.type) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port == 0) "" else initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мережевий fallback") },
                navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Порядок відновлення: звичайне з’єднання → DoH при помилці DNS → проксі при блокуванні IP/маршруту.",
                style = MaterialTheme.typography.bodyMedium
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Проксі fallback")
                            Text("Використовується лише після помилки прямого з’єднання.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }

                    Text("Тип проксі", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row {
                            RadioButton(selected = type == NetworkProxyType.HTTP, onClick = { type = NetworkProxyType.HTTP })
                            Text("HTTP", modifier = Modifier.padding(top = 12.dp))
                        }
                        Row {
                            RadioButton(selected = type == NetworkProxyType.SOCKS5, onClick = { type = NetworkProxyType.SOCKS5 })
                            Text("SOCKS5", modifier = Modifier.padding(top = 12.dp))
                        }
                    }

                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Хост / IP проксі") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                        label = { Text("Порт") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (type == NetworkProxyType.HTTP) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Користувач (необов’язково)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Пароль (необов’язково)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "SOCKS5 зараз підтримується без логіна/пароля. DNS цільового сайту передається через SOCKS як unresolved host.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            val config = NetworkProxyConfig(
                                enabled = enabled,
                                type = type,
                                host = host,
                                port = port.toIntOrNull() ?: 0,
                                username = username,
                                password = password
                            ).sanitized()
                            if (enabled && !config.isUsable) {
                                Toast.makeText(activity, "Вкажи коректний хост і порт проксі", Toast.LENGTH_LONG).show()
                            } else {
                                NetworkFallbackSettings.save(activity, config)
                                Toast.makeText(activity, if (enabled) "Проксі fallback збережено" else "Проксі fallback вимкнено", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Зберегти") }
                }
            }

            Text(
                "DoH не вимикається: він застосовується тільки коли DNS справді не знайшов хост. Якщо IP уже визначений, але з’єднання блокується, застосунок одразу переходить до налаштованого проксі.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
