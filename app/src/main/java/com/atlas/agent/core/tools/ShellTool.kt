package com.atlas.agent.core.tools

import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.agent.ToolContext
import com.atlas.agent.core.agent.intOr
import com.atlas.agent.core.agent.reqStr
import com.atlas.agent.core.agent.str
import com.atlas.agent.core.util.Util
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ShellTool : Tool {
    override val name = "shell"
    override val description =
        "Run a shell command as the Atlas app (no root). Returns exit code plus stdout/stderr. " +
            "Good for: ls/cat/df/getprop/ping/ip/ps (own processes)/toybox utilities. " +
            "Usually BLOCKED: am, input, pm, dumpsys, settings, logcat of other apps — use the dedicated tools instead."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"cmd":{"type":"string"},"timeout_s":{"type":"integer","description":"default 20, max 120"},"cwd":{"type":"string"}},"required":["cmd"]}"""
    )
    override val group = "shell"
    override val dangerous = true

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val cmd = args.reqStr("cmd")
        val timeout = args.intOr("timeout_s", 20).coerceIn(1, 120).toLong()
        val cwd = args.str("cwd", null)?.let { resolveUserPath(ctx, it) } ?: ctx.filesDir
        if (!cwd.isDirectory) return "cwd not a directory: ${cwd.absolutePath}"

        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
        pb.directory(cwd)
        pb.redirectErrorStream(true)
        pb.environment()["PATH"] = "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin"
        pb.environment()["HOME"] = ctx.filesDir.absolutePath
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val process = pb.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (output.length < 200_000) output.appendLine(line)
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(400)
            return "TIMEOUT after ${timeout}s\npartial output:\n${Util.truncate(output.toString(), 4000)}"
        }
        reader.join(800)
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        return "exit=$code\n${Util.truncate(output.toString(), 8000)}".trimEnd()
    }
}
