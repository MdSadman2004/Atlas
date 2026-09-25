package com.atlas.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.core.util.Util

@Composable
fun ActivityScreen() {
    val db = Atlas.db
    val version by db.version.collectAsStateWithLifecycle()
    val live by RunController.live.collectAsStateWithLifecycle()
    val runs = remember(version) { db.runs(60) }
    val stats = remember(version) { db.stats() }
    var expandedRun by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 60.dp),
    ) {
        item {
            Column {
                Text("Activity", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${stats.second} runs · ${stats.first} messages · ${stats.third} tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        live?.let { run ->
            item {
                WarmCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(active = true)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                run.status.ifBlank { "working" },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (run.toolLog.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                run.toolLog.last().take(160),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        items(runs, key = { it.id }) { r ->
            WarmCard(Modifier.fillMaxWidth().animateItem()) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            active = false,
                            tint = when (r.status) {
                                "done" -> MaterialTheme.colorScheme.secondary
                                "error" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                            size = 6,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "#${r.id}  ${r.status}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            Util.fmtDateTime(r.started),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        buildString {
                            append("steps ${r.steps} · tokens ${r.tokens}")
                            r.goalId?.let { append(" · goal #$it") }
                            if (r.ended > 0) append(" · ${(r.ended - r.started) / 1000}s")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    r.summary?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it.take(240), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (expandedRun == r.id) "hide tool calls" else "show tool calls",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expandedRun = if (expandedRun == r.id) null else r.id },
                    )
                    AnimatedVisibility(
                        visible = expandedRun == r.id,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(Modifier.padding(top = 8.dp)) {
                            val evs = db.events(r.id)
                            if (evs.isEmpty()) {
                                Text("no tool calls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            evs.forEach { e ->
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                    StatusDot(
                                        active = false,
                                        tint = if (e.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                        size = 5,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "${e.name}(${(e.args ?: "").replace('\n', ' ').take(90)})",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            (e.result ?: "").replace('\n', ' ').take(150),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
