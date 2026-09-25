package com.atlas.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.autonomy.GoalScheduler
import com.atlas.agent.core.util.Util

@Composable
fun GoalsScreen() {
    val ctx = LocalContext.current
    val db = Atlas.db
    val version by db.version.collectAsStateWithLifecycle()
    val goals = remember(version) { db.goals() }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
        ) {
            item {
                Column {
                    Text("Goals", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Standing objectives Atlas pursues on a schedule — in the background, even when the app is closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (goals.isEmpty()) {
                item {
                    Text(
                        "No goals yet. Ask in chat — “every morning at 8, summarise tech news” — or create one with the + button.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(goals, key = { it.id }) { g ->
                WarmCard(Modifier.fillMaxWidth().animateItem()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "every ${g.intervalMinutes} min · ran ${g.runCount}× · next ${Util.fmtDateTime(g.nextRun)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = g.enabled,
                                onCheckedChange = { on ->
                                    db.updateGoal(g.id, enabled = on)
                                    GoalScheduler(ctx).scheduleAll()
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
                        Spacer(Modifier.height(10.dp))
                        Text(
                            g.instruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        g.lastResult?.let {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(active = false, tint = MaterialTheme.colorScheme.secondary, size = 6)
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    it.take(220),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                shape = CircleShape,
                                modifier = Modifier.clickable { GoalScheduler(ctx).runNow(g.id) },
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("run now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("last ${Util.ago(g.lastRun)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { deleting = g.id }) {
                                Icon(Icons.Default.Delete, "delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, "new goal") }
    }

    if (creating) {
        var title by remember { mutableStateOf("") }
        var instruction by remember { mutableStateOf("") }
        var interval by remember { mutableStateOf("60") }
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("New goal", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Title") }, singleLine = true,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = instruction, onValueChange = { instruction = it },
                        label = { Text("What to do each run") }, minLines = 3,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = interval, onValueChange = { interval = it.filter { c -> c.isDigit() } },
                        label = { Text("Every … minutes (15–720)") }, singleLine = true,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
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
                }) { Text("Create", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete goal?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("The goal and its schedule are removed. Its chat history stays.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    db.deleteGoal(id)
                    GoalScheduler(ctx).scheduleAll()
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}
