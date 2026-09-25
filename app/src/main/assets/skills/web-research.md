---
name: web-research
description: How to research something properly with web_search, fetch_url and sub-agents without flooding the chat.
---

# Web research

## Single question

1. `web_search` with a *specific* query (add the year, the version, the site when you know it).
2. `fetch_url` the 2–3 most promising results — snippets are often wrong or stale.
3. Cross-check one claim across two independent sources before stating it as fact.
4. Answer with: the conclusion first, then 2–5 supporting bullets, then the source URLs.

## Big questions (many pages, comparisons, long reports)

Delegate: `spawn_agent` with a complete brief — what to find, which sites, what format to answer in, and "return only the findings, max 400 words". The sub-agent burns its own context; you only get the report.

Split large questions into 2–4 independent sub-tasks and run them as separate `spawn_agent` calls.

## Fetching traps

- HTML is converted to text automatically; use `raw=true` for JSON APIs.
- Paywalls/login walls return a login page — say so; do not invent the content.
- If a page fails, try the site's mobile version, a cache (`r.jina.ai` prefix works without a key), or a different source.
- Truncate aggressively: ask for the section you need, not the whole site.

## Output habits

- Quote the exact number/date you found; never round silently.
- Distinguish "found on the page" from "inferred".
- If nothing credible turned up, say that plainly instead of padding.
