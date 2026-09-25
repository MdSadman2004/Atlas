package com.atlas.agent.core.tools

import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.store.Settings

class ToolRegistry {
    private val map = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        map[tool.name] = tool
    }

    fun get(name: String): Tool? = map[name]

    fun all(): List<Tool> = map.values.toList()

    fun enabled(settings: Settings): List<Tool> = all().filter { settings.toolEnabled(it.name, it.defaultEnabled) }
}

fun ToolRegistry.registerDefaults() {
    // device / system
    register(DeviceInfoTool())
    register(BatteryTool())
    register(NowTool())
    register(CalcTool())
    register(LocationTool())
    register(ClipboardReadTool())
    register(ClipboardWriteTool())
    register(NotifyTool())
    register(SpeakTool())
    register(VibrateTool())
    register(TorchTool())
    register(VolumeTool())
    register(OpenUrlTool())
    register(OpenAppTool())
    register(ListAppsTool())
    register(OpenSettingsTool())
    register(ShareTextTool())
    register(ContactsSearchTool())
    register(SmsTool())
    register(ScreenshotTool())
    register(AnalyzeImageTool())
    // files
    register(FsListTool())
    register(FsReadTool())
    register(FsWriteTool())
    register(FsMkdirTool())
    register(FsDeleteTool())
    // web
    register(WebSearchTool())
    register(FetchUrlTool())
    // shell
    register(ShellTool())
    // ui automation
    register(UiDumpTool())
    register(UiTapTool())
    register(UiTypeTool())
    register(UiSwipeTool())
    register(UiScrollTool())
    register(UiPressTool())
    // agent internals
    register(MemorySaveTool())
    register(MemorySearchTool())
    register(MemoryForgetTool())
    register(SkillsListTool())
    register(SkillReadTool())
    register(SkillWriteTool())
    register(SkillDeleteTool())
    register(GoalCreateTool())
    register(GoalListTool())
    register(GoalUpdateTool())
    register(GoalDeleteTool())
    register(ScheduleOnceTool())
    register(SpawnAgentTool())
    register(SetTodosTool())
}
