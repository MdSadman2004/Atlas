package com.atlas.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.agent.RunController
import com.atlas.agent.core.util.Util
import com.atlas.agent.ui.theme.AtlasMuted

@Composable
fun ActivityScreen() {
    val db = Atlas.db
    val version by db.version.collectAsStateWithLifecycle()
    val live by RunController.live.collectAsStateWithLifecycle()
    val runs = remember(version) { db.runs(60) }
    val events = remember(version) { db.recentEvents(80) }
    val stats = remember(version) { db.stats() }
    var expandedRun by remember { mutableStateOf<Long?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                "messages ${stats.first} · runs ${stats.second} · tokens ${stats.third}",
                color = AtlasMuted, fontSize = 11.sp,
            )
        }
        live?.let { run ->
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("running now — ${run.status}", fontSize = 12.sp)
                        if (run.toolLog.isNotEmpty()) {
                            Text(run.toolLog.last(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        items(runs, key = { it.id }) { r ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row {
                        Text("#${r.id} ${r.status}", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(Util.fmtDateTime(r.started), color = AtlasMuted, fontSize = 11.sp)
                    }
                    Text(
                        "steps ${r.steps} · tokens ${r.tokens}${r.goalId?.let { " · goal #$it" } ?: ""}" +
                            if (r.ended > 0) " · ${(r.ended - r.started) / 1000}s" else "",
                        color = AtlasMuted, fontSize = 11.sp,
                    )
                    r.summary?.let { Text(it.take(200), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        if (expandedRun == r.id) "hide tool calls" else "show tool calls",
                        color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp).clickable { expandedRun = if (expandedRun == r.id) null else r.id },
                    )
                    if (expandedRun == r.id) {
                        Column(Modifier.padding(top = 6.dp)) {
                            val evs = db.events(r.id)
                            if (evs.isEmpty()) Text("(no tool calls)", color = AtlasMuted, fontSize = 11.sp)
                            evs.forEach { e ->
                                Text(
                                    "${if (e.ok) "✓" else "✗"} ${e.name}(${e.args?.replace('\n', ' ')?.take(90) ?: ""})",
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                )
                                Text("   → ${e.result?.replace('\n', ' ')?.take(140) ?: ""}", color = AtlasMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
