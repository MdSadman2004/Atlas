package com.atlas.agent.core.llm

import android.util.Base64
import com.atlas.agent.core.store.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApiToolCall(val id: String, val name: String, val arguments: String)

data class ApiMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ApiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val imageDataUrls: List<String> = emptyList(),
)

data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

data class ModelInfo(val id: String, val name: String, val contextLength: Long, val endpoints: List<String>) {
    val protocol: String get() = if (id.startsWith("claude")) "anthropic" else "openai"
}

sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent
    data class Reasoning(val delta: String) : LlmEvent
    data class ToolCallStart(val index: Int, val id: String?, val name: String?) : LlmEvent
    data class ToolCallArgs(val index: Int, val delta: String) : LlmEvent
}

data class LlmResult(
    val text: String,
    val reasoning: String,
    val toolCalls: List<ApiToolCall>,
    val finishReason: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val model: String,
)

class LlmException(val status: Int, message: String) : Exception(message)

private fun JSONObject.strOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifBlank { null }
}

private fun JSONObject.intOrZero(key: String): Int {
    if (!has(key) || isNull(key)) return 0
    return optInt(key, 0)
}

class LlmClient(private val settings: Settings) {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------ models

    fun models(): List<ModelInfo> {
        val req = Request.Builder()
            .url("${settings.baseUrl}/models")
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException(resp.code, body.take(500))
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<ModelInfo>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val eps = ArrayList<String>()
                m.optJSONArray("supported_endpoints")?.let { e ->
                    for (j in 0 until e.length()) eps.add(e.optString(j))
                }
                out.add(
                    ModelInfo(
                        id = m.optString("id"),
                        name = m.optString("name", m.optString("id")),
                        contextLength = m.optLong("context_length", 0L),
                        endpoints = eps,
                    )
                )
            }
            return out.sortedBy { it.id }
        }
    }

    // ------------------------------------------------------------------ public API

    fun chat(
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
        system: String,
        model: String = settings.model,
        maxTokens: Int = settings.maxTokens,
        temperature: Double = settings.temperature.toDouble(),
        stream: Boolean = settings.streaming,
        onEvent: (LlmEvent) -> Unit = {},
    ): LlmResult {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return if (isAnthropic(model)) {
                    anthropic(messages, tools, system, model, maxTokens, temperature, stream, onEvent)
                } else {
                    openai(messages, tools, system, model, maxTokens, temperature, stream, onEvent)
                }
            } catch (e: LlmException) {
                lastError = e
                val retryable = e.status == 429 || e.status >= 500 ||
                    (e.status == 400 && stream && e.message?.contains("stream", ignoreCase = true) == true)
                if (!retryable || attempt == 2) {
                    if (e.status == 400 && stream) {
                        // last resort: try without streaming
                        return if (isAnthropic(model)) {
                            anthropic(messages, tools, system, model, maxTokens, temperature, false, onEvent)
                        } else {
                            openai(messages, tools, system, model, maxTokens, temperature, false, onEvent)
                        }
                    }
                    throw e
                }
                Thread.sleep((1200L * (attempt + 1)))
            }
        }
        throw (lastError ?: LlmException(0, "unknown error"))
    }

    /** Simple non-streaming completion, no tools. Returns assistant text. */
    fun complete(
        messages: List<ApiMessage>,
        system: String = "",
        model: String = settings.fastModel,
        maxTokens: Int = 1024,
        temperature: Double = 0.2,
    ): String = chat(messages, emptyList(), system, model, maxTokens, temperature, false).text

    fun describeImage(path: String, mime: String, question: String, model: String = settings.visionModel): String {
        val bytes = java.io.File(path).readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUrl = "data:$mime;base64,$b64"
        val msg = ApiMessage(
            role = "user",
            content = question.ifBlank { "Describe this image in detail. If it contains text, transcribe it." },
            imageDataUrls = listOf(dataUrl),
        )
        return chat(listOf(msg), emptyList(), "", model, 1200, 0.2, false).text
    }

    private fun isAnthropic(model: String) = model.startsWith("claude")

    // ------------------------------------------------------------------ OpenAI

    private fun openAiMessages(messages: List<ApiMessage>, system: String): JSONArray {
        val arr = JSONArray()
        if (system.isNotBlank()) {
            arr.put(JSONObject().put("role", "system").put("content", system))
        }
        for (m in messages) {
            when (m.role) {
                "assistant" -> {
                    val o = JSONObject().put("role", "assistant")
                    if (m.toolCalls.isNotEmpty()) {
                        o.put("content", if (m.content.isNullOrBlank()) JSONObject.NULL else m.content)
                        val tcs = JSONArray()
                        for (tc in m.toolCalls) {
                            tcs.put(
                                JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put("function", JSONObject().put("name", tc.name).put("arguments", tc.arguments))
                            )
                        }
                        o.put("tool_calls", tcs)
                    } else {
                        o.put("content", m.content ?: "")
                    }
                    arr.put(o)
                }
                "tool" -> arr.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.toolCallId ?: "unknown")
                        .put("content", m.content ?: "")
                )
                else -> {
                    if (m.imageDataUrls.isNotEmpty()) {
                        val parts = JSONArray()
                        if (!m.content.isNullOrBlank()) {
                            parts.put(JSONObject().put("type", "text").put("text", m.content))
                        }
                        for (url in m.imageDataUrls) {
                            parts.put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", url))
                            )
                        }
                        arr.put(JSONObject().put("role", "user").put("content", parts))
                    } else {
                        arr.put(JSONObject().put("role", "user").put("content", m.content ?: ""))
                    }
                }
            }
        }
        return arr
    }

    private fun openAiTools(tools: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.schema)
                    )
            )
        }
        return arr
    }

    private fun openai(
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
        system: String,
        model: String,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean,
        onEvent: (LlmEvent) -> Unit,
    ): LlmResult {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", openAiMessages(messages, system))
            if (tools.isNotEmpty()) {
                put("tools", openAiTools(tools))
                put("tool_choice", "auto")
            }
            put("max_tokens", maxTokens)
            if (temperature > 0.0) put("temperature", temperature)
            if (model.startsWith("deepseek/deepseek-v4.1")) {
                settings.reasoningEffort.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
            }
            put("stream", stream)
        }
        val req = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw LlmException(resp.code, err)
            }
            if (!stream) {
                val text = resp.body?.string() ?: ""
                val json = JSONObject(text)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val msg = choice?.optJSONObject("message")
                val calls = parseToolCalls(msg?.optJSONArray("tool_calls"))
                val usage = json.optJSONObject("usage")
                return LlmResult(
                    text = msg?.strOrNull("content") ?: "",
                    reasoning = msg?.strOrNull("reasoning") ?: "",
                    toolCalls = calls,
                    finishReason = choice?.strOrNull("finish_reason") ?: "",
                    promptTokens = usage?.intOrZero("prompt_tokens") ?: 0,
                    completionTokens = usage?.intOrZero("completion_tokens") ?: 0,
                    model = json.strOrNull("model") ?: model,
                )
            }
            val src = resp.body?.source() ?: throw LlmException(0, "empty stream body")
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val acc = LinkedHashMap<Int, Acc>()
            var finish = ""
            var promptTokens = 0
            var completionTokens = 0
            var respModel = model
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val json = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    continue
                }
                json.strOrNull("model")?.let { respModel = it }
                json.optJSONObject("usage")?.let { u ->
                    promptTokens = u.intOrZero("prompt_tokens")
                    completionTokens = u.intOrZero("completion_tokens")
                }
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.strOrNull("finish_reason")?.let { finish = it }
                val delta = choice.optJSONObject("delta") ?: continue
                delta.strOrNull("content")?.let {
                    text.append(it)
                    onEvent(LlmEvent.Text(it))
                }
                delta.strOrNull("reasoning")?.let {
                    reasoning.append(it)
                    onEvent(LlmEvent.Reasoning(it))
                }
                delta.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", i)
                        val a = acc.getOrPut(idx) { Acc() }
                        tc.strOrNull("id")?.let { a.id = it }
                        tc.optJSONObject("function")?.let { f ->
                            f.strOrNull("name")?.let { a.name = it }
                            f.strOrNull("arguments")?.let {
                                a.args.append(it)
                                onEvent(LlmEvent.ToolCallArgs(idx, it))
                            }
                        }
                        onEvent(LlmEvent.ToolCallStart(idx, tc.strOrNull("id"), tc.optJSONObject("function")?.strOrNull("name")))
                    }
                }
            }
            val calls = acc.entries.sortedBy { it.key }.map {
                ApiToolCall(
                    id = it.value.id.ifBlank { "call_${it.key}" },
                    name = it.value.name,
                    arguments = it.value.args.toString().ifBlank { "{}" },
                )
            }.filter { it.name.isNotBlank() }
            return LlmResult(
                text = text.toString(),
                reasoning = reasoning.toString(),
                toolCalls = calls,
                finishReason = finish,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                model = respModel,
            )
        }
    }

    private fun parseToolCalls(arr: JSONArray?): List<ApiToolCall> {
        if (arr == null) return emptyList()
        val out = ArrayList<ApiToolCall>()
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val f = tc.optJSONObject("function") ?: continue
            out.add(
                ApiToolCall(
                    id = tc.optString("id", "call_$i"),
                    name = f.optString("name"),
                    arguments = f.optString("arguments", "{}"),
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ Anthropic

    private fun anthropicMessages(messages: List<ApiMessage>): JSONArray {
        val out = JSONArray()
        var lastBlocks: JSONArray? = null
        var lastRole: String? = null
        fun push(role: String, blocks: JSONArray) {
            if (role == lastRole && lastBlocks != null) {
                for (i in 0 until blocks.length()) lastBlocks!!.put(blocks.opt(i))
            } else {
                out.put(JSONObject().put("role", role).put("content", blocks))
                lastRole = role
                lastBlocks = blocks
            }
        }
        for (m in messages) {
            when (m.role) {
                "assistant" -> {
                    val blocks = JSONArray()
                    if (!m.content.isNullOrBlank()) {
                        blocks.put(JSONObject().put("type", "text").put("text", m.content))
                    }
                    for (tc in m.toolCalls) {
                        val input = try {
                            JSONObject(tc.arguments.ifBlank { "{}" })
                        } catch (e: Exception) {
                            JSONObject()
                        }
                        blocks.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", tc.id)
                                .put("name", tc.name)
                                .put("input", input)
                        )
                    }
                    if (blocks.length() > 0) push("assistant", blocks)
                }
                "tool" -> {
                    val blocks = JSONArray()
                    blocks.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", m.toolCallId ?: "unknown")
                            .put("content", m.content ?: "")
                    )
                    push("user", blocks)
                }
                else -> {
                    val blocks = JSONArray()
                    if (!m.content.isNullOrBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.content))
                    if (blocks.length() > 0) push("user", blocks)
                }
            }
        }
        return out
    }

    private fun anthropicTools(tools: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", t.schema)
            )
        }
        return arr
    }

    private fun anthropic(
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
        system: String,
        model: String,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean,
        onEvent: (LlmEvent) -> Unit,
    ): LlmResult {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", anthropicMessages(messages))
            if (tools.isNotEmpty()) put("tools", anthropicTools(tools))
            if (temperature > 0.0) put("temperature", temperature)
            put("stream", stream)
        }
        val req = Request.Builder()
            .url("${settings.baseUrl}/messages")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("anthropic-version", "2023-06-01")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw LlmException(resp.code, err)
            }
            if (!stream) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                val text = StringBuilder()
                val calls = ArrayList<ApiToolCall>()
                json.optJSONArray("content")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val b = arr.optJSONObject(i) ?: continue
                        when (b.optString("type")) {
                            "text" -> text.append(b.optString("text"))
                            "tool_use" -> calls.add(
                                ApiToolCall(
                                    id = b.optString("id"),
                                    name = b.optString("name"),
                                    arguments = (b.optJSONObject("input") ?: JSONObject()).toString(),
                                )
                            )
                        }
                    }
                }
                val usage = json.optJSONObject("usage")
                return LlmResult(
                    text = text.toString(),
                    reasoning = "",
                    toolCalls = calls,
                    finishReason = json.optString("stop_reason"),
                    promptTokens = usage?.intOrZero("input_tokens") ?: 0,
                    completionTokens = usage?.intOrZero("output_tokens") ?: 0,
                    model = json.optString("model", model),
                )
            }
            val src = resp.body?.source() ?: throw LlmException(0, "empty stream body")
            val text = StringBuilder()
            val calls = LinkedHashMap<Int, Acc>()
            var finish = ""
            var promptTokens = 0
            var completionTokens = 0
            var respModel = model
            var currentToolIndex = -1
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (line.startsWith("event:")) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val json = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    continue
                }
                when (json.optString("type")) {
                    "message_start" -> {
                        json.optJSONObject("message")?.let { m ->
                            m.strOrNull("model")?.let { respModel = it }
                            m.optJSONObject("usage")?.let { u -> promptTokens = u.intOrZero("input_tokens") }
                        }
                    }
                    "content_block_start" -> {
                        val idx = json.optInt("index", 0)
                        val block = json.optJSONObject("content_block")
                        if (block?.optString("type") == "tool_use") {
                            val a = Acc()
                            a.id = block.optString("id")
                            a.name = block.optString("name")
                            calls[idx] = a
                            currentToolIndex = idx
                            onEvent(LlmEvent.ToolCallStart(idx, a.id, a.name))
                        } else {
                            currentToolIndex = -1
                        }
                    }
                    "content_block_delta" -> {
                        val idx = json.optInt("index", 0)
                        val delta = json.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> delta.strOrNull("text")?.let {
                                text.append(it)
                                onEvent(LlmEvent.Text(it))
                            }
                            "thinking_delta" -> delta.strOrNull("thinking")?.let { onEvent(LlmEvent.Reasoning(it)) }
                            "input_json_delta" -> delta.strOrNull("partial_json")?.let {
                                val a = calls.getOrPut(idx) { Acc() }
                                a.args.append(it)
                                onEvent(LlmEvent.ToolCallArgs(idx, it))
                            }
                        }
                    }
                    "message_delta" -> {
                        json.optJSONObject("delta")?.strOrNull("stop_reason")?.let { finish = it }
                        json.optJSONObject("usage")?.let { u -> completionTokens = u.intOrZero("output_tokens") }
                    }
                    "error" -> {
                        val msg = json.optJSONObject("error")?.optString("message") ?: "stream error"
                        throw LlmException(200, msg)
                    }
                }
            }
            val outCalls = calls.entries.sortedBy { it.key }.map {
                ApiToolCall(
                    id = it.value.id.ifBlank { "tool_${it.key}" },
                    name = it.value.name,
                    arguments = it.value.args.toString().ifBlank { "{}" },
                )
            }.filter { it.name.isNotBlank() }
            return LlmResult(
                text = text.toString(),
                reasoning = "",
                toolCalls = outCalls,
                finishReason = finish,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                model = respModel,
            )
        }
    }

    private class Acc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }
}
