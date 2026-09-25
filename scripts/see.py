#!/usr/bin/env python
"""Ask a vision model what is in an image.

Usage:  python scripts/see.py <image-path> "<question>"
Key:    ATLAS_KEY env var, else COMMANDCODE_API_KEY from D:/.hermes/.env (never printed).
"""
import base64
import json
import os
import re
import sys
import urllib.request

MODEL = os.environ.get("VISION_MODEL", "google/gemini-3.8-flash")


def load_key() -> str:
    key = os.environ.get("ATLAS_KEY", "").strip()
    if key:
        return key
    for line in open("D:/.hermes/.env", encoding="utf-8"):
        m = re.match(r"\s*COMMANDCODE_API_KEY\s*=\s*(.*)$", line)
        if m:
            return m.group(1).strip().strip('"').strip("'")
    raise SystemExit("no key: set ATLAS_KEY or COMMANDCODE_API_KEY")


def ask(path: str, question: str, max_tokens: int = 2000) -> str:
    b64 = base64.b64encode(open(path, "rb").read()).decode()
    mime = "image/png" if path.lower().endswith(".png") else "image/jpeg"
    payload = {
        "model": MODEL,
        "max_tokens": max_tokens,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": question},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
            ],
        }],
    }
    req = urllib.request.Request(
        "https://api.commandcode.ai/provider/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": "Bearer " + load_key(),
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 AtlasVision/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.load(resp)
    return data["choices"][0]["message"].get("content", "").strip()


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    print(ask(sys.argv[1], sys.argv[2]))
