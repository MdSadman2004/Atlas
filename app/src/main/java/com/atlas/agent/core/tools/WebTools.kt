package com.atlas.agent.core.tools

import com.atlas.agent.core.agent.Tool
import com.atlas.agent.core.agent.ToolContext
import com.atlas.agent.core.agent.intOr
import com.atlas.agent.core.agent.reqStr
import com.atlas.agent.core.agent.str
import com.atlas.agent.core.util.Http
import com.atlas.agent.core.util.Util
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class WebSearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Search the web (DuckDuckGo). Returns titles, URLs and snippets. Follow up with fetch_url to read a result."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"query":{"type":"string"},"max_results":{"type":"integer","description":"default 8"}},"required":["query"]}"""
    )
    override val group = "web"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val q = args.reqStr("query")
        val max = args.intOr("max_results", 8).coerceIn(1, 20)
        val results = trySearch(q, max)
        if (results.isEmpty()) return "No results (or the search endpoint was blocked). Try fetch_url on a known site."
        return results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }
    }

    private data class R(val title: String, val url: String, val snippet: String)

    private fun trySearch(q: String, max: Int): List<R> {
        // 1) DuckDuckGo HTML (GET)
        runCatching { parseDdg(Http.get("https://html.duckduckgo.com/html/?q=" + enc(q)), max) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // 2) DuckDuckGo HTML (POST form)
        runCatching {
            val form = FormBody.Builder().add("q", q).build()
            val req = Request.Builder().url("https://html.duckduckgo.com/html/")
                .header("User-Agent", Http.UA).post(form).build()
            Http.client.newCall(req).execute().use { resp -> parseDdg(resp.body?.string() ?: "", max) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // 3) lite.duckduckgo.com
        runCatching { parseLite(Http.get("https://lite.duckduckgo.com/lite/?q=" + enc(q)), max) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parseDdg(html: String, max: Int): List<R> {
        val out = ArrayList<R>()
        val linkRe = Regex("""(?is)<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""")
        val snippetRe = Regex("""(?is)<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""")
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { Util.htmlToText(it.groupValues[1]) }.toList()
        links.forEachIndexed { i, m ->
            if (out.size >= max) return@forEachIndexed
            var url = m.groupValues[1]
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(url)?.groupValues?.get(1)
            if (uddg != null) url = java.net.URLDecoder.decode(uddg, "UTF-8")
            val title = Util.htmlToText(m.groupValues[2])
            out.add(R(title, url, snippets.getOrElse(i) { "" }))
        }
        return out
    }

    private fun parseLite(html: String, max: Int): List<R> {
        val out = ArrayList<R>()
        val re = Regex("""(?is)<a[^>]+class="result-link"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""")
        val snip = Regex("""(?is)<td[^>]*class="result-snippet"[^>]*>(.*?)</td>""")
        val links = re.findAll(html).toList()
        val snippets = snip.findAll(html).map { Util.htmlToText(it.groupValues[1]) }.toList()
        links.forEachIndexed { i, m ->
            if (out.size >= max) return@forEachIndexed
            out.add(R(Util.htmlToText(m.groupValues[2]), m.groupValues[1], snippets.getOrElse(i) { "" }))
        }
        return out
    }
}

class FetchUrlTool : Tool {
    override val name = "fetch_url"
    override val description =
        "Fetch a URL and return its text content (HTML is converted to plain text). Use raw=true for JSON/API endpoints. Max ~9000 chars unless max_chars is set."
    override val parameters = JSONObject(
        """{"type":"object","properties":{"url":{"type":"string"},"raw":{"type":"boolean"},"max_chars":{"type":"integer"}},"required":["url"]}"""
    )
    override val group = "web"

    override suspend fun run(ctx: ToolContext, args: JSONObject): String {
        val url = args.reqStr("url")
        val raw = org.json.JSONObject(args.toString()).optBoolean("raw", false)
        val maxChars = args.intOr("max_chars", 9000).coerceIn(500, 60000)
        return runCatching {
            val body = Http.get(url)
            val contentType = ""
            val text = if (raw) body else if (body.trimStart().startsWith("<")) Util.htmlToText(body) else body
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.let { Util.htmlToText(it) }
            buildString {
                if (!title.isNullOrBlank()) append("title: $title\n")
                append(Util.truncate(text, maxChars))
            }
        }.getOrElse { "ERROR: ${it.message}" }
    }
}
