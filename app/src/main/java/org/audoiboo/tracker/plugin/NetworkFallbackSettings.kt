package org.audoiboo.tracker.plugin

import android.content.Context

internal enum class NetworkProxyType { HTTP, SOCKS5 }

internal data class NetworkProxyConfig(
    val enabled: Boolean = false,
    val type: NetworkProxyType = NetworkProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = ""
) {
    val isUsable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    fun sanitized(): NetworkProxyConfig = copy(
        host = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/'),
        port = port.coerceIn(0, 65535),
        username = username.trim()
    )
}

/** Lightweight network-only preferences kept separate from library/player settings migrations. */
internal object NetworkFallbackSettings {
    private const val FILE = "network_fallback"
    private const val ENABLED = "proxy_enabled"
    private const val TYPE = "proxy_type"
    private const val HOST = "proxy_host"
    private const val PORT = "proxy_port"
    private const val USER = "proxy_user"
    private const val PASS = "proxy_pass"

    @Volatile private var appContext: Context? = null
    @Volatile private var cached = NetworkProxyConfig()

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        cached = read(app)
    }

    fun current(): NetworkProxyConfig = cached

    fun current(context: Context): NetworkProxyConfig {
        if (appContext == null) initialize(context)
        return cached
    }

    fun save(context: Context, value: NetworkProxyConfig) {
        val app = context.applicationContext
        val clean = value.sanitized()
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, clean.enabled)
            .putString(TYPE, clean.type.name)
            .putString(HOST, clean.host)
            .putInt(PORT, clean.port)
            .putString(USER, clean.username)
            .putString(PASS, clean.password)
            .apply()
        appContext = app
        cached = clean
    }

    private fun read(context: Context): NetworkProxyConfig {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val type = runCatching {
            NetworkProxyType.valueOf(p.getString(TYPE, NetworkProxyType.HTTP.name).orEmpty())
        }.getOrDefault(NetworkProxyType.HTTP)
        return NetworkProxyConfig(
            enabled = p.getBoolean(ENABLED, false),
            type = type,
            host = p.getString(HOST, "").orEmpty(),
            port = p.getInt(PORT, 0),
            username = p.getString(USER, "").orEmpty(),
            password = p.getString(PASS, "").orEmpty()
        ).sanitized()
    }
}
