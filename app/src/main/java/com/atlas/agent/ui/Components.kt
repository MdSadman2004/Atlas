package com.atlas.agent.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.atlas.agent.ui.theme.Motion
import com.atlas.agent.ui.theme.Sage
import com.atlas.agent.ui.theme.WarmRed
import java.io.File

// ------------------------------------------------------------------ primitives

/** A hairline-bordered warm card — the workhorse surface of the whole app. */
@Composable
fun WarmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) { content() }
}

/** Small status dot; pulses softly while [active]. */
@Composable
fun StatusDot(active: Boolean, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 7) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(size.dp)
            .scale(if (active) pulse else 1f)
            .background(if (active) tint else tint.copy(alpha = 0.35f), CircleShape)
    )
}

/** Compact pill used for status ("working", "idle") and meta info. */
@Composable
fun StatusPill(text: String, active: Boolean = false) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SuggestionChip(text: String, onClick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick(text) },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** Three dots breathing while the model is thinking. */
@Composable
fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val scale by transition.animateFloat(
                initialValue = 0.6f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(700, delayMillis = i * 140, easing = Motion.easeOut),
                    RepeatMode.Reverse,
                ),
                label = "d$i",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(6.dp)
                    .scale(scale)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f), CircleShape)
            )
        }
    }
}

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

            tok.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(tok.removeSurrounding("**"))
            }

            tok.startsWith("[") -> {
                val t = tok.substringAfter('[').substringBefore(']')
                val u = tok.substringAfter('(').substringBeforeLast(')')
                withStyle(SpanStyle(color = link)) { append(t) }
                append("  ")
                withStyle(SpanStyle(color = link.copy(alpha = 0.7f), fontSize = 11.sp)) { append(u) }
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val body = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
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
                    trimmed.startsWith("### ") -> withStyle(
                        SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    ) { append(trimmed.removePrefix("### ")) }

                    trimmed.startsWith("## ") -> withStyle(
                        SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    ) { append(trimmed.removePrefix("## ")) }

                    trimmed.startsWith("# ") -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 19.sp)
                    ) { append(trimmed.removePrefix("# ")) }

                    trimmed.startsWith("> ") -> withStyle(
                        SpanStyle(color = muted, fontStyle = FontStyle.Italic)
                    ) { append(trimmed.removePrefix("> ")) }

                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        append("— ")
                        appendInline(trimmed.substring(2), codeBg, link)
                    }

                    trimmed.matches(Regex("^\\d+[.)] .*")) -> appendInline(trimmed, codeBg, link)

                    trimmed == "---" -> withStyle(SpanStyle(color = outline)) {
                        append("· · ·")
                    }

                    else -> appendInline(line, codeBg, link)
                }
                append('\n')
            }
        }
    }
    SelectionContainer(modifier = modifier) {
        Text(
            text = annotated,
            color = body,
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = 23.sp,
        )
    }
}

// ------------------------------------------------------------------ messages

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(20.dp, 20.dp, 8.dp, 20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    RoundedCornerShape(20.dp, 20.dp, 8.dp, 20.dp),
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
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
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showReasoning = !showReasoning }
                    .padding(vertical = 3.dp),
            ) {
                StatusDot(active = false, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 5)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showReasoning) "hide reasoning" else "reasoning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Default.KeyboardArrowDown, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(13.dp)
                        .scale(if (showReasoning) -1f else 1f),
                )
            }
            AnimatedVisibility(visible = showReasoning, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.padding(start = 2.dp, top = 2.dp, bottom = 8.dp)) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .heightIn(min = 18.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), CircleShape)
                    )
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        if (text.isNotBlank()) {
            MarkdownText(text)
        } else if (streaming) {
            TypingDots()
        }
    }
}

@Composable
fun ToolChip(name: String, result: String, ok: Boolean, args: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    val tint = if (ok) MaterialTheme.colorScheme.secondary else WarmRed
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(active = false, tint = tint, size = 6)
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                result.replace('\n', ' ').trim().take(if (expanded) 4000 else 64),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 6.dp)) {
                if (!args.isNullOrBlank()) {
                    Text(
                        "args",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        args,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    result.take(2500),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MessageView(row: MessageRow, onToolDetail: (MessageRow) -> Unit) {
    when (row.role) {
        "user" -> UserBubble(row.content)
        "assistant" -> AssistantBubble(row.content, row.reasoning)
        "tool" -> ToolChip(
            row.name ?: "tool",
            row.content,
            ok = !row.content.startsWith("ERROR") && !row.content.startsWith("DENIED"),
        )
    }
}

// ------------------------------------------------------------------ images & bits

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
            modifier = Modifier
                .heightIn(max = maxHeight.dp)
                .clip(RoundedCornerShape(16.dp))
                .padding(vertical = 4.dp),
        )
    } else {
        Text("(image) " + File(path).name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun KeyValueLine(k: String, v: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(116.dp))
        Text(v, style = MaterialTheme.typography.bodySmall, color = valueColor, modifier = Modifier.weight(1f))
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
