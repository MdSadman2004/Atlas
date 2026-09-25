package com.atlas.agent.core.memory

import com.atlas.agent.core.store.AtlasDb
import com.atlas.agent.core.store.MemoryRow

class MemoryStore(private val db: AtlasDb) {

    fun all(limit: Int = 200): List<MemoryRow> = db.memories(limit)

    fun search(q: String, limit: Int = 30): List<MemoryRow> = db.searchMemories(q, limit)

    fun add(text: String, importance: Int = 2): Long = db.insertMemory(text.trim(), importance)

    /** Returns true when a genuinely new fact was stored. */
    fun addIfNew(text: String, importance: Int = 2): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        val norm = normalize(t)
        val existing = db.memories(500)
        for (m in existing) {
            val mn = normalize(m.text)
            if (mn == norm || (mn.length > 20 && norm.length > 20 && (mn.contains(norm) || norm.contains(mn)))) {
                db.bumpMemoryUse(m.id)
                return false
            }
        }
        db.insertMemory(t, importance)
        return true
    }

    fun forget(id: Long) = db.deleteMemory(id)

    fun promptBlock(): String {
        val rows = db.memories(40)
        if (rows.isEmpty()) return ""
        return rows.joinToString("\n") { "- ${it.text}" }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
