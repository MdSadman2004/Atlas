package com.atlas.agent.core

import android.app.Application
import com.atlas.agent.core.agent.AgentEngine
import com.atlas.agent.core.llm.LlmClient
import com.atlas.agent.core.memory.MemoryStore
import com.atlas.agent.core.skills.SkillStore
import com.atlas.agent.core.store.AtlasDb
import com.atlas.agent.core.store.Settings
import com.atlas.agent.core.tools.ToolRegistry
import com.atlas.agent.core.tools.registerDefaults
import com.atlas.agent.core.voice.Tts

/**
 * Atlas service locator. One process-wide container — deliberately no Hilt/KSP so the
 * build stays fast and dependency-light.
 */
object Atlas {
    lateinit var app: Application
        private set
    lateinit var settings: Settings
        private set
    lateinit var db: AtlasDb
        private set
    lateinit var llm: LlmClient
        private set
    lateinit var tools: ToolRegistry
        private set
    lateinit var memory: MemoryStore
        private set
    lateinit var skills: SkillStore
        private set
    lateinit var engine: AgentEngine
        private set
    lateinit var tts: Tts
        private set

    /** True while any Atlas activity is in the foreground. */
    @Volatile
    var foreground: Boolean = false

    @Volatile
    private var initialized = false

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = application
            settings = Settings(application)
            db = AtlasDb(application)
            llm = LlmClient(settings)
            memory = MemoryStore(db)
            skills = SkillStore(application)
            tools = ToolRegistry().also { it.registerDefaults() }
            engine = AgentEngine(settings, db, llm, tools, memory, skills)
            tts = Tts(application)
            initialized = true
        }
    }
}
