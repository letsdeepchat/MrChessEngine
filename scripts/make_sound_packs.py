"""Synthesises the five board sound packs (original procedural sounds, no samples) as 16-bit PCM WAVs.

Usage: python scripts/make_sound_packs.py app/src/main/res/raw   (needs numpy)

Standard   warm low-passed thud (180 -> 90 Hz exponential drop, 45 ms) / 260 Hz + noise snap /
           587 + 880 Hz chime / major-chord game end
Wood       hollow knock: 140 Hz body + 290 Hz woody formant, 40 ms decay
Futuristic FM synth pluck sweeping 440 -> 660 Hz
Soft       muted felt tap: 120 Hz soft sine thud
Arcade     8-bit square-wave blips
"""
import sys, wave
import numpy as np

SR = 44100
OUT = sys.argv[1]
rng = np.random.default_rng(11)


def n(d): return int(SR * d)


def env(length, attack, decay):
    t = np.arange(length) / SR
    a = np.minimum(1.0, t / max(attack, 1e-4))
    return a * np.exp(-t / decay)


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x); acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc; y[i] = acc
    return y


def highpass(x, cutoff): return x - lowpass(x, cutoff)


def osc(freq, d, kind="sine"):
    f = np.broadcast_to(np.asarray(freq, dtype=float), (n(d),)) if np.ndim(freq) else np.full(n(d), float(freq))
    ph = 2 * np.pi * np.cumsum(f) / SR
    if kind == "sine": return np.sin(ph)
    if kind == "square": return np.where(np.sin(ph) >= 0, 1.0, -1.0)
    if kind == "tri": return 2 / np.pi * np.arcsin(np.sin(ph))
    raise ValueError(kind)


def expsweep(f0, f1, d): return f0 * (f1 / f0) ** (np.arange(n(d)) / n(d))


def noise(d, decay, lp=None, hp=None):
    x = rng.standard_normal(n(d))
    if lp: x = lowpass(x, lp)
    if hp: x = highpass(x, hp)
    return x * env(n(d), 0.0005, decay)


def mix(*xs):
    y = np.zeros(max(len(x) for x in xs))
    for x in xs: y[:len(x)] += x
    return y


def at(x, delay):  # place a sound after a delay (s)
    return np.concatenate([np.zeros(n(delay)), x])


def room(x, amount=0.12):  # tiny "table" ambience: two short combs
    y = np.concatenate([x, np.zeros(n(0.08))])
    for dt, g in ((0.011, amount), (0.023, amount * 0.6)):
        d = n(dt); z = y.copy(); z[d:] += g * y[:-d]; y = z
    return y


def save(name, y, peak=0.85):
    y = y - np.mean(y)
    y = y / (np.max(np.abs(y)) + 1e-9) * peak
    fade = n(0.005); y[-fade:] *= np.linspace(1, 0, fade)
    with wave.open(f"{OUT}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype("<i2").tobytes())


# ---------------------------------------------------------------- building blocks
def thud(f0=180, f1=90, d=0.12, decay=0.045, cutoff=1400):
    body = osc(expsweep(f0, f1, d), d) * env(n(d), 0.001, decay)
    click = noise(0.02, 0.004, lp=4000)
    return lowpass(mix(body, 0.35 * click), cutoff)


def snap(f=260, d=0.14):
    tone = osc(expsweep(f * 1.2, f * 0.8, d), d) * env(n(d), 0.0008, 0.06)
    burst = noise(0.06, 0.012, lp=6000, hp=600)
    return mix(tone, 0.9 * burst, at(0.5 * noise(0.04, 0.008, lp=5000, hp=900), 0.018))


def chime(freqs, d=0.55, decay=0.22):
    return mix(*[osc(f, d) * env(n(d), 0.002, decay / (1 + i * 0.4)) * (1 / (1 + i * 0.5)) for i, f in enumerate(freqs)])


def knock(body=140, formant=290, decay=0.04, d=0.12):
    b = osc(expsweep(body * 1.15, body, d), d) * env(n(d), 0.0008, decay)
    f = osc(formant, d) * env(n(d), 0.0005, decay * 0.55)
    tick = noise(0.015, 0.003, lp=7000, hp=1500)
    return mix(b, 0.7 * f, 0.4 * tick)


def pluck(f0=440, f1=660, d=0.16, decay=0.06, index=2.5):
    carrier = expsweep(f0, f1, d)
    mod = osc(carrier * 2.0, d) * index * carrier * np.exp(-np.arange(n(d)) / SR / 0.03)
    return osc(carrier + mod, d) * env(n(d), 0.001, decay)


def felt(f=120, d=0.16, decay=0.05):
    return lowpass(mix(osc(expsweep(f * 1.3, f, d), d) * env(n(d), 0.004, decay), 0.15 * noise(0.03, 0.006, lp=1500)), 900)


def blip(f, d=0.06, decay=0.04, duty="square"):
    return osc(f, d, duty) * env(n(d), 0.0005, decay) * 0.6


packs = {
    "standard": {
        "move": room(thud()),
        "capture": room(snap()),
        "castle": room(mix(thud(), at(thud(170, 85), 0.085))),
        "check": room(mix(0.6 * thud(), at(chime([587.33, 880.0]), 0.02)), 0.18),
        "game_end": room(mix(chime([523.25, 659.25, 783.99], 1.1, 0.5), at(chime([1046.5], 0.9, 0.4), 0.12)), 0.2),
    },
    "wood": {
        "move": room(knock(), 0.18),
        "capture": room(mix(knock(150, 310), at(knock(125, 270, 0.035), 0.045)), 0.18),
        "castle": room(mix(knock(), at(knock(150, 300), 0.09)), 0.18),
        "check": room(mix(knock(), at(knock(520, 1040, 0.05, 0.2), 0.05)), 0.18),
        "game_end": room(mix(knock(392, 784, 0.12, 0.4), at(knock(330, 660, 0.12, 0.4), 0.16), at(knock(262, 524, 0.2, 0.6), 0.32)), 0.2),
    },
    "futuristic": {
        "move": room(pluck(), 0.15),
        "capture": room(mix(pluck(660, 330, 0.18, 0.07, 3.5), 0.25 * noise(0.1, 0.03, lp=8000, hp=2000)), 0.15),
        "castle": room(mix(pluck(440, 660), at(pluck(554, 830), 0.07)), 0.15),
        "check": room(mix(pluck(880, 1320, 0.2, 0.09, 1.5), at(pluck(1175, 1760, 0.22, 0.1, 1.2), 0.06)), 0.15),
        "game_end": room(mix(pluck(440, 660, 0.3, 0.15), at(pluck(554, 830, 0.3, 0.15), 0.12), at(pluck(660, 990, 0.6, 0.3), 0.24)), 0.2),
    },
    "soft": {
        "move": room(felt(), 0.1),
        "capture": room(mix(felt(110), at(felt(150, 0.12, 0.04), 0.04)), 0.1),
        "castle": room(mix(felt(), at(felt(135), 0.09)), 0.1),
        "check": room(mix(felt(), at(lowpass(chime([440.0, 660.0], 0.5, 0.2), 2500), 0.03)), 0.12),
        "game_end": room(lowpass(chime([261.6, 329.6, 392.0], 1.0, 0.45), 2200), 0.15),
    },
    "arcade": {
        "move": blip(880),
        "capture": mix(blip(660, 0.05), at(blip(330, 0.08, 0.05), 0.045), 0.3 * noise(0.08, 0.03)),
        "castle": mix(blip(660, 0.05), at(blip(990, 0.05), 0.06)),
        "check": mix(blip(988, 0.06), at(blip(1319, 0.06), 0.07), at(blip(988, 0.06), 0.14)),
        "game_end": mix(*[at(blip(f, 0.1, 0.08), i * 0.11) for i, f in enumerate((523, 659, 784))], at(blip(1047, 0.35, 0.2), 0.33)),
    },
}
for pack, sounds in packs.items():
    for kind, y in sounds.items():
        save(f"sfx_{pack}_{kind}", y, 0.6 if pack == "soft" else 0.85)
print("ok")
