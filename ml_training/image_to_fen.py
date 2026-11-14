#!/usr/bin/env python3
"""
Desktop reference of the app's screenshot → FEN pipeline (same steps as ImageToFen.kt), for
testing the TFLite model on your own screenshots or on synthetic full-board renders.

    python image_to_fen.py screenshot.png                 # print FEN for an image
    python image_to_fen.py --synthetic 50                 # accuracy on 50 rendered random boards
"""
from __future__ import annotations

import argparse
import random
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

from train_piece_classifier import CLASSES, TILE, find_fonts, random_theme, render_glyph_piece

BOARD = TILE * 8
DEFAULT_MODEL = Path(__file__).resolve().parent.parent / "app/src/main/assets/piece_classifier_int8.tflite"


def find_board_quad(gray: np.ndarray):
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blur, 40, 120)
    edges = cv2.dilate(edges, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
    contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    min_area = gray.shape[0] * gray.shape[1] * 0.15
    best, best_area = None, 0.0
    for c in contours:
        approx = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
        if len(approx) != 4 or not cv2.isContourConvex(approx):
            continue
        area = abs(cv2.contourArea(approx))
        if area < min_area or area <= best_area:
            continue
        x, y, w, h = cv2.boundingRect(approx)
        if not 0.8 <= w / h <= 1.25:
            continue
        p = approx.reshape(4, 2).astype(np.float32)
        s, d = p.sum(1), p[:, 1] - p[:, 0]
        best = np.array([p[s.argmin()], p[d.argmin()], p[s.argmax()], p[d.argmax()]], np.float32)
        best_area = area
    return best


def detect_board(rgb: np.ndarray) -> np.ndarray:
    quad = find_board_quad(cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY))
    if quad is not None:
        dst = np.array([[0, 0], [BOARD, 0], [BOARD, BOARD], [0, BOARD]], np.float32)
        return cv2.warpPerspective(rgb, cv2.getPerspectiveTransform(quad, dst), (BOARD, BOARD))
    h, w = rgb.shape[:2]
    s = min(h, w)
    crop = rgb[(h - s) // 2:(h + s) // 2, (w - s) // 2:(w + s) // 2]
    return cv2.resize(crop, (BOARD, BOARD), interpolation=cv2.INTER_AREA)


class Classifier:
    def __init__(self, model_path: str | Path = DEFAULT_MODEL):
        import tensorflow as tf
        self.it = tf.lite.Interpreter(model_path=str(model_path))
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]
        self.it.resize_tensor_input(self.inp["index"], [64, TILE, TILE, 3])
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]

    def __call__(self, board: np.ndarray):
        tiles = board.reshape(8, TILE, 8, TILE, 3).transpose(0, 2, 1, 3, 4).reshape(64, TILE, TILE, 3)
        scale, zp = self.inp["quantization"]
        q = np.clip(np.round(tiles.astype(np.float32) / 255.0 / scale + zp), -128, 127).astype(np.int8)
        self.it.set_tensor(self.inp["index"], q)
        self.it.invoke()
        raw = self.it.get_tensor(self.out["index"]).astype(np.float32)
        oscale, ozp = self.out["quantization"]
        probs = (raw - ozp) * oscale
        return [CLASSES[k] for k in probs.argmax(1)], probs.max(1)


def labels_to_placement(labels: list[str]) -> str:
    rows = []
    for r in range(8):
        row, empty = "", 0
        for c in range(8):
            l = labels[r * 8 + c]
            if l == "empty":
                empty += 1
            else:
                row += (str(empty) if empty else "") + l
                empty = 0
        rows.append(row + (str(empty) if empty else ""))
    return "/".join(rows)


def image_to_fen(path: str, clf: Classifier) -> str:
    rgb = np.asarray(Image.open(path).convert("RGB"))
    labels, conf = clf(detect_board(rgb))
    low = [i for i, c in enumerate(conf) if c < 0.6]
    if low:
        print(f"low-confidence tiles (image order): {low}")
    return labels_to_placement(labels) + " w - - 0 1"


def render_random_board(fonts, pad: int = 60) -> tuple[np.ndarray, list[str]]:
    """Synthetic 'screenshot': random position on a random theme, surrounded by UI-ish margins."""
    light, dark = random_theme()
    sq = random.randint(48, 96)
    img = Image.new("RGB", (sq * 8 + 2 * pad, sq * 8 + 2 * pad + random.randint(0, 200)),
                    tuple(random.randint(20, 60) for _ in range(3)))
    d = ImageDraw.Draw(img)
    labels = []
    pool = ["empty"] * 40 + CLASSES[1:] * 2
    for r in range(8):
        for c in range(8):
            d.rectangle([pad + c * sq, pad + r * sq, pad + (c + 1) * sq - 1, pad + (r + 1) * sq - 1],
                        fill=light if (r + c) % 2 == 0 else dark)
            l = random.choice(pool)
            labels.append(l)
            if l != "empty":
                p = render_glyph_piece(l, random.choice(fonts), int(sq * 0.9))
                img.paste(p, (pad + c * sq + (sq - p.width) // 2, pad + r * sq + (sq - p.height) // 2), p)
    return np.asarray(img), labels


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image", nargs="?")
    ap.add_argument("--model", default=str(DEFAULT_MODEL))
    ap.add_argument("--synthetic", type=int, default=0, help="evaluate on N synthetic boards")
    args = ap.parse_args()
    clf = Classifier(args.model)
    if args.image:
        print(image_to_fen(args.image, clf))
    if args.synthetic:
        random.seed(123)
        fonts = find_fonts([])
        tiles_ok = boards_ok = 0
        for _ in range(args.synthetic):
            rgb, truth = render_random_board(fonts)
            pred, _ = clf(detect_board(rgb))
            ok = sum(p == t for p, t in zip(pred, truth))
            tiles_ok += ok
            boards_ok += ok == 64
        n = args.synthetic
        print(f"tile accuracy {tiles_ok / (64 * n):.4f}, perfect boards {boards_ok}/{n}")


if __name__ == "__main__":
    main()
