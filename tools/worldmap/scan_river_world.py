"""What did the rivers do to a generated world? Counts river biome cells, elevated water, flowing water and spills.

usage: python scan_river_world.py <world dir>
"""
import math
import os
import sys
from collections import Counter

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mca  # noqa: E402

MIN_Y = -64
world = sys.argv[1]
region_dir = os.path.join(world, "dimensions", "minecraft", "overworld", "region")
files = sorted(f for f in os.listdir(region_dir) if f.endswith(".mca"))
RIVER_NAMES = {"epicterrain:epic_river", "epicterrain:epic_frozen_river", "epicterrain:epic_grand_river", "epicterrain:epic_frozen_grand_river"}
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
Y_LOW = 56   # decode blocks from here up: elevated water and everything around it
Y_HIGH = 330


def pname(e):
    """A block state palette entry's block id: 26.x wraps plain strings as {'': id} and writes compounds as {id, properties}."""
    if isinstance(e, str):
        return e
    return e.get("Name") or e.get("id") or e.get("") or ""


def pprops(e):
    if isinstance(e, dict):
        return e.get("Properties") or e.get("properties") or {}
    return {}


def palette_names(container):
    return [p if isinstance(p, str) else p.get("Name", "") for p in container.get("palette", [])]


def decode_blocks(section):
    """Returns (names palette, flowing flags per palette entry, 16x16x16 index array [y][z][x] or None for uniform)."""
    s = section.get("block_states")
    if not s:
        return None
    entries = s.get("palette", [])
    names = [pname(e) for e in entries]
    flowing = []
    for e in entries:
        level = pprops(e).get("level")
        flowing.append(pname(e) == "minecraft:water" and level not in (None, "0"))
    if len(names) == 1 or "data" not in s:
        return names, flowing, None
    bits = max(4, math.ceil(math.log2(len(names))))
    return names, flowing, mca.unpack_longs(s["data"], bits, 4096).reshape(16, 16, 16)


chunks = 0
river_chunks = 0
river_cells = Counter()
elevated_water_cols = 0
elevated_by_biome = Counter()
flowing_blocks = 0
flowing_chunks = 0
water_blocks_elevated = 0
spill_candidates = 0
spill_chunks = 0
gravel_beds = 0
top_hist = Counter()
examples = []

for name in files:
    parts = name.split(".")
    rx, rz = int(parts[1]), int(parts[2])
    for lx, lz, chunk in mca.chunks_in_region(os.path.join(region_dir, name)):
        if chunk.get("Status") != "minecraft:full":
            continue
        chunks += 1
        sections = {s.get("Y"): s for s in chunk.get("sections", [])}
        has_river = False
        for s in sections.values():
            for n in palette_names(s.get("biomes", {})):
                if n in RIVER_NAMES:
                    has_river = True
        if not has_river:
            continue
        river_chunks += 1
        # biome cells at the surface (for reporting)
        for sy, s in sections.items():
            b = s.get("biomes", {})
            names = palette_names(b)
            if len(names) == 1:
                if names[0] in RIVER_NAMES:
                    river_cells[names[0]] += 64
            elif "data" in b:
                bits = max(1, math.ceil(math.log2(len(names))))
                idx = mca.unpack_longs(b["data"], bits, 64)
                for i in idx:
                    if names[int(i)] in RIVER_NAMES:
                        river_cells[names[int(i)]] += 1
        # 3D block grid for y >= Y_LOW: 0 air, 1 solid, 2 water, 3 flowing water
        ny = Y_HIGH - Y_LOW
        grid = np.ones((ny, 16, 16), dtype=np.uint8)
        for sy, s in sections.items():
            y0 = sy * 16
            if y0 + 16 <= Y_LOW or y0 >= Y_HIGH:
                continue
            decoded = decode_blocks(s)
            if decoded is None:
                continue
            names, flowing, idx = decoded
            kinds = []
            for n, f in zip(names, flowing):
                kinds.append(0 if n in AIR else (3 if f else 2) if n == "minecraft:water" else 1)
            kinds = np.array(kinds, dtype=np.uint8)
            block = np.full((16, 16, 16), kinds[0], dtype=np.uint8) if idx is None else kinds[idx]
            for dy in range(16):
                y = y0 + dy
                if Y_LOW <= y < Y_HIGH:
                    grid[y - Y_LOW] = block[dy]
        # elevated water: blocks at y >= 63
        lo = 63 - Y_LOW
        up = grid[lo:]
        water = (up == 2) | (up == 3)
        water_blocks_elevated += int(water.sum())
        flow = int((up == 3).sum())
        if flow:
            flowing_blocks += flow
            flowing_chunks += 1
        # columns whose highest water block is elevated
        cols = water.any(axis=0)
        elevated_water_cols += int(cols.sum())
        # a spill candidate: elevated water with air beside it (inside the chunk) whose air column ends on solid ground
        found = 0
        ys, zs, xs = np.nonzero(water)
        for y, z, x in zip(ys, zs, xs):
            for dz, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nz, nx = z + dz, x + dx
                if not (0 <= nz < 16 and 0 <= nx < 16):
                    continue
                if up[y, nz, nx] != 0:
                    continue
                yy = y - 1
                while yy >= 0 and up[yy, nz, nx] == 0:
                    yy -= 1
                if yy < 0:
                    continue  # runs into the sea-level layer below: fine
                if up[yy, nz, nx] == 1:
                    found += 1
        if found:
            spill_candidates += found
            spill_chunks += 1
            if len(examples) < 5:
                examples.append(((rx * 32 + lx), (rz * 32 + lz), found))

print("full chunks scanned:", chunks, "| chunks holding an epic river biome:", river_chunks)
print("epic river biome cells (4x4x4):", dict(river_cells))
print("water blocks at y>=63 in those chunks:", water_blocks_elevated, "in", elevated_water_cols, "columns")
print("flowing (non-source) water blocks at y>=63:", flowing_blocks, "in", flowing_chunks, "chunks")
print("water blocks beside open air that ends on ground (may be a river stepping down its bed):", spill_candidates, "in", spill_chunks, "chunks; first chunks:", examples)
