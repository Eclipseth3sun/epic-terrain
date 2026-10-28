"""Top-down picture of a generated spot: terrain shading, water (source / flowing), gravel, with planned river nodes overlaid.

usage: python render_spot.py <world dir> <cx> <cz> <half> <out.png> [river_nodes.csv]
"""
import csv
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mca  # noqa: E402

MIN_Y = -64
world = sys.argv[1]
cx, cz, half = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
out = sys.argv[5]
nodes_csv = sys.argv[6] if len(sys.argv) > 6 else None
region_dir = os.path.join(world, "dimensions", "minecraft", "overworld", "region")
SCALE = 4
regions = {}
section_cache = {}


def pname(e):
    if isinstance(e, str):
        return e
    return e.get("Name") or e.get("id") or e.get("") or ""


def pprops(e):
    return (e.get("Properties") or e.get("properties") or {}) if isinstance(e, dict) else {}


def chunk_at(chunk_x, chunk_z):
    rx, rz = chunk_x >> 5, chunk_z >> 5
    if (rx, rz) not in regions:
        path = os.path.join(region_dir, f"r.{rx}.{rz}.mca")
        loaded = {}
        if os.path.exists(path):
            for lx, lz, chunk in mca.chunks_in_region(path):
                if chunk.get("Status") == "minecraft:full":
                    hm = chunk.get("Heightmaps") or {}
                    surf = mca.unpack_longs(hm["WORLD_SURFACE"], 10, 256).reshape(16, 16).astype(int) + MIN_Y - 1
                    floor = mca.unpack_longs(hm["OCEAN_FLOOR"], 10, 256).reshape(16, 16).astype(int) + MIN_Y - 1
                    loaded[(lx, lz)] = ({s.get("Y"): s for s in chunk.get("sections", [])}, surf, floor)
        regions[(rx, rz)] = loaded
    return regions[(rx, rz)].get((chunk_x & 31, chunk_z & 31))


def block_at(chunk_x, chunk_z, sections, x, y, z):
    key = (chunk_x, chunk_z, y >> 4)
    if key not in section_cache:
        section = sections.get(y >> 4)
        if section is None:
            section_cache[key] = None
        else:
            states = section["block_states"]
            entries = states.get("palette", [])
            names = [pname(e) for e in entries]
            flowing = [pname(e) == "minecraft:water" and pprops(e).get("level") not in (None, "0") for e in entries]
            if len(names) == 1 or "data" not in states:
                idx = None
            else:
                bits = max(4, math.ceil(math.log2(len(names))))
                idx = mca.unpack_longs(states["data"], bits, 4096)
            section_cache[key] = (names, flowing, idx)
    cached = section_cache[key]
    if cached is None:
        return "?", False
    names, flowing, idx = cached
    i = 0 if idx is None else int(idx[((y & 15) * 16 + (z & 15)) * 16 + (x & 15)])
    return names[i], flowing[i]


size = 2 * half
img = Image.new("RGB", (size * SCALE, size * SCALE), (30, 30, 30))
pix = img.load()
heights = np.full((size, size), np.nan)
colors = {}
for dz in range(size):
    for dx in range(size):
        x, z = cx - half + dx, cz - half + dz
        info = chunk_at(x >> 4, z >> 4)
        if info is None:
            continue
        sections, surf, floor = info
        y = int(surf[z & 15, x & 15])
        name, flowing = block_at(x >> 4, z >> 4, sections, x, y, z)
        heights[dz, dx] = y
        if name == "minecraft:water":
            colors[(dx, dz)] = (0, 215, 255) if flowing else (20, 70, 210)
        elif name == "minecraft:gravel":
            colors[(dx, dz)] = (225, 205, 60)
        elif name in ("minecraft:air", "minecraft:cave_air"):
            colors[(dx, dz)] = (255, 0, 255)
        else:
            colors[(dx, dz)] = None
lo, hi = np.nanmin(heights), np.nanmax(heights)
for dz in range(size):
    for dx in range(size):
        if np.isnan(heights[dz, dx]):
            continue
        c = colors.get((dx, dz))
        shade = 0.45 + 0.55 * (heights[dz, dx] - lo) / max(1.0, hi - lo)
        if c is None:
            base = (int(110 * shade + 40), int(150 * shade + 30), int(90 * shade + 20))
        else:
            base = c
        for ox in range(SCALE):
            for oz in range(SCALE):
                pix[dx * SCALE + ox, dz * SCALE + oz] = base
draw = ImageDraw.Draw(img)
if nodes_csv:
    with open(nodes_csv) as f:
        for row in csv.DictReader(f, delimiter=";"):
            px = (float(row["x"]) - (cx - half)) * SCALE
            pz = (float(row["z"]) - (cz - half)) * SCALE
            if 0 <= px < size * SCALE and 0 <= pz < size * SCALE:
                draw.ellipse([px - 5, pz - 5, px + 5, pz + 5], outline=(255, 40, 40), width=2)
                draw.text((px + 7, pz - 6), "%s W%s" % (row["i"], row["water"]), fill=(255, 255, 255))
draw.text((6, 6), "centre (%d, %d), %d blocks wide; blue=water source, cyan=flowing water, yellow=gravel, red rings=planned nodes (every 3rd)" % (cx, cz, size), fill=(255, 255, 255))
img.save(out)
print("saved", out, "height range", lo, hi)
