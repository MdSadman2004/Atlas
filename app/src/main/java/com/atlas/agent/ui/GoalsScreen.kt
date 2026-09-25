package com.atlas.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.autonomy.GoalScheduler
import com.atlas.agent.core.util.Util
import com.atlas.agent.ui.theme.AtlasMuted

@Composable
fun GoalsScreen() {
    val ctx = LocalContext.current
    val db = Atlas.db
    val version by db.version.collectAsStateWithLifecycle()
    val goals = remember(version) { db.goals() }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Autonomous goals run in the background on a schedule, even when Atlas is closed. " +
                        "Check interval ticks every ${Atlas.settings.autonomyIntervalMinutes} min; each goal runs when due.",
                    color = AtlasMuted, fontSize = 11.sp,
                )
            }
            if (goals.isEmpty()) {
                item { Text("No goals yet. Create one, or just tell Atlas in chat: “every morning at 8, summarise the news”.", color = AtlasMuted, fontSize = 12.sp) }
            }
            items(goals, key = { it.id }) { g ->
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.title, fontSize = 14.sp)
                                Text(
                                    "every ${g.intervalMinutes} min · ran ${g.runCount}× · last ${Util.ago(g.lastRun)} · next ${Util.fmtDateTime(g.nextRun)}",
                                    color = AtlasMuted, fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = g.enabled,
                                onCheckedChange = { on ->
                                    db.updateGoal(g.id, enabled = on)
                                    GoalScheduler(ctx).scheduleAll()
                                },
                            )
                        }
                        Text(g.instruction, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        g.lastResult?.let {
                            Text("last result: ${it.take(300)}", color = AtlasMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { GoalScheduler(ctx).runNow(g.id) }) {
                                Icon(Icons.Default.PlayArrow, "run now", tint = MaterialTheme.colorScheme.primary)
                            }
                            Text("run now", color = AtlasMuted, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { deleting = g.id }) {
                                Icon(Icons.Default.Delete, "delete", tint = AtlasMuted)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) { Icon(Icons.Default.Add, "new goal") }
    }

    if (creating) {
        var title by remember { mutableStateOf("") }
        var instruction by remember { mutableStateOf("") }
        var interval by remember { mutableStateOf("60") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New autonomous goal") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(
                        value = instruction, onValueChange = { instruction = it },
                        label = { Text("Instruction (what to do each run)") }, minLines = 3,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = interval, onValueChange = { interval = it.filter { c -> c.isDigit() } },
                        label = { Text("Interval (minutes, 15-720)") }, singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mins = interval.toIntOrNull()?.coerceIn(15, 720) ?: 60
                    if (title.isNotBlank() && instruction.isNotBlank()) {
                        db.insertGoal(title, instruction, mins)
                        GoalScheduler(ctx).scheduleAll()
                        creating = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete goal?") },
            text = { Text("The goal and its schedule are removed. Its chat history stays.") },
            confirmButton = {
                TextButton(onClick = {
                    db.deleteGoal(id)
                    GoalScheduler(ctx).scheduleAll()
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
