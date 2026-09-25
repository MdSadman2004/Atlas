package com.atlas.agent.core.skills

import android.app.Application
import com.atlas.agent.core.llm.ApiMessage
import com.atlas.agent.core.llm.LlmClient
import java.io.File

data class SkillInfo(val name: String, val description: String, val file: File)

/**
 * Skills are markdown files with a small frontmatter block, stored in filesDir/skills/.
 * The agent sees name + description in its system prompt and loads the body on demand
 * (progressive disclosure, same idea as Hermes skills).
 */
class SkillStore(private val app: Application) {

    val dir: File get() = File(app.filesDir, "skills").also { if (!it.exists()) it.mkdirs() }

    fun list(): List<SkillInfo> = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
        ?.map { f ->
            val (name, desc) = parseFrontmatter(f)
            SkillInfo(name.ifBlank { f.nameWithoutExtension }, desc, f)
        }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun read(name: String): String? {
        val f = find(name) ?: return null
        return f.readText()
    }

    fun write(name: String, description: String, body: String): File {
        val slug = name.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifBlank { "skill" }
        val f = File(dir, "$slug.md")
        val content = buildString {
            append("---\n")
            append("name: ").append(name.replace('\n', ' ')).append('\n')
            append("description: ").append(description.replace('\n', ' ')).append('\n')
            append("---\n\n")
            append(body.trim()).append('\n')
        }
        f.writeText(content)
        return f
    }

    fun delete(name: String): Boolean = find(name)?.delete() ?: false

    fun promptBlock(): String {
        val items = list()
        if (items.isEmpty()) return ""
        return items.joinToString("\n") { "- ${it.name} — ${it.description}" }
    }

    private fun find(name: String): File? {
        val slug = name.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')
        return list().firstOrNull { it.name.equals(name, ignoreCase = true) || it.file.nameWithoutExtension == slug }?.file
    }

    private fun parseFrontmatter(f: File): Pair<String, String> {
        var name = ""
        var desc = ""
        runCatching {
            val lines = f.readLines()
            if (lines.firstOrNull()?.trim() == "---") {
                for (line in lines.drop(1)) {
                    if (line.trim() == "---") break
                    when {
                        line.startsWith("name:") -> name = line.removePrefix("name:").trim()
                        line.startsWith("description:") -> desc = line.removePrefix("description:").trim()
                    }
                }
            }
        }
        return name to desc
    }

    /** Seed bundled skills into the store on first launch. */
    fun seedFromAssets() {
        runCatching {
            val existing = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val assets = app.assets.list("skills") ?: return
            for (a in assets) {
                if (a in existing) continue
                app.assets.open("skills/$a").use { input ->
                    File(dir, a).writeBytes(input.readBytes())
                }
            }
        }
    }
}
