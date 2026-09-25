#!/usr/bin/env python
"""Atlas score — a dark cinematic synth bed in the spirit of Tron: Ares (original material).

Not a copy of any existing track: a slow industrial pulse, sub-bass swells, a filtered
16th arpeggio, gated percussion and a wide pad, built from scratch with numpy.

Writes:  E:/Atlas/atlas-score.wav    (stereo, 44.1 kHz, 16-bit)
"""
import math
import wave

import numpy as np

SR = 44100
BPM = 104.0
BEAT = 60.0 / BPM
BAR = 4 * BEAT
BARS = 19
DUR = BARS * BAR
N = int(DUR * SR)
rng = np.random.default_rng(7)

t = np.arange(N) / SR


def env(n, a=0.005, d=0.2, s=0.0, r=0.1, sus=1.0):
    """Simple ADSR over n samples."""
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    s_n = max(0, n - a_n - d_n - r_n)
    e = np.concatenate([
        np.linspace(0, 1, a_n, endpoint=False),
        np.linspace(1, sus, d_n, endpoint=False),
        np.full(s_n, sus),
        np.linspace(sus, 0, n - a_n - d_n - s_n),
    ])
    return e[:n]


def saw(freq, n, detune=0.0):
    tt = np.arange(n) / SR
    f = freq * (1 + detune)
    ph = (tt * f) % 1.0
    return 2 * ph - 1


def lowpass(x, cutoff):
    """One-pole lowpass — cheap analog-ish colour."""
    a = math.exp(-2 * math.pi * cutoff / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc = (1 - a) * x[i] + a * acc
        y[i] = acc
    return y


def place(buf, sig, at_sec, gain=1.0):
    i = int(at_sec * SR)
    if i < 0 or i >= len(buf):
        return
    j = min(len(buf), i + len(sig))
    buf[i:j] += sig[: j - i] * gain


mix = np.zeros(N)

# ---------------------------------------------------------------- sub bass / pulse
# Root notes: D, D, F, C  (one per two bars), with a slow swell per note.
roots = [36.71, 36.71, 43.65, 32.70]
for b in range(BARS):
    note = roots[(b // 2) % len(roots)]
    start = b * BAR
    n = int(BAR * SR)
    tt = np.arange(n) / SR
    # sub sine with a gentle pitch drop
    sub = np.sin(2 * np.pi * note * tt * (1 - 0.004 * tt))
    # analog bass layer: two detuned saws through a lowpass that opens over the bar
    bass = 0.5 * (saw(note * 2, n, -0.004) + saw(note * 2, n, 0.004))
    bass = lowpass(bass, 320)
    e = env(n, a=0.6, d=0.4, s=0.75, r=0.6, sus=1.0)
    # sidechain-style ducking on the beat grid
    duck = np.ones(n)
    for k in range(int(BAR / BEAT)):
        kk = int(k * BEAT * SR)
        dlen = int(0.16 * SR)
        duck[kk:kk + dlen] *= np.linspace(0.25, 1.0, dlen)[: max(0, min(dlen, n - kk))]
    place(mix, (sub * 0.55 + bass * 0.30) * e * duck, start)

# ---------------------------------------------------------------- arpeggio (16ths)
arp_notes = [73.42, 87.31, 98.00, 110.00, 98.00, 87.31, 73.42, 65.41]  # D F G A G F D C
for b in range(4, BARS - 2):
    for step in range(16):
        idx = (b * 16 + step) % len(arp_notes)
        f = arp_notes[idx]
        at = b * BAR + step * BEAT / 4
        n = int(BEAT / 4 * SR * 1.6)
        sig = 0.5 * (saw(f, n, -0.006) + saw(f, n, 0.006))
        sig = lowpass(sig, 2200 if (b % 4) < 2 else 3200)
        e = env(n, a=0.004, d=0.05, s=0.35, r=0.06, sus=0.35)
        # stereo spread via tiny delay on the right channel later
        place(mix, sig * e * 0.16, at)

# ---------------------------------------------------------------- pad / atmosphere
chord = [146.83, 174.61, 220.00, 293.66]  # D minor spread
pad = np.zeros(N)
for f in chord:
    sig = 0.33 * (saw(f, N, -0.003) + saw(f, N, 0.003) + saw(f, N, 0.001))
    pad += sig
pad = lowpass(pad, 900)
swell = np.concatenate([
    np.linspace(0, 1, int(6 * SR)),
    np.ones(max(0, N - int(12 * SR))),
    np.linspace(1, 0.15, int(6 * SR)),
])[:N]
mix += pad * swell * 0.09

# ---------------------------------------------------------------- percussion
def kick(n, gain=1.0):
    tt = np.arange(n) / SR
    f = 110 * np.exp(-18 * tt) + 42
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-7 * tt)
    click = rng.normal(0, 1, n) * np.exp(-260 * tt) * 0.35
    return (body + click) * gain


def snare(n, gain=1.0):
    tt = np.arange(n) / SR
    body = np.sin(2 * np.pi * 190 * tt) * np.exp(-26 * tt) * 0.5
    noise = rng.normal(0, 1, n) * np.exp(-16 * tt)
    return (body + noise * 0.8) * gain


def hat(n, gain=1.0, decay=140.0):
    tt = np.arange(n) / SR
    return rng.normal(0, 1, n) * np.exp(-decay * tt) * gain


half = int(2 * BEAT * SR)
for b in range(2, BARS - 1):
    bar_t = b * BAR
    heavy = b >= 6 and (b % 8) < 6
    # half-time kick + snare
    place(mix, kick(half, 1.0), bar_t)
    place(mix, kick(half, 0.8), bar_t + 2 * BEAT + BEAT / 2) if heavy else None
    place(mix, snare(int(0.6 * SR), 0.5 if heavy else 0.32), bar_t + 2 * BEAT)
    # hats on 8ths
    for k in range(8):
        g = 0.16 if k % 2 else 0.10
        place(mix, hat(int(0.09 * SR), g), bar_t + k * BEAT / 2)

# riser into the first heavy bar
riser_at = 6 * BAR - 2 * BAR
tt = np.arange(int(2 * BAR * SR)) / SR
riser = rng.normal(0, 1, len(tt)) * np.linspace(0, 0.16, len(tt))
riser = lowpass(riser, 2400)
place(mix, riser, riser_at)

# braam hit on the drop and near the end
for at, g in ((6 * BAR, 0.5), (17 * BAR, 0.45)):
    n = int(3.2 * SR)
    tt = np.arange(n) / SR
    hit = sum(saw(f, n, d) for f, d in ((36.71, -0.01), (36.71, 0.012), (55.0, 0.0)))
    hit = lowpass(hit, 700) * np.exp(-1.1 * tt)
    place(mix, hit * g * 0.5, at)

# ---------------------------------------------------------------- stereo + master
delay = int(0.011 * SR)
left = mix.copy()
right = np.concatenate([np.zeros(delay), mix[:-delay]])
stereo = np.stack([left, right], axis=1)

# gentle bus compression + soft clip
stereo = np.tanh(stereo * 1.25) * 0.9
peak = np.max(np.abs(stereo)) or 1.0
stereo = stereo / peak * 0.89

# fade in/out
fi = int(1.2 * SR)
fo = int(2.6 * SR)
stereo[:fi] *= np.linspace(0, 1, fi)[:, None]
stereo[-fo:] *= np.linspace(1, 0, fo)[:, None]

pcm = (stereo * 32767).astype("<i2")
out = "E:/Atlas/atlas-score.wav"
with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print(f"wrote {out}  {N / SR:.1f}s  {N * 4 / 1024:.0f} KB")
