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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.agent.core.Atlas
import com.atlas.agent.ui.theme.AtlasMuted

@Composable
fun SessionsScreen(onOpen: (Long) -> Unit, onNew: (Long) -> Unit) {
    val db = Atlas.db
    val version by db.version.collectAsStateWithLifecycle()
    val sessions = remember(version) { db.sessions() }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sessions.isEmpty()) {
                item { Text("No chats yet.", color = AtlasMuted, fontSize = 13.sp) }
            }
            items(sessions, key = { it.id }) { s ->
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) },
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 14.sp)
                            Text(
                                "${s.msgCount} messages · ${com.atlas.agent.core.util.Util.ago(s.updated)}",
                                color = AtlasMuted, fontSize = 11.sp,
                            )
                        }
                        IconButton(onClick = { renaming = s.id to s.title }) {
                            Icon(Icons.Default.Edit, "rename", tint = AtlasMuted)
                        }
                        IconButton(onClick = { deleting = s.id }) {
                            Icon(Icons.Default.Delete, "delete", tint = AtlasMuted)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        FloatingActionButton(
            onClick = { onNew(db.createSession("New chat")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) { Icon(Icons.Default.Add, "new chat") }
    }

    renaming?.let { (id, current) ->
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    db.renameSession(id, text.ifBlank { "Chat" })
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text("This removes the conversation and its messages.") },
            confirmButton = {
                TextButton(onClick = {
                    db.deleteSession(id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
