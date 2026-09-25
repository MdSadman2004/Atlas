package com.atlas.agent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.autonomy.GoalScheduler
import com.atlas.agent.core.llm.ModelInfo
import com.atlas.agent.core.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    var sub by remember { mutableStateOf<String?>(null) }
    when (sub) {
        "tools" -> SubScreen("Tools", { sub = null }) { ToolsScreen() }
        "memory" -> SubScreen("Memory", { sub = null }) { MemoryScreen() }
        "skills" -> SubScreen("Skills", { sub = null }) { SkillsScreen() }
        else -> MainSettings(onOpen = { sub = it })
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("← back", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        content()
    }
}

/** Small-caps section heading + hairline card. */
@Composable
private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionTitle(title)
        WarmCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), content = content) }
    }
}

@Composable
private fun OutlinePill(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MainSettings(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val s = Atlas.settings

    var baseUrl by remember { mutableStateOf(s.baseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var aboutUser by remember { mutableStateOf(s.aboutUser) }
    var systemExtra by remember { mutableStateOf(s.systemExtra) }
    var status by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    val dbVersion by Atlas.db.version.collectAsStateWithLifecycle()

    LaunchedEffect(reload) {
        models = withContext(Dispatchers.IO) { runCatching { Atlas.llm.models() }.getOrDefault(emptyList()) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        status = result.entries.joinToString(", ") { "${it.key.substringAfterLast('.')} ${if (it.value) "granted" else "denied"}" }
    }
    val a11yOn = com.atlas.agent.core.a11y.AtlasA11yService.isEnabled(ctx)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 60.dp),
    ) {
        item {
            Column {
                Text("Setup", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connection, autonomy, permissions and the library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingCard("Connection") {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API key") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinePill("Save & test") {
                        s.baseUrl = baseUrl
                        s.apiKey = apiKey
                        status = "saved — checking…"
                        reload++
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Self-test") {
                        s.baseUrl = baseUrl
                        s.apiKey = apiKey
                        val sid = Atlas.db.createSession("self-test")
                        com.atlas.agent.core.service.AgentService.start(ctx)
                        com.atlas.agent.core.agent.RunController.start(
                            sid,
                            "Self-test: call the now tool, then reply with one short line confirming you are online.",
                            emptyList(),
                        )
                        status = "self-test running — watch the Chat tab"
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = false, tint = MaterialTheme.colorScheme.secondary, size = 6)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "model: ${s.model}  ·  locked  ·  reasoning_effort=high",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "vision: ${s.visionModel}   ·   ${models.size} models on this key",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        item {
            SettingCard("Behaviour") {
                Text("Approvals", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "off" to "Auto-approve — finish the job without stopping (default)",
                    "smart" to "Ask before risky tools (shell, deletes, taps, SMS)",
                    "manual" to "Ask before every tool call",
                ).forEach { (mode, blurb) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { s.approvals = mode }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = s.approvals == mode,
                            onClick = { s.approvals = mode },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Column {
                            Text(mode, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SliderRow("Max steps per turn", s.maxSteps.toFloat(), 1f..60f, s.maxSteps.toString()) { s.maxSteps = it.toInt() }
                SliderRow("Max tokens per reply", s.maxTokens.toFloat(), 512f..16000f, s.maxTokens.toString()) { s.maxTokens = it.toInt() }
                SliderRow("History window", s.historyLimit.toFloat(), 6f..120f, "${s.historyLimit} messages") { s.historyLimit = it.toInt() }
                SliderRow("Temperature", s.temperature, 0f..1.5f, "%.2f".format(s.temperature)) { s.temperature = it }
                Spacer(Modifier.height(6.dp))
                SwitchRow("Stream replies", s.streaming) { s.streaming = it }
                SwitchRow("Auto-extract memories", s.autoMemory) { s.autoMemory = it }
                SwitchRow("AI-generated chat titles", s.titleModel) { s.titleModel = it }
            }
        }

        item {
            SettingCard("Appearance") {
                listOf("dark" to "Dark warm (default)", "light" to "Light warm").forEach { (mode, blurb) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            s.theme = mode
                            (ctx as? android.app.Activity)?.recreate()
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = s.theme == mode,
                            onClick = {
                                s.theme = mode
                                (ctx as? android.app.Activity)?.recreate()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Text(blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        item {
            SettingCard("About you") {
                Text(
                    "Kept in every conversation's context.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = aboutUser, onValueChange = { aboutUser = it; s.aboutUser = it },
                    label = { Text("About you") }, minLines = 4,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = systemExtra, onValueChange = { systemExtra = it; s.systemExtra = it },
                    label = { Text("Standing instructions") }, minLines = 3,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SettingCard("Voice") {
                SwitchRow("Text-to-speech available", s.ttsEnabled) { s.ttsEnabled = it }
                SwitchRow("Speak replies out loud", s.speakReplies) { s.speakReplies = it }
                SwitchRow("Send after voice input", s.voiceAutoSend) { s.voiceAutoSend = it }
            }
        }

        item {
            SettingCard("Autonomy") {
                SwitchRow("Autonomous goals enabled", s.autonomyEnabled) {
                    s.autonomyEnabled = it
                    GoalScheduler(ctx).scheduleAll()
                }
                SliderRow("Check interval", s.autonomyIntervalMinutes.toFloat(), 15f..360f, "${s.autonomyIntervalMinutes} min") {
                    s.autonomyIntervalMinutes = it.toInt()
                    GoalScheduler(ctx).scheduleAll()
                }
                SwitchRow("Notify when a run finishes", s.notifyOnComplete) { s.notifyOnComplete = it }
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinePill("Reschedule") {
                        GoalScheduler(ctx).scheduleAll()
                        status = "autonomy rescheduled"
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Battery exemption") {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingCard("Permissions & access") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = a11yOn, tint = if (a11yOn) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (a11yOn) "screen control enabled" else "screen control off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinePill("Grant phone permissions") {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.CAMERA,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Screen control") {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Screen control lets Atlas read the screen and tap, type and swipe for you. " +
                        "On Android 13+ sideloaded apps may need “Allow restricted settings” in App info first.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingCard("Library") {
                Row {
                    OutlinePill("Tools · ${Atlas.tools.all().size}") { onOpen("tools") }
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Memory") { onOpen("memory") }
                    Spacer(Modifier.width(8.dp))
                    OutlinePill("Skills") { onOpen("skills") }
                }
            }
        }

        item {
            SettingCard("About") {
                KeyValueLine("Version", "1.0.0 (${com.atlas.agent.BuildConfig.VERSION_CODE})")
                KeyValueLine("Tools", "${Atlas.tools.all().size} registered")
                KeyValueLine("Models", "${models.size} available on this key")
                val st = Atlas.db.stats()
                KeyValueLine("Data", "${st.first} messages · ${st.second} runs · ${st.third} tokens")
                KeyValueLine("Approvals", s.approvals)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

// ------------------------------------------------------------------ library screens

@Composable
fun ToolsScreen() {
    var bump by remember { mutableStateOf(0) }
    val s = Atlas.settings
    val tools = remember(bump) { Atlas.tools.all() }
    val grouped = tools.groupBy { it.group }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (group, list) ->
            item { SectionTitle(group) }
            items(list) { t ->
                WarmCard(Modifier.fillMaxWidth().animateItem()) {
                    Row(Modifier.padding(start = 15.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                if (t.dangerous) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("risky", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(t.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = s.toolEnabled(t.name, t.defaultEnabled),
                            onCheckedChange = {
                                s.setToolEnabled(t.name, it)
                                bump++
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen() {
    val dbVersion by Atlas.db.version.collectAsStateWithLifecycle()
    val rows = remember(dbVersion) { Atlas.memory.all(300) }
    var adding by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinePill("Add fact") { adding = true }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${rows.size} remembered · injected into every chat",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows, key = { it.id }) { m ->
            WarmCard(Modifier.fillMaxWidth().animateItem()) {
                Row(Modifier.padding(start = 15.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(3.dp))
                        Text("importance ${m.importance} · used ${m.uses}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { Atlas.memory.forget(m.id) }) {
                        Text("forget", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("New memory", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = newText, onValueChange = { newText = it },
                    label = { Text("Fact worth remembering") }, minLines = 2,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        Atlas.memory.add(newText.trim(), 2)
                        newText = ""
                        adding = false
                    }
                }) { Text("Save", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

@Composable
fun SkillsScreen() {
    val dbVersion by Atlas.db.version.collectAsStateWithLifecycle()
    val skills = remember(dbVersion) { Atlas.skills.list() }
    var viewing by remember { mutableStateOf<String?>(null) }
    var viewingBody by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinePill("New skill") { creating = true }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${skills.size} skills · loaded on demand",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(skills, key = { it.name }) { sk ->
            WarmCard(Modifier.fillMaxWidth().animateItem()) {
                Column(Modifier.padding(15.dp)) {
                    Text(sk.name, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(3.dp))
                    Text(sk.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = {
                            viewing = sk.name
                            viewingBody = sk.file.readText()
                        }) { Text("view", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        TextButton(onClick = { Atlas.skills.delete(sk.name) }) {
                            Text("delete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    viewingBody?.let { body ->
        AlertDialog(
            onDismissRequest = { viewingBody = null; viewing = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text(viewing ?: "skill", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
                    Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { viewingBody = null; viewing = null }) { Text("Close", color = MaterialTheme.colorScheme.primary) } },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("New skill", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("name") }, singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("one-line description") }, singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newBody, onValueChange = { newBody = it }, label = { Text("body (markdown steps)") }, minLines = 5, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newBody.isNotBlank()) {
                        Atlas.skills.write(newName, newDesc.ifBlank { "custom skill" }, newBody)
                        newName = ""; newDesc = ""; newBody = ""
                        creating = false
                    }
                }) { Text("Save", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}
