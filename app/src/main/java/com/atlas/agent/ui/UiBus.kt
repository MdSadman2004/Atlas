package com.atlas.agent.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.atlas.agent.core.agent.Attachment
import com.atlas.agent.core.util.Util
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Text/images shared into Atlas from other apps (ACTION_SEND). */
object ShareBus {
    val text = MutableStateFlow<String?>(null)
    val images = MutableStateFlow<List<Attachment>>(emptyList())

    /** When true the next shared text is submitted immediately (automation / device tests). */
    val autoSend = MutableStateFlow(false)

    fun copyToCache(ctx: Context, uri: Uri): Attachment? = runCatching {
        var name = "shared-${System.currentTimeMillis()}.img"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        val file = File(ctx.cacheDir, Util.safeName(name))
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        }
        Attachment(file.absolutePath, ctx.contentResolver.getType(uri) ?: Util.guessMime(name))
    }.getOrNull()
}
