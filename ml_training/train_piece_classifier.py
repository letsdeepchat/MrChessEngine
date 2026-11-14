#!/usr/bin/env python3
"""
Train the 13-class chess tile classifier used by the app's screenshot import and export it
as a fully-integer (INT8) TFLite model.

Classes (order matters, mirrored in ImageToFen.kt):
    empty, P, N, B, R, Q, K, p, n, b, r, q, k        (upper case = White)

Training data is generated synthetically: every tile is rendered on random board themes with
random piece styles, scale/offset jitter, last-move highlights, move-hint dots, coordinate
labels, neighbour bleed (imperfect warps), blur, noise and JPEG artefacts. Two piece sources:

  1. Unicode chess glyphs rendered from any font that has them (DejaVu Sans ships with
     matplotlib, so this works out of the box).
  2. Optional --piece-sets DIR: sub-folders of PNG piece sets named wK.png, wQ.png ... bP.png
     (the naming used by most open-source sets). Add the sets used by the sites/apps you take
     screenshots from — this is the biggest accuracy win on real screenshots.

Optionally mix in real labelled tiles with --real-dir DIR/<class>/*.png (class names as above,
use "w_P"/"b_p" style folder names on case-insensitive file systems; see CLASS_DIRS).

Usage:
    python train_piece_classifier.py --out ../app/src/main/assets/piece_classifier_int8.tflite
    python train_piece_classifier.py --piece-sets ./piece_sets --samples-per-class 6000 --epochs 15
"""
from __future__ import annotations

import argparse
import glob
import io
import os
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

TILE = 64
CLASSES = ["empty", "P", "N", "B", "R", "Q", "K", "p", "n", "b", "r", "q", "k"]
# Folder names for --real-dir / --piece-sets that survive case-insensitive file systems.
CLASS_DIRS = {"empty": "empty", **{c: ("w_" if c.isupper() else "b_") + c.upper() for c in CLASSES[1:]}}
SOLID_GLYPH = {"K": "\u265A", "Q": "\u265B", "R": "\u265C", "B": "\u265D", "N": "\u265E", "P": "\u265F"}
OUTLINE_GLYPH = {"K": "\u2654", "Q": "\u2655", "R": "\u2656", "B": "\u2657", "N": "\u2658", "P": "\u2659"}

# (light, dark) square colours of common board themes, plus random ones generated at runtime.
THEMES = [
    ((238, 238, 210), (118, 150, 86)),   # green
    ((240, 217, 181), (181, 136, 99)),   # brown
    ((222, 227, 230), (140, 162, 173)),  # blue-grey
    ((234, 233, 210), (75, 115, 153)),   # blue
    ((232, 235, 239), (125, 135, 150)),  # grey
    ((240, 241, 240), (196, 216, 228)),  # ice
    ((255, 206, 158), (209, 139, 71)),   # orange wood
    ((227, 193, 111), (184, 139, 74)),   # walnut
    ((239, 239, 239), (170, 170, 170)),  # newspaper-ish
    ((159, 144, 176), (125, 74, 141)),   # purple
]
HIGHLIGHTS = [(246, 246, 105), (186, 202, 68), (255, 170, 0), (220, 60, 60), (100, 180, 255)]


# --------------------------------------------------------------------------- piece sources

def find_fonts(extra: list[str]) -> list[str]:
    fonts = list(extra)
    try:
        import matplotlib
        fonts.append(os.path.join(matplotlib.get_data_path(), "fonts", "ttf", "DejaVuSans.ttf"))
    except ImportError:
        pass
    for pattern in ["/usr/share/fonts/**/DejaVuSans*.ttf", "/usr/share/fonts/**/FreeSerif*.ttf",
                    "/usr/share/fonts/**/NotoSansSymbols2*.ttf", "/System/Library/Fonts/**/*.ttc",
                    "C:/Windows/Fonts/seguisym.ttf"]:
        fonts += glob.glob(pattern, recursive=True)
    ok = []
    for f in dict.fromkeys(fonts):
        try:
            font = ImageFont.truetype(f, 40)
            # Must actually contain the glyph (not a tofu box): compare against a missing char.
            if font.getbbox("\u265A") != font.getbbox("\uFFFF") and font.getbbox("\u265A")[2] > 10:
                ok.append(f)
        except Exception:
            pass
    if not ok:
        raise SystemExit("No font with chess glyphs found. pip install matplotlib, or pass --font path.ttf")
    return ok


def load_piece_sets(root: str | None) -> list[dict[str, Image.Image]]:
    sets = []
    if not root:
        return sets
    for d in sorted(Path(root).iterdir()):
        if not d.is_dir():
            continue
        s = {}
        for c in CLASSES[1:]:
            name = ("w" if c.isupper() else "b") + c.upper()
            for ext in (".png", ".webp"):
                p = d / (name + ext)
                if p.exists():
                    s[c] = Image.open(p).convert("RGBA")
        if len(s) == 12:
            sets.append(s)
        else:
            print(f"Skipping piece set {d} (found {len(s)}/12 pieces)")
    print(f"Loaded {len(sets)} PNG piece sets")
    return sets


def render_glyph_piece(cls: str, font_path: str, size: int) -> Image.Image:
    """RGBA piece drawn from font glyphs in one of several styles."""
    white = cls.isupper()
    kind = cls.upper()
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    font = ImageFont.truetype(font_path, int(size * random.uniform(0.80, 0.95)))
    solid = SOLID_GLYPH[kind]
    bbox = d.textbbox((0, 0), solid, font=font)
    x = (size - (bbox[2] - bbox[0])) / 2 - bbox[0]
    y = (size - (bbox[3] - bbox[1])) / 2 - bbox[1]
    style = random.random()
    if white:
        fill = tuple(random.randint(235, 255) for _ in range(3)) + (255,)
        edge = tuple(random.randint(0, 50) for _ in range(3)) + (255,)
        if style < 0.5:  # solid glyph with dark outline (what the app itself draws)
            d.text((x, y), solid, font=font, fill=fill, stroke_width=max(1, size // 28), stroke_fill=edge)
        else:            # white fill under the outline glyph
            d.text((x, y), solid, font=font, fill=fill)
            d.text((x, y), OUTLINE_GLYPH[kind], font=font, fill=edge)
    else:
        fill = tuple(random.randint(0, 45) for _ in range(3)) + (255,)
        edge = tuple(random.randint(120, 230) for _ in range(3)) + (255,)
        sw = max(1, size // 40) if style < 0.6 else 0
        d.text((x, y), solid, font=font, fill=fill, stroke_width=sw, stroke_fill=edge)
    return canvas


# --------------------------------------------------------------------------- tile synthesis

def random_theme():
    if random.random() < 0.2:
        light = tuple(random.randint(170, 255) for _ in range(3))
        dark = tuple(max(0, c - random.randint(50, 130)) for c in light)
        return light, dark
    light, dark = random.choice(THEMES)
    jitter = lambda c: tuple(int(np.clip(v + random.randint(-12, 12), 0, 255)) for v in c)
    return jitter(light), jitter(dark)


def make_tile(cls: str, fonts: list[str], piece_sets: list[dict]) -> Image.Image:
    margin = TILE // 4
    big = TILE + 2 * margin
    light, dark = random_theme()
    is_light = random.random() < 0.5
    sq_col = light if is_light else dark
    other = dark if is_light else light
    img = Image.new("RGB", (big, big), other)  # neighbours have the other colour
    d = ImageDraw.Draw(img)
    d.rectangle([margin, margin, margin + TILE - 1, margin + TILE - 1], fill=sq_col)

    if random.random() < 0.15:  # last-move / check / selection highlight
        hl = random.choice(HIGHLIGHTS)
        a = random.uniform(0.35, 0.7)
        col = tuple(int(sq_col[i] * (1 - a) + hl[i] * a) for i in range(3))
        d.rectangle([margin, margin, margin + TILE - 1, margin + TILE - 1], fill=col)

    if cls != "empty":
        psize = int(TILE * random.uniform(0.78, 1.0))
        if piece_sets and random.random() < 0.7:
            piece = random.choice(piece_sets)[cls].resize((psize, psize), Image.LANCZOS)
        else:
            piece = render_glyph_piece(cls, random.choice(fonts), psize)
        ox = margin + (TILE - psize) // 2 + random.randint(-3, 3)
        oy = margin + (TILE - psize) // 2 + random.randint(-3, 3)
        if random.random() < 0.08:  # piece being dragged / drop shadow
            shadow = Image.new("RGBA", piece.size, (0, 0, 0, 0))
            shadow.putalpha(piece.getchannel("A").point(lambda v: v // 3))
            img.paste(shadow, (ox + 2, oy + 2), shadow)
        img.paste(piece, (ox, oy), piece)

    if random.random() < 0.1:  # legal-move hint: dot on empty squares, ring on captures
        c = margin + TILE // 2
        r = TILE // 6 if cls == "empty" else TILE // 2 - 2
        hint = (0, 0, 0) if random.random() < 0.7 else (80, 80, 80)
        overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
        od = ImageDraw.Draw(overlay)
        if cls == "empty":
            od.ellipse([c - r, c - r, c + r, c + r], fill=hint + (70,))
        else:
            od.ellipse([c - r, c - r, c + r, c + r], outline=hint + (70,), width=TILE // 10)
        img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
        d = ImageDraw.Draw(img)

    if random.random() < 0.2:  # board coordinate label
        try:
            f = ImageFont.truetype(random.choice(fonts), TILE // 6)
        except Exception:
            f = ImageFont.load_default()
        ch = random.choice("abcdefgh12345678")
        pos = random.choice([(margin + 2, margin + 1), (margin + TILE - 10, margin + TILE - 14)])
        d.text(pos, ch, font=f, fill=other)

    # Imperfect warp: crop with a small random offset/scale around the square.
    s = random.randint(TILE - 4, TILE + 4)
    cx = margin + TILE // 2 + random.randint(-3, 3)
    cy = margin + TILE // 2 + random.randint(-3, 3)
    img = img.crop((cx - s // 2, cy - s // 2, cx - s // 2 + s, cy - s // 2 + s))
    # Simulate source resolution: down- then up-sample.
    if random.random() < 0.5:
        lo = random.randint(20, 56)
        img = img.resize((lo, lo), Image.BILINEAR)
    img = img.resize((TILE, TILE), Image.BILINEAR)

    if random.random() < 0.3:
        img = img.filter(ImageFilter.GaussianBlur(random.uniform(0.3, 1.2)))
    arr = np.asarray(img).astype(np.float32)
    arr = arr * random.uniform(0.8, 1.15) + random.uniform(-20, 20)  # contrast / brightness
    if random.random() < 0.4:
        arr += np.random.normal(0, random.uniform(2, 8), arr.shape)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    if random.random() < 0.5:
        b = io.BytesIO()
        img.save(b, "JPEG", quality=random.randint(35, 95))
        img = Image.open(io.BytesIO(b.getvalue())).convert("RGB")
    return img


def load_real_tiles(root: str | None):
    xs, ys = [], []
    if not root:
        return xs, ys
    for k, c in enumerate(CLASSES):
        for p in glob.glob(os.path.join(root, CLASS_DIRS[c], "*")):
            try:
                xs.append(np.asarray(Image.open(p).convert("RGB").resize((TILE, TILE))))
                ys.append(k)
            except Exception:
                pass
    print(f"Loaded {len(xs)} real tiles")
    return xs, ys


def build_dataset(n_per_class: int, fonts, piece_sets, real_dir, seed: int):
    random.seed(seed)
    np.random.seed(seed)
    X = np.zeros((n_per_class * len(CLASSES), TILE, TILE, 3), np.uint8)
    y = np.zeros(len(X), np.int64)
    i = 0
    for k, c in enumerate(CLASSES):
        for _ in range(n_per_class):
            X[i] = np.asarray(make_tile(c, fonts, piece_sets))
            y[i] = k
            i += 1
    rx, ry = load_real_tiles(real_dir)
    if rx:
        reps = max(1, n_per_class // max(1, len(rx) // len(CLASSES)) // 4)  # upweight real data
        X = np.concatenate([X] + [np.stack(rx)] * reps)
        y = np.concatenate([y] + [np.array(ry)] * reps)
    perm = np.random.permutation(len(X))
    return X[perm], y[perm]


# --------------------------------------------------------------------------- model

def build_model():
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers

    def block(x, f):
        x = layers.Conv2D(f, 3, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        x = layers.Conv2D(f, 3, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        return layers.MaxPooling2D()(x)

    inp = keras.Input((TILE, TILE, 3), name="tile")  # float in [0, 1]
    x = block(inp, 24)
    x = block(x, 48)
    x = block(x, 96)
    x = layers.Conv2D(128, 3, padding="same", activation="relu")(x)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.3)(x)
    out = layers.Dense(len(CLASSES), activation="softmax", name="probs")(x)
    model = keras.Model(inp, out)
    model.compile(optimizer=keras.optimizers.Adam(2e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def export_int8(model, rep_x: np.ndarray, out_path: str):
    import tensorflow as tf

    def representative():
        for i in range(min(500, len(rep_x))):
            yield [rep_x[i:i + 1].astype(np.float32) / 255.0]

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = representative
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_input_type = tf.int8
    conv.inference_output_type = tf.int8
    tfl = conv.convert()
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    Path(out_path).write_bytes(tfl)
    print(f"Wrote {out_path} ({len(tfl) / 1024:.0f} KiB)")
    return tfl


def eval_tflite(path: str, X: np.ndarray, y: np.ndarray) -> float:
    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=path)
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    scale, zp = inp["quantization"]
    it.resize_tensor_input(inp["index"], [len(X), TILE, TILE, 3])
    it.allocate_tensors()
    q = np.clip(np.round(X.astype(np.float32) / 255.0 / scale + zp), -128, 127).astype(np.int8)
    it.set_tensor(inp["index"], q)
    it.invoke()
    pred = it.get_tensor(out["index"]).argmax(1)
    return float((pred == y).mean())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "app/src/main/assets/piece_classifier_int8.tflite"))
    ap.add_argument("--samples-per-class", type=int, default=4000)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--font", action="append", default=[], help="extra font(s) with chess glyphs")
    ap.add_argument("--piece-sets", help="folder of PNG piece-set sub-folders (wK.png ... bP.png)")
    ap.add_argument("--real-dir", help="folder of real labelled tiles: <dir>/<class_dir>/*.png")
    ap.add_argument("--save-samples", help="write a contact sheet of generated tiles here (PNG)")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    fonts = find_fonts(args.font)
    print("Fonts:", *fonts, sep="\n  ")
    sets = load_piece_sets(args.piece_sets)

    X, y = build_dataset(args.samples_per_class, fonts, sets, args.real_dir, args.seed)
    Xv, yv = build_dataset(max(200, args.samples_per_class // 10), fonts, sets, None, args.seed + 1)
    print(f"train {X.shape}, val {Xv.shape}")
    if args.save_samples:
        sheet = Image.new("RGB", (TILE * 13, TILE * 8))
        for k in range(13):
            idx = np.where(y == k)[0][:8]
            for r, j in enumerate(idx):
                sheet.paste(Image.fromarray(X[j]), (k * TILE, r * TILE))
        sheet.save(args.save_samples)

    import tensorflow as tf
    from tensorflow import keras
    model = build_model()
    model.summary(print_fn=lambda s: None)
    cb = [keras.callbacks.ReduceLROnPlateau(patience=2, factor=0.5, verbose=1),
          keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)]
    model.fit(X.astype(np.float32) / 255.0, y, validation_data=(Xv.astype(np.float32) / 255.0, yv),
              epochs=args.epochs, batch_size=args.batch, callbacks=cb, verbose=2)
    float_acc = model.evaluate(Xv.astype(np.float32) / 255.0, yv, verbose=0)[1]
    export_int8(model, X, args.out)
    int8_acc = eval_tflite(args.out, Xv, yv)
    print(f"Validation accuracy: float {float_acc:.4f}  int8 {int8_acc:.4f}")


if __name__ == "__main__":
    main()
