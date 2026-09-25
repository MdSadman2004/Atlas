package com.atlas.agent.core.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.atlas.agent.core.llm.ApiMessage
import com.atlas.agent.core.llm.ApiToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SessionRow(val id: Long, val title: String, val created: Long, val updated: Long, val msgCount: Int = 0)
data class MessageRow(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val content: String,
    val payload: String?,
    val name: String?,
    val reasoning: String?,
    val toolCallId: String?,
    val created: Long,
)
data class MemoryRow(val id: Long, val text: String, val importance: Int, val created: Long, val uses: Int)
data class GoalRow(
    val id: Long,
    val title: String,
    val instruction: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val created: Long,
    val lastRun: Long,
    val nextRun: Long,
    val runCount: Int,
    val lastResult: String?,
)
data class RunRow(
    val id: Long,
    val sessionId: Long,
    val goalId: Long?,
    val started: Long,
    val ended: Long,
    val status: String,
    val steps: Int,
    val tokens: Int,
    val summary: String?,
)
data class EventRow(
    val id: Long,
    val runId: Long,
    val name: String,
    val args: String?,
    val result: String?,
    val ok: Boolean,
    val created: Long,
)

class AtlasDb(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 1) {

    /** Bumped after every write so Compose screens can re-query. */
    val version = MutableStateFlow(0L)

    fun bump() {
        version.value = version.value + 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                "created INTEGER NOT NULL, updated INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', payload TEXT, name TEXT, reasoning TEXT, " +
                "tool_call_id TEXT, created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)")
        db.execSQL(
            "CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, " +
                "importance INTEGER NOT NULL DEFAULT 1, created INTEGER NOT NULL, last_used INTEGER NOT NULL DEFAULT 0, " +
                "uses INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, instruction TEXT NOT NULL, " +
                "interval_minutes INTEGER NOT NULL DEFAULT 60, enabled INTEGER NOT NULL DEFAULT 1, created INTEGER NOT NULL, " +
                "last_run INTEGER NOT NULL DEFAULT 0, next_run INTEGER NOT NULL DEFAULT 0, run_count INTEGER NOT NULL DEFAULT 0, " +
                "last_result TEXT)"
        )
        db.execSQL(
            "CREATE TABLE runs(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, goal_id INTEGER, " +
                "started INTEGER NOT NULL, ended INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'running', " +
                "steps INTEGER NOT NULL DEFAULT 0, tokens INTEGER NOT NULL DEFAULT 0, summary TEXT)"
        )
        db.execSQL(
            "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, name TEXT NOT NULL, " +
                "args TEXT, result TEXT, ok INTEGER NOT NULL DEFAULT 1, created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — nothing to migrate yet.
    }

    // ------------------------------------------------------------------ sessions

    fun createSession(title: String): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert(
            "sessions", null,
            ContentValues().apply {
                put("title", title.ifBlank { "New chat" })
                put("created", now)
                put("updated", now)
            }
        )
        bump()
        return id
    }

    fun renameSession(id: Long, title: String) {
        writableDatabase.update("sessions", ContentValues().apply { put("title", title) }, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun touchSession(id: Long) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("updated", System.currentTimeMillis()) },
            "id=?",
            arrayOf(id.toString())
        )
    }

    fun deleteSession(id: Long) {
        writableDatabase.delete("messages", "session_id=?", arrayOf(id.toString()))
        writableDatabase.delete("sessions", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun sessions(): List<SessionRow> {
        val out = ArrayList<SessionRow>()
        val sql = "SELECT s.id, s.title, s.created, s.updated, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS cnt " +
            "FROM sessions s WHERE s.archived = 0 ORDER BY s.updated DESC"
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(SessionRow(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4)))
            }
        }
        return out
    }

    fun sessionTitle(id: Long): String {
        readableDatabase.rawQuery("SELECT title FROM sessions WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "Chat"
    }

    fun messageCount(sessionId: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=?", arrayOf(sessionId.toString())).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    // ------------------------------------------------------------------ messages

    fun addMessage(
        sessionId: Long,
        role: String,
        content: String,
        payload: String? = null,
        name: String? = null,
        reasoning: String? = null,
        toolCallId: String? = null,
    ): Long {
        val id = writableDatabase.insert(
            "messages", null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("role", role)
                put("content", content)
                put("payload", payload)
                put("name", name)
                put("reasoning", reasoning)
                put("tool_call_id", toolCallId)
                put("created", System.currentTimeMillis())
            }
        )
        touchSession(sessionId)
        bump()
        return id
    }

    fun updateMessage(id: Long, content: String, reasoning: String?, payload: String?) {
        writableDatabase.update(
            "messages",
            ContentValues().apply {
                put("content", content)
                put("reasoning", reasoning)
                put("payload", payload)
            },
            "id=?",
            arrayOf(id.toString())
        )
        bump()
    }

    fun deleteMessage(id: Long) {
        writableDatabase.delete("messages", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun messages(sessionId: Long, limit: Int = 400): List<MessageRow> {
        val out = ArrayList<MessageRow>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, role, content, payload, name, reasoning, tool_call_id, created " +
                "FROM messages WHERE session_id=? ORDER BY id ASC LIMIT ?",
            arrayOf(sessionId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readMessage(c))
        }
        return out
    }

    fun lastMessages(sessionId: Long, limit: Int): List<MessageRow> {
        val out = ArrayList<MessageRow>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, role, content, payload, name, reasoning, tool_call_id, created " +
                "FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(sessionId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readMessage(c))
        }
        out.reverse()
        return out
    }

    private fun readMessage(c: Cursor) = MessageRow(
        id = c.getLong(0),
        sessionId = c.getLong(1),
        role = c.getString(2),
        content = c.getString(3) ?: "",
        payload = c.getString(4),
        name = c.getString(5),
        reasoning = c.getString(6),
        toolCallId = c.getString(7),
        created = c.getLong(8),
    )

    /** Build the API-facing history: last [limit] rows, trimmed to start on a clean boundary. */
    fun messagesForApi(sessionId: Long, limit: Int): List<ApiMessage> {
        val rows = lastMessages(sessionId, limit)
        // Drop leading assistant/tool rows until the first user row so tool_call pairs stay valid.
        var start = 0
        while (start < rows.size && rows[start].role != "user") start++
        val out = ArrayList<ApiMessage>()
        for (row in rows.drop(start)) {
            when (row.role) {
                "user" -> out.add(ApiMessage(role = "user", content = row.content))
                "assistant" -> {
                    val calls = ArrayList<ApiToolCall>()
                    row.payload?.let { p ->
                        runCatching {
                            val obj = JSONObject(p)
                            obj.optJSONArray("tool_calls")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    val tc = arr.optJSONObject(i) ?: continue
                                    calls.add(
                                        ApiToolCall(
                                            id = tc.optString("id"),
                                            name = tc.optString("name"),
                                            arguments = tc.optString("arguments"),
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (row.content.isBlank() && calls.isEmpty()) continue
                    out.add(
                        ApiMessage(
                            role = "assistant",
                            content = row.content.ifBlank { null },
                            toolCalls = calls,
                        )
                    )
                }
                "tool" -> out.add(
                    ApiMessage(
                        role = "tool",
                        content = row.content.take(12000),
                        toolCallId = row.toolCallId ?: "unknown",
                        name = row.name,
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ memories

    fun insertMemory(text: String, importance: Int): Long {
        val id = writableDatabase.insert(
            "memories", null,
            ContentValues().apply {
                put("text", text)
                put("importance", importance)
                put("created", System.currentTimeMillis())
            }
        )
        bump()
        return id
    }

    fun memories(limit: Int = 200): List<MemoryRow> {
        val out = ArrayList<MemoryRow>()
        readableDatabase.rawQuery(
            "SELECT id, text, importance, created, uses FROM memories ORDER BY importance DESC, created DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(MemoryRow(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getInt(4)))
        }
        return out
    }

    fun searchMemories(q: String, limit: Int = 30): List<MemoryRow> {
        val out = ArrayList<MemoryRow>()
        readableDatabase.rawQuery(
            "SELECT id, text, importance, created, uses FROM memories WHERE text LIKE ? ORDER BY importance DESC, created DESC LIMIT ?",
            arrayOf("%$q%", limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(MemoryRow(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getInt(4)))
        }
        return out
    }

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memories", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun bumpMemoryUse(id: Long) {
        writableDatabase.execSQL("UPDATE memories SET uses = uses + 1, last_used = ? WHERE id = ?", arrayOf(System.currentTimeMillis(), id))
    }

    // ------------------------------------------------------------------ goals

    fun insertGoal(title: String, instruction: String, intervalMinutes: Int): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert(
            "goals", null,
            ContentValues().apply {
                put("title", title)
                put("instruction", instruction)
                put("interval_minutes", intervalMinutes)
                put("enabled", 1)
                put("created", now)
                put("next_run", now + intervalMinutes * 60_000L)
            }
        )
        bump()
        return id
    }

    fun goals(): List<GoalRow> {
        val out = ArrayList<GoalRow>()
        readableDatabase.rawQuery(
            "SELECT id, title, instruction, interval_minutes, enabled, created, last_run, next_run, run_count, last_result " +
                "FROM goals ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(readGoal(c))
        }
        return out
    }

    fun goal(id: Long): GoalRow? {
        readableDatabase.rawQuery(
            "SELECT id, title, instruction, interval_minutes, enabled, created, last_run, next_run, run_count, last_result " +
                "FROM goals WHERE id=?", arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) return readGoal(c)
        }
        return null
    }

    private fun readGoal(c: Cursor) = GoalRow(
        id = c.getLong(0),
        title = c.getString(1),
        instruction = c.getString(2),
        intervalMinutes = c.getInt(3),
        enabled = c.getInt(4) == 1,
        created = c.getLong(5),
        lastRun = c.getLong(6),
        nextRun = c.getLong(7),
        runCount = c.getInt(8),
        lastResult = c.getString(9),
    )

    fun updateGoal(id: Long, enabled: Boolean? = null, intervalMinutes: Int? = null, instruction: String? = null, title: String? = null) {
        val cv = ContentValues()
        enabled?.let { cv.put("enabled", if (it) 1 else 0) }
        intervalMinutes?.let { cv.put("interval_minutes", it) }
        instruction?.let { cv.put("instruction", it) }
        title?.let { cv.put("title", it) }
        if (cv.size() > 0) {
            writableDatabase.update("goals", cv, "id=?", arrayOf(id.toString()))
            bump()
        }
    }

    fun deleteGoal(id: Long) {
        writableDatabase.delete("goals", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun markGoalRun(id: Long, nextRun: Long, summary: String?) {
        writableDatabase.execSQL(
            "UPDATE goals SET last_run=?, next_run=?, run_count=run_count+1, last_result=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), nextRun, summary?.take(500), id)
        )
        bump()
    }

    fun dueGoals(now: Long): List<GoalRow> = goals().filter { it.enabled && it.nextRun <= now }

    // ------------------------------------------------------------------ runs / events / kv

    fun startRun(sessionId: Long, goalId: Long?): Long {
        val id = writableDatabase.insert(
            "runs", null,
            ContentValues().apply {
                put("session_id", sessionId)
                if (goalId != null) put("goal_id", goalId)
                put("started", System.currentTimeMillis())
                put("status", "running")
            }
        )
        bump()
        return id
    }

    fun finishRun(id: Long, status: String, steps: Int, tokens: Int, summary: String?) {
        writableDatabase.update(
            "runs",
            ContentValues().apply {
                put("ended", System.currentTimeMillis())
                put("status", status)
                put("steps", steps)
                put("tokens", tokens)
                put("summary", summary?.take(1000))
            },
            "id=?",
            arrayOf(id.toString())
        )
        bump()
    }

    fun runs(limit: Int = 50): List<RunRow> {
        val out = ArrayList<RunRow>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, goal_id, started, ended, status, steps, tokens, summary FROM runs ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RunRow(
                        id = c.getLong(0),
                        sessionId = c.getLong(1),
                        goalId = if (c.isNull(2)) null else c.getLong(2),
                        started = c.getLong(3),
                        ended = c.getLong(4),
                        status = c.getString(5) ?: "",
                        steps = c.getInt(6),
                        tokens = c.getInt(7),
                        summary = c.getString(8),
                    )
                )
            }
        }
        return out
    }

    fun addEvent(runId: Long, name: String, args: String?, result: String?, ok: Boolean) {
        writableDatabase.insert(
            "events", null,
            ContentValues().apply {
                put("run_id", runId)
                put("name", name)
                put("args", args?.take(4000))
                put("result", result?.take(4000))
                put("ok", if (ok) 1 else 0)
                put("created", System.currentTimeMillis())
            }
        )
    }

    fun events(runId: Long): List<EventRow> {
        val out = ArrayList<EventRow>()
        readableDatabase.rawQuery(
            "SELECT id, run_id, name, args, result, ok, created FROM events WHERE run_id=? ORDER BY id ASC",
            arrayOf(runId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    EventRow(
                        id = c.getLong(0),
                        runId = c.getLong(1),
                        name = c.getString(2),
                        args = c.getString(3),
                        result = c.getString(4),
                        ok = c.getInt(5) == 1,
                        created = c.getLong(6),
                    )
                )
            }
        }
        return out
    }

    fun recentEvents(limit: Int = 120): List<EventRow> {
        val out = ArrayList<EventRow>()
        readableDatabase.rawQuery(
            "SELECT id, run_id, name, args, result, ok, created FROM events ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    EventRow(
                        id = c.getLong(0),
                        runId = c.getLong(1),
                        name = c.getString(2),
                        args = c.getString(3),
                        result = c.getString(4),
                        ok = c.getInt(5) == 1,
                        created = c.getLong(6),
                    )
                )
            }
        }
        return out
    }

    fun kvSet(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "kv", null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun kvGet(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun stats(): Triple<Int, Int, Int> {
        var msgs = 0
        var runs = 0
        var tokens = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { c -> if (c.moveToFirst()) msgs = c.getInt(0) }
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(tokens),0) FROM runs", null).use { c ->
            if (c.moveToFirst()) {
                runs = c.getInt(0)
                tokens = c.getInt(1)
            }
        }
        return Triple(msgs, runs, tokens)
    }

    /** Export a session as markdown. */
    fun exportMarkdown(sessionId: Long): String {
        val sb = StringBuilder()
        sb.append("# ${sessionTitle(sessionId)}\n\n")
        for (m in messages(sessionId)) {
            when (m.role) {
                "user" -> sb.append("**You:** ${m.content}\n\n")
                "assistant" -> if (m.content.isNotBlank()) sb.append("**Atlas:** ${m.content}\n\n")
                "tool" -> sb.append("> tool `_${m.name ?: "?"}_`: ${m.content.take(400)}\n\n")
            }
        }
        return sb.toString()
    }

    fun toolCallCount(sessionId: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=? AND role='tool'", arrayOf(sessionId.toString())).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun allSessionsRaw(): JSONArray {
        val arr = JSONArray()
        for (s in sessions()) {
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("messages", s.msgCount))
        }
        return arr
    }
}
