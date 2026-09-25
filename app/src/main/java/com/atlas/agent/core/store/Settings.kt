package com.atlas.agent.core.store

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.commandcode.ai/provider/v1"
        /** Hard-locked by user instruction: Atlas only ever runs on this model. */
        const val LOCKED_MODEL = "deepseek/deepseek-v4.1-flash"
        const val DEFAULT_MODEL = LOCKED_MODEL
        const val DEFAULT_FAST_MODEL = LOCKED_MODEL
        /** Vision needs a multimodal model; deepseek's own vision model keeps the family consistent. */
        const val DEFAULT_VISION_MODEL = "deepseek/deepseek-v4-flash-vision-exp"
        /** Forwarded to the API for the locked model — maximum deliberation. */
        const val REASONING_EFFORT = "high"
        const val APPROVALS_MANUAL = "manual"
        const val APPROVALS_SMART = "smart"
        const val APPROVALS_OFF = "off"
        const val DEFAULT_ABOUT_USER =
            "The user is Md Sadman Bin Masud, based in Dhaka, Bangladesh (UTC+6). EECE student at MIST. " +
                "He prefers concise, direct answers, accuracy over flattery, and concrete results. " +
                "Deliverables go to D:/ or E:/ on his PC; on this phone, save files under his Downloads."
    }

    private val sp = context.getSharedPreferences("atlas_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit { putString("api_key", v) }

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit { putString("base_url", v.trim().trimEnd('/')) }

    /** Hard-locked to LOCKED_MODEL — writes are ignored so nothing can switch the model. */
    var model: String
        get() = LOCKED_MODEL
        set(v) { /* locked */ }

    /** Titles, memory extraction and sub-agents run on the same locked model. */
    var fastModel: String
        get() = LOCKED_MODEL
        set(v) { /* locked */ }

    /** Reasoning effort sent with every request to the locked model. */
    val reasoningEffort: String get() = REASONING_EFFORT

    var visionModel: String
        get() = sp.getString("vision_model", DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = sp.edit { putString("vision_model", v) }

    var userName: String
        get() = sp.getString("user_name", "Md Sadman") ?: "Md Sadman"
        set(v) = sp.edit { putString("user_name", v) }

    var aboutUser: String
        get() = sp.getString("about_user", DEFAULT_ABOUT_USER) ?: DEFAULT_ABOUT_USER
        set(v) = sp.edit { putString("about_user", v) }

    var systemExtra: String
        get() = sp.getString("system_extra", "") ?: ""
        set(v) = sp.edit { putString("system_extra", v) }

    var maxSteps: Int
        get() = sp.getInt("max_steps", 25)
        set(v) = sp.edit { putInt("max_steps", v.coerceIn(1, 80)) }

    var maxTokens: Int
        get() = sp.getInt("max_tokens", 4096)
        set(v) = sp.edit { putInt("max_tokens", v.coerceIn(256, 32000)) }

    var temperature: Float
        get() = sp.getFloat("temperature", 0.4f)
        set(v) = sp.edit { putFloat("temperature", v.coerceIn(0f, 1.5f)) }

    var historyLimit: Int
        get() = sp.getInt("history_limit", 40)
        set(v) = sp.edit { putInt("history_limit", v.coerceIn(6, 200)) }

    var streaming: Boolean
        get() = sp.getBoolean("streaming", true)
        set(v) = sp.edit { putBoolean("streaming", v) }

    /** Default is auto-approve ("off"): Atlas works straight through without approval prompts. */
    var approvals: String
        get() = sp.getString("approvals", APPROVALS_OFF) ?: APPROVALS_OFF
        set(v) = sp.edit { putString("approvals", v) }

    var autoMemory: Boolean
        get() = sp.getBoolean("auto_memory", true)
        set(v) = sp.edit { putBoolean("auto_memory", v) }

    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts_enabled", true)
        set(v) = sp.edit { putBoolean("tts_enabled", v) }

    var speakReplies: Boolean
        get() = sp.getBoolean("speak_replies", false)
        set(v) = sp.edit { putBoolean("speak_replies", v) }

    var voiceAutoSend: Boolean
        get() = sp.getBoolean("voice_auto_send", false)
        set(v) = sp.edit { putBoolean("voice_auto_send", v) }

    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit { putString("theme", v) }

    var autonomyEnabled: Boolean
        get() = sp.getBoolean("autonomy_enabled", true)
        set(v) = sp.edit { putBoolean("autonomy_enabled", v) }

    var autonomyIntervalMinutes: Int
        get() = sp.getInt("autonomy_interval", 15)
        set(v) = sp.edit { putInt("autonomy_interval", v.coerceIn(15, 720)) }

    var notifyOnComplete: Boolean
        get() = sp.getBoolean("notify_on_complete", true)
        set(v) = sp.edit { putBoolean("notify_on_complete", v) }

    var titleModel: Boolean
        get() = sp.getBoolean("ai_titles", true)
        set(v) = sp.edit { putBoolean("ai_titles", v) }

    fun toolEnabled(name: String, default: Boolean): Boolean = sp.getBoolean("tool_$name", default)

    fun setToolEnabled(name: String, enabled: Boolean) = sp.edit { putBoolean("tool_$name", enabled) }
}
