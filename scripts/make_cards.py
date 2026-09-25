#!/usr/bin/env python
"""Render Atlas title + outro card sequences (720x1600, 30 fps) with PIL.

Writes PNG frames into  E:/Atlas/build/frames_intro/  and  E:/Atlas/build/frames_outro/
so ffmpeg can encode them into the final edit.
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 720, 1600
BG_TOP = (20, 17, 16)
BG_BOTTOM = (27, 23, 20)
CLAY = (231, 164, 115)
WARM = (244, 238, 231)
MUTED = (182, 168, 153)
OUT = "E:/Atlas/build"

FONT_CANDIDATES = {
    "serif": ["C:/Windows/Fonts/georgia.ttf", "C:/Windows/Fonts/times.ttf", "C:/Windows/Fonts/arial.ttf"],
    "sans": ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"],
    "sansb": ["C:/Windows/Fonts/seguisb.ttf", "C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf"],
    "mono": ["C:/Windows/Fonts/consola.ttf", "C:/Windows/Fonts/cour.ttf"],
}


def load(kind, size):
    for path in FONT_CANDIDATES[kind]:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def ease(x):
    x = max(0.0, min(1.0, x))
    return 1 - (1 - x) ** 3


def base_frame(seed=0):
    """Warm vertical gradient + soft top glow + vignette + film grain."""
    y = np.linspace(0, 1, H)[:, None]
    grad = (np.array(BG_TOP)[None, None, :] * (1 - y[:, :, None]) +
            np.array(BG_BOTTOM)[None, None, :] * y[:, :, None])
    img = np.repeat(grad, W, axis=1)

    yy, xx = np.mgrid[0:H, 0:W]
    glow = np.exp(-(((xx - W / 2) / (W * 0.75)) ** 2 + ((yy - H * 0.16) / (H * 0.30)) ** 2))
    img += glow[:, :, None] * np.array([26, 15, 6])[None, None, :]

    r = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2)
    img *= np.clip(1.12 - 0.35 * r, 0.35, 1.1)[:, :, None]

    rng = np.random.default_rng(1000 + seed)
    img += rng.normal(0, 3.2, img.shape)
    return Image.fromarray(np.clip(img, 0, 255).astype("uint8"), "RGB")


def draw_tracked(img, text, font, fill, cy, tracking=0.0, alpha=1.0):
    """Centred text with letter-spacing, drawn through an alpha layer."""
    if alpha <= 0.001:
        return
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    widths = [d.textlength(c, font=font) for c in text]
    total = sum(widths) + tracking * max(0, len(text) - 1)
    x = (W - total) / 2
    for ch, cw in zip(text, widths):
        d.text((x, cy), ch, font=font, fill=fill + (255,))
        x += cw + tracking
    if alpha < 1.0:
        a = layer.getchannel("A").point(lambda v: int(v * alpha))
        layer.putalpha(a)
    img.paste(layer, (0, 0), layer)


def draw_line(img, cy, width, color, alpha):
    if width <= 1:
        return
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.line([(W / 2 - width / 2, cy), (W / 2 + width / 2, cy)], fill=color + (int(255 * alpha),), width=3)
    img.paste(layer, (0, 0), layer)


def intro_frames(n=90):
    serif_big = load("serif", 96)
    sans = load("sans", 30)
    mono = load("mono", 21)
    out = f"{OUT}/frames_intro"
    os.makedirs(out, exist_ok=True)
    for i in range(n):
        p = i / (n - 1)
        img = base_frame(i)
        a1 = ease(p / 0.34)
        draw_tracked(img, "ATLAS", serif_big, WARM, 540 + 30 * (1 - a1), tracking=10, alpha=a1)
        a2 = ease((p - 0.18) / 0.32)
        draw_line(img, 690, 320 * a2, CLAY, a2)
        a3 = ease((p - 0.42) / 0.34)
        draw_tracked(img, "an autonomous agent on your phone", sans, MUTED, 748, tracking=0.6, alpha=a3)
        a4 = ease((p - 0.62) / 0.34)
        draw_tracked(img, "full capability self-test", mono, CLAY, 806, tracking=2.0, alpha=a4)
        img.save(f"{out}/f_{i:04d}.png")
    print("intro frames:", n)


def outro_frames(n=120):
    sansb = load("sansb", 34)
    sans = load("sans", 26)
    mono = load("mono", 24)
    serif = load("serif", 54)
    out = f"{OUT}/frames_outro"
    os.makedirs(out, exist_ok=True)
    lines = [
        ("49 tools · screen control · autonomous goals", MUTED),
        ("deepseek-v4.1-flash (high) via CommandCode", MUTED),
        ("on-device · no cloud middleman", MUTED),
    ]
    for i in range(n):
        p = i / (n - 1)
        img = base_frame(i + 500)
        a0 = ease(p / 0.25)
        draw_tracked(img, "Atlas", serif, WARM, 520, tracking=3, alpha=a0)
        a1 = ease((p - 0.15) / 0.3)
        draw_line(img, 610, 260 * a1, CLAY, a1)
        a2 = ease((p - 0.3) / 0.3)
        draw_tracked(img, "github.com/MdSadman2004/Atlas", mono, CLAY, 670, tracking=1.2, alpha=a2)
        for k, (text, col) in enumerate(lines):
            a = ease((p - 0.45 - k * 0.08) / 0.3)
            draw_tracked(img, text, sans, col, 760 + k * 52, tracking=0.4, alpha=a)
        a3 = ease((p - 0.72) / 0.28)
        draw_tracked(img, "D:\\\\Atlas  ·  E:\\\\Atlas", mono, (120, 112, 104), 990, tracking=1.0, alpha=a3)
        img.save(f"{out}/f_{i:04d}.png")
    print("outro frames:", n)


if __name__ == "__main__":
    intro_frames()
    outro_frames()
