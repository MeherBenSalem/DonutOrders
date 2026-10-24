#!/usr/bin/env python3
"""Measures card crops from the captures: the chest menu of each screenshot joined with the tooltip that is open in it.

Tooltips in the vanilla client have a near-black purple background (about #120412): green almost zero, red and blue
nearly equal and dark. The darkened world behind menus (cherry leaves included) always keeps some green.
Writes marketing/source/crops.json.
Usage (repository root): python3 tools/marketing/crops.py
"""
import json
import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHOTS = ROOT / "marketing/images/screenshots"
OUT = ROOT / "marketing/source/crops.json"
W, H, SCALE = 1920, 1080, 3

# Screenshot: rows of the open chest menu.
MENUS = {
    "board-hover": 6, "board-book-hover": 6, "board-sort": 6, "board-category": 6, "deliver-filled": 6, "catalog": 6,
    "create-insight": 6, "create-confirm": 6, "enchant-picker": 6, "my-orders": 6, "my-orders-collect": 6, "edit": 6,
    "admin-board": 6, "sell-confirm": 3,
}

# Screenshots that also get a crop of the tooltip alone ("<name>-tip").
TOOLTIP_ONLY = {"board-category"}


def menu_box(rows):
    """The chest part of a menu (title and rows, not the player's inventory) at GUI scale 3."""
    left = (W // SCALE - 176) // 2
    top = (H // SCALE - (114 + rows * 18)) // 2
    return [left * SCALE, top * SCALE, 176 * SCALE, (17 + rows * 18 + 7) * SCALE]


def densest_run(counts, minimum, gap):
    """The run of indexes (gaps up to `gap` allowed) with the most mask pixels, ignoring sparse indexes."""
    best, run, total, best_total, last = None, None, 0, 0, None
    for index in sorted(i for i, n in counts.items() if n >= minimum):
        if last is None or index - last > gap:
            run, total = [index, index], 0
        run[1] = index
        total += counts[index]
        if total > best_total:
            best, best_total = list(run), total
        last = index
    return best


def tooltip_box(image):
    pixels = image.load()
    points = []
    for y in range(0, H, 2):
        for x in range(0, W, 2):
            r, g, b = pixels[x, y][:3]
            if g <= 9 and 8 <= r <= 34 and 8 <= b <= 36 and abs(r - b) <= 8:
                points.append((x, y))
    if len(points) < 400:
        return None
    # The tooltip is one dense rectangle; stray matches (dark item pixels, the hotbar) are dropped.
    rows = {}
    for x, y in points:
        rows[y] = rows.get(y, 0) + 1
    ys = densest_run(rows, 6, 16)
    columns = {}
    for x, y in points:
        if ys[0] <= y <= ys[1]:
            columns[x] = columns.get(x, 0) + 1
    xs = densest_run(columns, 3, 16)
    return [xs[0], ys[0], xs[1], ys[1]]


def main():
    crops = {}
    for name, rows in MENUS.items():
        file = SHOTS / f"{name}.jpg"
        if not file.exists():
            continue
        x, y, w, h = menu_box(rows)
        x1, y1, x2, y2 = x, y, x + w, y + h
        tip = tooltip_box(Image.open(file).convert("RGB"))
        if tip:
            x1, y1 = min(x1, tip[0]), min(y1, tip[1])
            x2, y2 = max(x2, tip[2]), max(y2, tip[3])
        margin = 14
        x1, y1 = max(0, x1 - margin), max(0, y1 - margin)
        x2, y2 = min(W, x2 + margin), min(H, y2 + margin)
        crops[name] = [x1, y1, x2 - x1, y2 - y1]
        print(f"{name}: {crops[name]}{' (with tooltip)' if tip else ''}")
        if tip and name in TOOLTIP_ONLY:
            crops[f"{name}-tip"] = [tip[0] - margin, tip[1] - margin, tip[2] - tip[0] + 2 * margin, tip[3] - tip[1] + 2 * margin]
            print(f"{name}-tip: {crops[name + '-tip']}")
    OUT.write_text(json.dumps(crops, indent=2) + "\n")


if __name__ == "__main__":
    main()
