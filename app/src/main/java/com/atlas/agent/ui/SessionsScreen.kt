package com.atlas.agent.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.core.util.Util

@Composable
fun SessionsScreen(onOpen: (Long) -> Unit, onNew: (Long) -> Unit) {
    val db = Atlas.db
    val version by db.version.collectAsStateWithLifecycle()
    val sessions = remember(version) {
        db.sessions().filter { it.title != MainActivity.AUTOMATION_SESSION }
    }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
        ) {
            item {
                Column {
                    Text("Chats", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (sessions.isEmpty()) "Nothing yet" else "${sessions.size} conversation${if (sessions.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "Start a chat and it will appear here — each one keeps its own history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sessions, key = { it.id }) { s ->
                WarmCard(
                    Modifier.fillMaxWidth().animateItem(),
                    onClick = { onOpen(s.id) },
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${s.msgCount} messages · ${Util.ago(s.updated)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { renaming = s.id to s.title }) {
                            Icon(Icons.Default.Edit, "rename", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { deleting = s.id }) {
                            Icon(Icons.Default.Delete, "delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { onNew(db.createSession("New chat")) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, "new chat") }
    }

    renaming?.let { (id, current) ->
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Rename chat", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    db.renameSession(id, text.ifBlank { "Chat" })
                    renaming = null
                }) { Text("Save", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete chat?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("The conversation and its messages are removed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    db.deleteSession(id)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}
