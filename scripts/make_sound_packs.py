"""Synthesises the Wood, Futuristic, Soft and Arcade board sound packs (original sounds, no samples).
Usage: python scripts/make_sound_packs.py app/src/main/res/raw   (needs numpy)"""
import sys, wave, numpy as np
SR = 22050
OUT = sys.argv[1]
rng = np.random.default_rng(7)

def t(d): return np.arange(int(SR * d)) / SR
def env(n, a=0.002, d=0.08):
    x = np.arange(n) / SR
    return np.minimum(1, x / a) * np.exp(-x / d)
def tone(f, d, dec=0.08, a=0.002, kind="sine"):
    x = t(d); ph = 2 * np.pi * np.cumsum(np.broadcast_to(f, x.shape) if np.ndim(f) else np.full(x.shape, f)) / SR
    w = {"sine": np.sin(ph), "square": np.sign(np.sin(ph)) * 0.6, "tri": 2 / np.pi * np.arcsin(np.sin(ph))}[kind]
    return w * env(len(x), a, dec)
def noise(d, dec=0.02, lp=0.5):
    n = rng.standard_normal(int(SR * d)); y = np.zeros_like(n); acc = 0
    for i, v in enumerate(n): acc = acc + lp * (v - acc); y[i] = acc
    return y * env(len(n), 0.0005, dec)
def seq(*parts, gap=0.0):
    out = np.zeros(0)
    for p in parts:
        if isinstance(p, float): out = np.concatenate([out, np.zeros(int(SR * p))]); continue
        out = np.concatenate([out, p])
    return out
def mix(*xs):
    n = max(len(x) for x in xs); y = np.zeros(n)
    for x in xs: y[:len(x)] += x
    return y
def sweep(f0, f1, d, dec=0.06, kind="sine"):
    return tone(np.linspace(f0, f1, int(SR * d)), d, dec, kind=kind)
def save(name, y, vol=0.8):
    y = y / (np.max(np.abs(y)) + 1e-9) * vol
    y = np.concatenate([y, np.zeros(int(SR * 0.01))])
    with wave.open(f"{OUT}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype("<i2").tobytes())

def knock(f=190, d=0.09):  # wooden thump: low resonance + click
    return mix(tone(f, d, 0.025), 0.6 * tone(f * 2.7, d, 0.012), 0.5 * noise(0.02, 0.006, 0.6))

packs = {
  "wood": {
    "move": knock(),
    "capture": seq(knock(170), 0.035, knock(230)),
    "castle": seq(knock(200), 0.07, knock(200)),
    "check": mix(knock(200), seq(0.05, tone(880, 0.12, 0.04) * 0.6)),
    "game_end": seq(tone(660, 0.14, 0.06), tone(554, 0.14, 0.06), tone(440, 0.3, 0.12)),
  },
  "futuristic": {
    "move": sweep(600, 1250, 0.07, 0.05),
    "capture": mix(sweep(1400, 380, 0.12, 0.07), 0.3 * noise(0.08, 0.03, 0.9)),
    "castle": seq(sweep(500, 1000, 0.06, 0.04), 0.02, sweep(700, 1400, 0.06, 0.04)),
    "check": seq(tone(1320, 0.08, 0.05), 0.02, tone(1760, 0.1, 0.06)),
    "game_end": seq(sweep(400, 800, 0.12, 0.1), sweep(600, 1200, 0.12, 0.1), sweep(800, 1600, 0.3, 0.2)),
  },
  "soft": {
    "move": tone(440, 0.16, 0.06, a=0.01),
    "capture": mix(tone(330, 0.2, 0.08, a=0.01), 0.5 * tone(495, 0.2, 0.06, a=0.01)),
    "castle": seq(tone(440, 0.1, 0.05, a=0.01), 0.04, tone(523, 0.12, 0.06, a=0.01)),
    "check": mix(tone(880, 0.3, 0.12, a=0.01), 0.6 * tone(1320, 0.3, 0.1, a=0.01)),
    "game_end": mix(tone(523, 0.7, 0.3, a=0.02), tone(659, 0.7, 0.3, a=0.02), tone(784, 0.7, 0.3, a=0.02)),
  },
  "arcade": {
    "move": tone(880, 0.05, 0.04, kind="square"),
    "capture": mix(sweep(900, 200, 0.12, 0.08, kind="square"), 0.4 * noise(0.1, 0.05, 0.95)),
    "castle": seq(tone(660, 0.05, 0.04, kind="square"), 0.03, tone(990, 0.05, 0.04, kind="square")),
    "check": seq(tone(988, 0.06, 0.05, kind="square"), tone(1319, 0.06, 0.05, kind="square"), tone(988, 0.06, 0.05, kind="square")),
    "game_end": seq(*[tone(f, 0.1, 0.08, kind="square") for f in (523, 659, 784)], tone(1047, 0.3, 0.2, kind="square")),
  },
}
for pack, sounds in packs.items():
    for k, y in sounds.items():
        save(f"sfx_{pack}_{k}", y, 0.55 if pack == "soft" else 0.8)
print("ok")
