package com.atlas.agent.core.util

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Util {

    fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "\n…[truncated ${s.length - max} chars]"

    fun fmtBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private val hhmm = SimpleDateFormat("HH:mm", Locale.US)
    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun fmtTime(ts: Long): String = if (ts <= 0) "—" else hhmm.format(Date(ts))
    fun fmtDateTime(ts: Long): String = if (ts <= 0) "—" else full.format(Date(ts))

    fun ago(ts: Long): String {
        if (ts <= 0) return "never"
        val d = System.currentTimeMillis() - ts
        return when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} h ago"
            else -> "${d / 86_400_000} d ago"
        }
    }

    fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.', '-')
        return cleaned.ifBlank { "file" }
    }

    fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "txt", "md", "log" -> "text/plain"
        else -> "application/octet-stream"
    }

    fun readTextFile(file: File, maxBytes: Int = 512 * 1024): String {
        if (!file.exists()) throw IllegalArgumentException("No such file: ${file.absolutePath}")
        if (file.length() > maxBytes) throw IllegalArgumentException("File too large (${fmtBytes(file.length())}); max ${fmtBytes(maxBytes.toLong())}")
        return file.readText()
    }

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        s = s.replace(Regex("(?is)<head.*?</head>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        s = s.replace(Regex("(?is)<[^>]+>"), " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "bull" to "•",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "euro" to "€", "pound" to "£",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "times" to "×", "divide" to "÷",
    )

    fun decodeEntities(s: String): String {
        var out = s
        out = out.replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            String(Character.toChars(code))
        }
        out = out.replace(Regex("&#(\\d+);")) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        out = out.replace(Regex("&([a-zA-Z]+);")) { m ->
            namedEntities[m.groupValues[1]] ?: m.value
        }
        return out
    }

    fun deviceSummary(app: Application): String {
        val sb = StringBuilder()
        sb.append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        runCatching {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            sb.append(", battery $level%${if (charging) " (charging)" else ""}")
        }
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            sb.append(", storage free ${fmtBytes(stat.availableBytes)}")
        }
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            val kind = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            sb.append(", network $kind")
        }
        return sb.toString()
    }
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.get().build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url: ${Util.truncate(body, 300)}")
            return body
        }
    }
}
