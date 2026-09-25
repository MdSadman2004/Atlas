package com.atlas.agent.core.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.agent.ToolContext
import com.atlas.agent.core.agent.boolOr
import com.atlas.agent.core.agent.intOr
import com.atlas.agent.core.agent.reqStr
import com.atlas.agent.core.agent.str
import com.atlas.agent.core.notify.Notifications
import com.atlas.agent.core.util.Util
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceInfoTool : Tool {
    override val name = "device_info"
    override val description =
        "Get this phone's status: model, Android version, battery, storage, network, accessibility service state and which permissions are granted. Call this before other device tasks if unsure what is available."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val app = ctx.app
        val sb = StringBuilder()
        sb.append("summary: ${Util.deviceSummary(app)}\n")
        sb.append("uptime: ${SystemClock.elapsedRealtime() / 60000} min\n")
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            sb.append("shared storage: ${Util.fmtBytes(stat.availableBytes)} free of ${Util.fmtBytes(stat.totalBytes)}\n")
        }
        runCatching {
            val ips = ArrayList<String>()
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        ips.add("${ni.name}=${addr.hostAddress}")
                    }
                }
            }
            if (ips.isNotEmpty()) sb.append("local ip: ${ips.joinToString(", ")}\n")
        }
        sb.append("accessibility automation: ${if (com.atlas.agent.core.a11y.AtlasA11yService.isEnabled(app)) "ENABLED" else "disabled"}\n")
        val perms = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val granted = perms.filter { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        val denied = perms.filterNot { granted.contains(it) }.map { it.substringAfterLast('.') }
        sb.append("permissions granted: ${granted.joinToString(", ") { it.substringAfterLast('.') }.ifBlank { "none" }}\n")
        if (denied.isNotEmpty()) sb.append("permissions NOT granted: ${denied.joinToString(", ")}\n")
        return sb.toString()
    }
}

class BatteryTool : Tool {
    override val name = "battery"
    override val description = "Read battery level, charging state and temperature."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val app = ctx.app
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val intent = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)
        val volts = intent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0)
        return "level: $level%\ncharging: $charging\ntemperature: ${temp ?: "?"}°C\nvoltage: ${volts ?: 0} mV"
    }
}

class NowTool : Tool {
    override val name = "now"
    override val description = "Get the current date, time, weekday, timezone and unix timestamp. Always use this instead of guessing the time."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm:ss z", Locale.US)
        val tz = java.util.TimeZone.getDefault()
        return "${fmt.format(Date(now))}\nunix_ms=$now\ntz=${tz.id} (UTC${tz.getOffset(now) / 3600000}h)"
    }
}

class CalcTool : Tool {
    override val name = "calc"
    override val description =
        "Evaluate a math expression exactly (+ - * / % ^ parentheses, functions sqrt abs sin cos tan log log10 exp floor ceil round min max pow, constants pi e). Use this for any arithmetic."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"expression":{"type":"string","description":"e.g. (245*17)^0.5 + min(3,9)"}},"required":["expression"]}"""
    )
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val expr = args.reqStr("expression")
        return runCatching { Calc.eval(expr).toString() }
            .getOrElse { "ERROR: ${it.message}" }
    }
}

class LocationTool : Tool {
    override val name = "location"
    override val description = "Get the phone's last known location (latitude, longitude, accuracy, age). Requires the location permission."
    override val parameters = JSONObject("""{"type":"object","properties":{"high_accuracy":{"type":"boolean","description":"prefer GPS provider"}}}""")
    override val group = "device"
    override val defaultEnabled = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val app = ctx.app
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return "Location permission is not granted. Ask the user to allow Location for Atlas in Android settings."
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val prefer = if (args.boolOr("high_accuracy", false)) listOf("gps", "network", "passive") else listOf("network", "gps", "passive")
        var best: android.location.Location? = null
        for (p in prefer) {
            runCatching {
                if (!lm.isProviderEnabled(p)) return@runCatching
                val loc = lm.getLastKnownLocation(p)
                if (loc != null && (best == null || loc.time > best!!.time)) best = loc
            }
        }
        val loc = best ?: return "No last known location available yet."
        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
        return "lat=${loc.latitude}\nlon=${loc.longitude}\naccuracy=${loc.accuracy}m\nprovider=${loc.provider}\nage=${ageSec}s\nmaps=https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
    }
}

class ClipboardReadTool : Tool {
    override val name = "clipboard_read"
    override val description = "Read the current clipboard text."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val cm = ctx.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return "(clipboard is empty)"
        if (clip.itemCount == 0) return "(clipboard is empty)"
        val text = clip.getItemAt(0).coerceToText(ctx.app).toString()
        return Util.truncate(text, 4000)
    }
}

class ClipboardWriteTool : Tool {
    override val name = "clipboard_write"
    override val description = "Copy text to the clipboard."
    override val parameters = JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val text = args.reqStr("text")
        val cm = ctx.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Atlas", text))
        return "copied ${text.length} chars to clipboard"
    }
}

class NotifyTool : Tool {
    override val name = "notify"
    override val description = "Post a notification to the phone's notification shade."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title","body"]}"""
    )
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        Notifications.simple(ctx.app, args.reqStr("title"), args.reqStr("body"))
        return "notification posted"
    }
}

class SpeakTool : Tool {
    override val name = "speak"
    override val description = "Speak text out loud through the phone's speaker (text-to-speech)."
    override val parameters = JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val text = args.reqStr("text")
        Atlas.tts.speak(text)
        return "speaking ${text.length} chars"
    }
}

class VibrateTool : Tool {
    override val name = "vibrate"
    override val description = "Vibrate the phone briefly."
    override val parameters = JSONObject("""{"type":"object","properties":{"ms":{"type":"integer","description":"duration in ms, default 400"}}}""")
    override val group = "device"

    @Suppress("DEPRECATION")
    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val ms = args.intOr("ms", 400).coerceIn(50, 5000).toLong()
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return "vibrated ${ms}ms"
    }
}

class TorchTool : Tool {
    override val name = "torch"
    override val description = "Turn the phone's flashlight (torch) on or off."
    override val parameters = JSONObject("""{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"]}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val on = args.boolOr("on", true)
        val cm = ctx.app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This device reports no flash unit."
        return runCatching {
            cm.setTorchMode(id, on)
            "torch ${if (on) "on" else "off"}"
        }.getOrElse { "ERROR: ${it.message ?: "could not control torch"}" }
    }
}

class VolumeTool : Tool {
    override val name = "volume"
    override val description = "Read or change a volume stream. Actions: get, set, up, down."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"action":{"type":"string","enum":["get","set","up","down"]},"stream":{"type":"string","enum":["music","ring","alarm","notification","call"],"description":"default music"},"level":{"type":"integer","description":"0-100 percent for set"}},"required":["action"]}"""
    )
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (args.str("stream", "music")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        return when (args.reqStr("action")) {
            "get" -> "level=$cur/$max (${cur * 100 / max}%)"
            "set" -> {
                val pct = (args.intOr("level", cur * 100 / max)).coerceIn(0, 100)
                val target = (pct * max / 100.0).toInt().coerceIn(0, max)
                am.setStreamVolume(stream, target, 0)
                "level set to $target/$max ($pct%)"
            }
            "up" -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                "up → ${am.getStreamVolume(stream)}/$max"
            }
            "down" -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                "down → ${am.getStreamVolume(stream)}/$max"
            }
            else -> "unknown action"
        }
    }
}

class OpenUrlTool : Tool {
    override val name = "open_url"
    override val description = "Open a URL in the phone's browser (or the matching app for the link)."
    override val parameters = JSONObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
    override val group = "apps"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val url = args.reqStr("url")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.app.startActivity(intent)
            "opened $url"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

class OpenAppTool : Tool {
    override val name = "open_app"
    override val description = "Launch an installed app by (partial) name, e.g. 'whatsapp', 'camera', 'settings'. Returns candidate list if the name is ambiguous."
    override val parameters = JSONObject("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
    override val group = "apps"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val q = args.reqStr("name").lowercase()
        val pm = ctx.app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        val matches = apps.filter {
            runCatching {
                val label = it.loadLabel(pm).toString()
                label.lowercase().contains(q) || it.activityInfo.packageName.lowercase().contains(q)
            }.getOrDefault(false)
        }
        if (matches.isEmpty()) return "No app matches '$q'."
        val exact = matches.firstOrNull { runCatching { it.loadLabel(pm).toString().lowercase() == q }.getOrDefault(false) }
        val chosen = exact ?: matches.first()
        if (exact == null && matches.size > 1) {
            return "Multiple matches: " + matches.take(6).joinToString("; ") {
                "${runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.activityInfo.packageName)} (${it.activityInfo.packageName})"
            }
        }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(chosen.activityInfo.packageName, chosen.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.app.startActivity(launch)
            "launched ${runCatching { chosen.loadLabel(pm).toString() }.getOrDefault(chosen.activityInfo.packageName)} (${chosen.activityInfo.packageName})"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

class ListAppsTool : Tool {
    override val name = "list_apps"
    override val description = "List installed apps that have a launcher icon, optionally filtered by name."
    override val parameters = JSONObject("""{"type":"object","properties":{"filter":{"type":"string"},"limit":{"type":"integer"}}}""")
    override val group = "apps"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val q = args.str("filter", "")?.lowercase() ?: ""
        val limit = args.intOr("limit", 60).coerceIn(1, 200)
        val pm = ctx.app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.activityInfo.packageName) to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { q.isBlank() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return "No matching apps."
        return apps.take(limit).joinToString("\n") { "- ${it.first} — ${it.second}" } +
            if (apps.size > limit) "\n(…${apps.size - limit} more)" else ""
    }
}

class OpenSettingsTool : Tool {
    override val name = "open_settings"
    override val description = "Open a settings screen: wifi, bluetooth, apps, accessibility, battery, display, sound, location, notifications, developer, home."
    override val parameters = JSONObject("""{"type":"object","properties":{"screen":{"type":"string"}},"required":["screen"]}""")
    override val group = "apps"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val action = when (args.reqStr("screen").lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "apps", "app" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications", "notification" -> "android.settings.NOTIFICATION_SETTINGS"
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "home" -> Settings.ACTION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return runCatching {
            ctx.app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened settings: $action"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

class ShareTextTool : Tool {
    override val name = "share_text"
    override val description = "Open the Android share sheet with text (send to another app)."
    override val parameters = JSONObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")
    override val group = "apps"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val text = args.reqStr("text")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return runCatching {
            ctx.app.startActivity(Intent.createChooser(send, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "share sheet opened"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

class ContactsSearchTool : Tool {
    override val name = "contacts_search"
    override val description = "Search phone contacts by name. Returns name + phone numbers. Requires the contacts permission."
    override val parameters = JSONObject("""{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}}}""")
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val q = args.str("query", "") ?: ""
        val limit = args.intOr("limit", 10).coerceIn(1, 50)
        if (ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission not granted. Ask the user to allow Contacts for Atlas in Android settings."
        }
        val out = ArrayList<String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        ctx.app.contentResolver.query(
            uri, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$q%"), null
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add("${c.getString(0)} — ${c.getString(1)}")
            }
        }
        return if (out.isEmpty()) "No contacts match '$q'." else out.joinToString("\n")
    }
}

class SmsTool : Tool {
    override val name = "sms_send"
    override val description = "Send an SMS text message. This really sends a message from the phone — use only when the user explicitly asked."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"to":{"type":"string","description":"phone number"},"body":{"type":"string"}},"required":["to","body"]}"""
    )
    override val group = "device"
    override val dangerous = true
    override val defaultEnabled = false

    @Suppress("DEPRECATION")
    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val to = args.reqStr("to")
        val body = args.reqStr("body")
        if (ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SMS permission not granted."
        }
        val sm = SmsManager.getDefault() ?: return "SmsManager unavailable"
        return runCatching {
            sm.sendTextMessage(to, null, body, null, null)
            "SMS queued to $to (${body.length} chars)"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

class ScreenshotTool : Tool {
    override val name = "screenshot"
    override val description =
        "Take a screenshot of the current screen (requires the accessibility service). Saves a PNG and returns its path — run analyze_image on it to actually see the content."
    override val parameters = JSONObject("""{"type":"object","properties":{}}""")
    override val group = "ui"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val svc = com.atlas.agent.core.a11y.AtlasA11yService.instance
            ?: return "Accessibility service is not enabled — screenshots are unavailable. Ask the user to enable Atlas in Settings > Accessibility."
        val path = svc.captureScreenshot()
        return path ?: "Screenshot failed (timed out or no display)."
    }
}

class AnalyzeImageTool : Tool {
    override val name = "analyze_image"
    override val description =
        "Look at an image file (photo, screenshot, downloaded picture) with the vision model and answer a question about it. Use this to read screenshots, describe photos, or extract text from images."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"path":{"type":"string","description":"absolute path or /sdcard path"},"question":{"type":"string","description":"what to find out; defaults to a full description"}},"required":["path"]}"""
    )
    override val group = "device"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val path = args.reqStr("path")
        val file = if (path.startsWith("/sdcard")) {
            java.io.File(Environment.getExternalStorageDirectory(), path.removePrefix("/sdcard").trimStart('/'))
        } else java.io.File(path)
        if (!file.exists()) return "No such file: ${file.absolutePath}"
        val mime = Util.guessMime(file.name)
        if (!mime.startsWith("image/")) return "Not an image file: ${file.name} ($mime)"
        if (file.length() > 8L * 1024 * 1024) return "Image too large (${Util.fmtBytes(file.length())}); max 8 MB."
        val q = args.str("question", "") ?: ""
        return runCatching {
            val answer = Atlas.llm.describeImage(file.absolutePath, mime, q)
            "[vision model: ${Atlas.settings.visionModel}]\n$answer"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

/** Tiny arithmetic evaluator (shunting-yard). */
object Calc {
    fun eval(expr: String): Double {
        val tokens = tokenize(expr)
        val rpn = toRpn(tokens)
        return evalRpn(rpn)
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) sb.append(s[i++])
                    out.add(sb.toString())
                }
                c.isLetter() -> {
                    val sb = StringBuilder()
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) sb.append(s[i++])
                    out.add(sb.toString())
                }
                c in "+-*/%^()," -> {
                    out.add(c.toString())
                    i++
                }
                else -> throw IllegalArgumentException("unexpected character '$c'")
            }
        }
        return out
    }

    private val prec = mapOf("+" to 1, "-" to 1, "*" to 2, "/" to 2, "%" to 2, "^" to 4, "u-" to 3)

    private fun toRpn(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        val ops = ArrayDeque<String>()
        var expectValue = true
        for (t in tokens) {
            when {
                t.toDoubleOrNull() != null || t == "pi" || t == "e" -> {
                    out.add(t)
                    expectValue = false
                }
                t == "(" -> {
                    ops.addLast(t)
                    expectValue = true
                }
                t == ")" -> {
                    while (ops.isNotEmpty() && ops.last() != "(") out.add(ops.removeLast())
                    if (ops.isEmpty()) throw IllegalArgumentException("unbalanced parentheses")
                    ops.removeLast()
                    expectValue = false
                }
                t == "," -> {
                    while (ops.isNotEmpty() && ops.last() != "(") out.add(ops.removeLast())
                    expectValue = true
                }
                t in prec -> {
                    val op = if (t == "-" && expectValue) "u-" else t
                    val p = prec[op]!!
                    val rightAssoc = op == "^" || op == "u-"
                    while (true) {
                        val top = ops.lastOrNull() ?: break
                        if (top == "(") break
                        val tp = prec[top] ?: break
                        if (if (rightAssoc) tp > p else tp >= p) out.add(ops.removeLast()) else break
                    }
                    ops.addLast(op)
                    expectValue = true
                }
                else -> {
                    // function name
                    ops.addLast(t)
                }
            }
        }
        while (ops.isNotEmpty()) {
            val op = ops.removeLast()
            if (op == "(") throw IllegalArgumentException("unbalanced parentheses")
            out.add(op)
        }
        return out
    }

    private fun evalRpn(rpn: List<String>): Double {
        val st = ArrayDeque<Double>()
        for (t in rpn) {
            when (t) {
                "+" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(a + b)
                }
                "-" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(a - b)
                }
                "*" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(a * b)
                }
                "/" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(a / b)
                }
                "%" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(a % b)
                }
                "^" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(Math.pow(a, b))
                }
                "u-" -> st.addLast(-st.removeLast())
                "pi" -> st.addLast(Math.PI)
                "e" -> st.addLast(Math.E)
                "sqrt" -> st.addLast(Math.sqrt(st.removeLast()))
                "abs" -> st.addLast(Math.abs(st.removeLast()))
                "sin" -> st.addLast(Math.sin(st.removeLast()))
                "cos" -> st.addLast(Math.cos(st.removeLast()))
                "tan" -> st.addLast(Math.tan(st.removeLast()))
                "log" -> st.addLast(Math.log(st.removeLast()))
                "log10" -> st.addLast(Math.log10(st.removeLast()))
                "exp" -> st.addLast(Math.exp(st.removeLast()))
                "floor" -> st.addLast(Math.floor(st.removeLast()))
                "ceil" -> st.addLast(Math.ceil(st.removeLast()))
                "round" -> st.addLast(Math.round(st.removeLast()).toDouble())
                "min" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(minOf(a, b))
                }
                "max" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(maxOf(a, b))
                }
                "pow" -> {
                    val b = st.removeLast(); val a = st.removeLast(); st.addLast(Math.pow(a, b))
                }
                else -> st.addLast(t.toDouble())
            }
        }
        if (st.size != 1) throw IllegalArgumentException("invalid expression")
        return st.removeLast()
    }
}
