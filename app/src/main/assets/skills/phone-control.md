---
name: phone-control
description: How to reliably drive the phone's UI (apps, settings, menus) with ui_dump, ui_tap, ui_type and screenshots.
---

# Phone control playbook

## The loop

1. `ui_dump` — read the screen as text. Every line shows: index, widget class, `text`/`desc`, flags (`clickable`, `editable`, `scrollable`) and bounds `[left,top,right,bottom]`.
2. `ui_tap` — tap by the **text** you saw (exact match wins, then substring). Use `index` when there are several matches. Raw `x`/`y` (screen pixels) also work.
3. `ui_type` — type into a field. Tap it first if it is not focused; use `target_text` to address a specific field.
4. `ui_dump` again — **always confirm** the screen changed the way you expected.
5. Need the actual pixels (colors, images, unlabelled buttons)? `screenshot` then `analyze_image` on the returned path.

## Keyboard & text traps

- After typing, the keyboard covers the bottom of the screen — dump again; the send button may be behind it.
- Faster than `ui_type` for long text: `clipboard_write` then long-press the field and tap Paste (or use `ui_type` anyway — it is one call).
- Spaces matter: prefer `clipboard_write` + paste when a command contains quotes or special characters.

## Reliability rules

- One action per step; re-dump after anything that could navigate.
- If a tap does nothing, the node was probably not clickable — tap its center coordinates from the bounds instead.
- Scroll with `ui_scroll` (down/up) rather than flicking blindly; check what appeared afterwards.
- `ui_press back` is your undo for wrong screens. Never chain multiple backs without checking.
- Some apps (banking, DRM video) block accessibility inspection — say so instead of guessing.
- On Android 12+ a dialog may ask about accessibility restrictions for a specific app; tell the user to allow it if asked.

## Screenshots + vision

`screenshot` returns a file path. Feed it to `analyze_image` with a concrete question ("what is on this screen?", "which button says Send?"). Combine: text tree for structure, vision for meaning.
