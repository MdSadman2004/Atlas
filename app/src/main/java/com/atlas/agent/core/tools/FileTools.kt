package com.atlas.agent.core.tools

import android.os.Environment
import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.agent.ToolContext
import com.atlas.agent.core.agent.boolOr
import com.atlas.agent.core.agent.intOr
import com.atlas.agent.core.agent.reqStr
import com.atlas.agent.core.agent.str
import com.atlas.agent.core.util.Util
import org.json.JSONObject
import java.io.File

private fun resolvePath(ctx: ToolContext, path: String): File {
    val p = path.trim()
    return when {
        p.isEmpty() || p == "~" -> ctx.filesDir
        p.startsWith("~/") -> File(ctx.filesDir, p.removePrefix("~/"))
        p.startsWith("/sdcard") -> File(Environment.getExternalStorageDirectory(), p.removePrefix("/sdcard").trimStart('/'))
        p.startsWith("/storage/emulated/0") -> File(Environment.getExternalStorageDirectory(), p.removePrefix("/storage/emulated/0").trimStart('/'))
        p.startsWith("/") -> File(p)
        else -> File(ctx.filesDir, p)
    }
}

class FsListTool : Tool {
    override val name = "fs_list"
    override val description =
        "List files in a directory. Use '~' or a relative path for Atlas's private folder, '/sdcard/Download' etc. for shared storage. Shows name, size and modified time."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"path":{"type":"string","description":"directory; default '~'"},"recursive":{"type":"boolean"}},"required":["path"]}"""
    )
    override val group = "files"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val dir = resolvePath(ctx, args.reqStr("path"))
        if (!dir.exists()) return "No such directory: ${dir.absolutePath}"
        if (!dir.isDirectory) return "${dir.absolutePath} is a file (${Util.fmtBytes(dir.length())})"
        val recursive = args.boolOr("recursive", false)
        val sb = StringBuilder("${dir.absolutePath}:\n")
        var count = 0
        val max = 200
        fun walk(d: File, depth: Int) {
            val items = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (f in items) {
                if (count >= max) return
                count++
                val indent = "  ".repeat(depth)
                if (f.isDirectory) {
                    sb.append("$indent${f.name}/\n")
                    if (recursive && depth < 3) walk(f, depth + 1)
                } else {
                    sb.append("$indent${f.name}  (${Util.fmtBytes(f.length())}, ${Util.fmtDateTime(f.lastModified())})\n")
                }
            }
        }
        walk(dir, 0)
        if (count >= max) sb.append("…(list truncated)\n")
        return sb.toString()
    }
}

class FsReadTool : Tool {
    override val name = "fs_read"
    override val description = "Read a text file (max 512 KB). Returns numbered-free raw content. For images use analyze_image; for huge files use fetch_url or split reads."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"path":{"type":"string"},"max_bytes":{"type":"integer"}},"required":["path"]}"""
    )
    override val group = "files"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val f = resolvePath(ctx, args.reqStr("path"))
        if (!f.exists()) return "No such file: ${f.absolutePath}"
        if (!f.isFile) return "Not a file: ${f.absolutePath}"
        val max = args.intOr("max_bytes", 512 * 1024)
        val mime = Util.guessMime(f.name)
        if (mime.startsWith("image/")) return "Binary image (${Util.fmtBytes(f.length())}). Use analyze_image to see it."
        return runCatching { Util.truncate(Util.readTextFile(f, max), 60000) }
            .getOrElse { "ERROR: ${it.message}" }
    }
}

class FsWriteTool : Tool {
    override val name = "fs_write"
    override val description = "Write (or append) text to a file, creating directories as needed. Note: Android blocks writes to most shared-storage folders; use '~/' or /sdcard/Download for user-visible files."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"append":{"type":"boolean"}},"required":["path","content"]}"""
    )
    override val group = "files"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val f = resolvePath(ctx, args.reqStr("path"))
        val content = args.reqStr("content")
        val append = args.boolOr("append", false)
        return runCatching {
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            "wrote ${Util.fmtBytes(f.length())} to ${f.absolutePath}"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

class FsMkdirTool : Tool {
    override val name = "fs_mkdir"
    override val description = "Create a directory (and parents)."
    override val parameters = JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
    override val group = "files"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val f = resolvePath(ctx, args.reqStr("path"))
        return if (f.mkdirs() || f.isDirectory) "ok: ${f.absolutePath}" else "ERROR: could not create ${f.absolutePath}"
    }
}

class FsDeleteTool : Tool {
    override val name = "fs_delete"
    override val description = "Delete a file or directory (with recursive=true for non-empty directories)."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"path":{"type":"string"},"recursive":{"type":"boolean"}},"required":["path"]}"""
    )
    override val group = "files"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val f = resolvePath(ctx, args.reqStr("path"))
        if (!f.exists()) return "No such path: ${f.absolutePath}"
        val recursive = args.boolOr("recursive", false)
        return runCatching {
            val ok = if (recursive) f.deleteRecursively() else f.delete()
            if (ok) "deleted ${f.absolutePath}" else "ERROR: delete failed (non-empty directory? use recursive=true)"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}

    /** Convenience for other tools that must resolve a user-supplied path. */
    internal fun resolveUserPath(ctx: ToolContext, path: String): File = resolvePath(ctx, path)
