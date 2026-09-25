package com.atlas.agent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atlas.agent.core.store.MessageRow
import com.atlas.agent.ui.theme.AtlasGreen
import com.atlas.agent.ui.theme.AtlasMuted
import com.atlas.agent.ui.theme.AtlasRed
import java.io.File

// ------------------------------------------------------------------ markdown

private val inlineRe = Regex("(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(\\*[^*]+\\*)|(\\[[^\\]]+\\]\\([^)]+\\))")

private fun AnnotatedString.Builder.appendInline(line: String, codeBg: Color, link: Color) {
    var last = 0
    for (m in inlineRe.findAll(line)) {
        if (m.range.first > last) append(line.substring(last, m.range.first))
        val tok = m.value
        when {
            tok.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
            ) { append(tok.trim('`')) }

            tok.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(tok.removeSurrounding("**"))
            }

            tok.startsWith("[") -> {
                val t = tok.substringAfter('[').substringBefore(']')
                val u = tok.substringAfter('(').substringBeforeLast(')')
                withStyle(SpanStyle(color = link)) { append(t) }
                append(" ")
                withStyle(SpanStyle(color = link, fontSize = 11.sp)) { append(u) }
            }

            tok.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(tok.trim('*'))
            }
        }
        last = m.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    val muted = AtlasMuted
    val body = MaterialTheme.colorScheme.onSurface
    val annotated: AnnotatedString = remember(text) {
        buildAnnotatedString {
            var inCode = false
            for (line in text.split('\n')) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```")) {
                    inCode = !inCode
                    continue
                }
                if (inCode) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 12.sp)) {
                        append(line)
                    }
                    append('\n')
                    continue
                }
                when {
                    trimmed.startsWith("### ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)) {
                        append(trimmed.removePrefix("### "))
                    }

                    trimmed.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
                        append(trimmed.removePrefix("## "))
                    }

                    trimmed.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)) {
                        append(trimmed.removePrefix("# "))
                    }

                    trimmed.startsWith("> ") -> withStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic)) {
                        append(trimmed.removePrefix("> "))
                    }

                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        append("• ")
                        appendInline(trimmed.substring(2), codeBg, link)
                    }

                    trimmed.matches(Regex("^\\d+[.)] .*")) -> appendInline(trimmed, codeBg, link)

                    trimmed == "---" -> append("────────────")

                    else -> appendInline(line, codeBg, link)
                }
                append('\n')
            }
        }
    }
    SelectionContainer(modifier = modifier) {
        Text(text = annotated, color = body, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

// ------------------------------------------------------------------ messages

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer { Text(text, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
fun AssistantBubble(text: String, reasoning: String?, streaming: Boolean = false) {
    var showReasoning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (!reasoning.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { showReasoning = !showReasoning }.padding(vertical = 2.dp)
            ) {
                Text("thinking", color = AtlasMuted, fontSize = 11.sp)
                Icon(
                    if (showReasoning) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, tint = AtlasMuted, modifier = Modifier.size(14.dp)
                )
            }
            if (showReasoning) {
                Text(
                    reasoning, color = AtlasMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
            }
        }
        if (text.isNotBlank()) {
            MarkdownText(text)
        } else if (streaming) {
            Text("…", color = AtlasMuted, fontSize = 14.sp)
        }
    }
}

@Composable
fun ToolChip(name: String, result: String, ok: Boolean, args: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ok) "✓" else "✗", color = if (ok) AtlasGreen else AtlasRed, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    result.replace('\n', ' ').take(if (expanded) 400 else 60),
                    fontSize = 11.sp, color = AtlasMuted,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (expanded) {
                if (!args.isNullOrBlank()) {
                    Text("args: $args", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AtlasMuted, modifier = Modifier.padding(top = 4.dp))
                }
                Text(result.take(2000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun MessageView(row: MessageRow, onToolDetail: (MessageRow) -> Unit) {
    when (row.role) {
        "user" -> UserBubble(row.content)
        "assistant" -> AssistantBubble(row.content, row.reasoning)
        "tool" -> ToolChip(row.name ?: "tool", row.content, ok = !row.content.startsWith("ERROR") && !row.content.startsWith("DENIED"))
    }
}

// ------------------------------------------------------------------ images

@Composable
fun LocalImage(path: String, maxHeight: Int = 320) {
    val bmp = remember(path) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = path,
            modifier = Modifier.heightIn(max = maxHeight.dp).padding(vertical = 4.dp)
        )
    } else {
        Text("(image) $path", color = AtlasMuted, fontSize = 11.sp)
    }
}

// ------------------------------------------------------------------ small bits

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = AtlasMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
fun KeyValueLine(k: String, v: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = AtlasMuted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(v, fontSize = 12.sp, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ScrollRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun BoxedText(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
