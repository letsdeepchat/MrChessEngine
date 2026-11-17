#!/usr/bin/env python3
"""Filters the Lichess puzzle database (CC0, https://database.lichess.org/#puzzles) down to a small
set of grandmaster-level puzzles for the app. Reads the CSV on stdin, writes the asset CSV to argv[1].

Kept: rating >= 2500, rating deviation <= 90, popularity >= 80, >= 1500 plays, 4+ moves in the line.
Up to 120 puzzles per 100-point band from 2500 upward, most-played first, so every level is covered.
"""
import csv, sys, collections

PER_BAND = 120
bands = collections.defaultdict(list)
reader = csv.reader(sys.stdin)
header = next(reader)
idx = {h: i for i, h in enumerate(header)}
for row in reader:
    try:
        rating = int(row[idx["Rating"]]); rd = int(row[idx["RatingDeviation"]])
        pop = int(row[idx["Popularity"]]); plays = int(row[idx["NbPlays"]])
    except (ValueError, IndexError):
        continue
    moves = row[idx["Moves"]].split()
    if rating < 2500 or rd > 90 or pop < 80 or plays < 1500 or len(moves) < 4:
        continue
    band = min(rating // 100 * 100, 3100)
    b = bands[band]
    b.append((plays, row[idx["PuzzleId"]], row[idx["FEN"]], " ".join(moves), rating, row[idx["Themes"]]))
    if len(b) > PER_BAND * 4:
        b.sort(reverse=True); del b[PER_BAND:]

out = []
for band in sorted(bands):
    b = sorted(bands[band], reverse=True)[:PER_BAND]
    out += b
    print(f"band {band}: {len(b)}", file=sys.stderr)
out.sort(key=lambda r: r[4])
with open(sys.argv[1], "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["id", "fen", "moves", "rating", "themes"])
    for plays, pid, fen, moves, rating, themes in out:
        w.writerow([pid, fen, moves, rating, themes])
print(f"total {len(out)}", file=sys.stderr)
