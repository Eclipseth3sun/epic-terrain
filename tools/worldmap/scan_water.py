"""Finds water source blocks that sit beside air (at the side, or over air below them): water that would flow if it were updated, in a saved world.

Usage: python tools/worldmap/scan_water.py "<world folder>" out.json

Reads the fully generated chunks of the overworld's region files. A block that already has a fluid tick scheduled in its chunk is not counted (the game will settle it).
Only neighbours inside the same chunk are looked at, so the count is a floor. Needs numpy (and tools/worldmap/mca.py)."""
import glob
import json
import os
import sys
from collections import Counter
from multiprocessing import Pool

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mca  # noqa: E402

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def entry_name(e):
    if isinstance(e, dict):
        return e.get("") or e.get("Name") or e.get("id") or "?"
    return str(e)


def entry_level(e):
    if isinstance(e, dict):
        p = e.get("properties") or e.get("Properties") or {}
        return str(p.get("level", "0"))
    return "0"


def scan(path):
    if os.path.getsize(path) < 8192:
        return None
    total = Counter()
    chunks = 0
    examples = []
    for lx, lz, nbt in mca.chunks_in_region(path):
        status = str(nbt.get("Status", nbt.get("status", "")))
        if "full" not in status:
            continue
        sections = nbt.get("sections") or []
        grid = {}
        any_water = False
        for s in sections:
            bs = s.get("block_states") or {}
            pal = bs.get("palette", [])
            names = [entry_name(e) for e in pal]
            if not any(n == "minecraft:water" for n in names):
                # no water: only air matters for neighbours of water in the section above or below, so keep the air mask
                kind = np.zeros(4096, dtype=np.int8)
                if len(pal) == 1:
                    kind[:] = 1 if names[0] in AIR else 0
                else:
                    data = bs.get("data")
                    if data is not None and any(n in AIR for n in names):
                        bits = max(4, int(np.ceil(np.log2(len(pal)))))
                        idx = mca.unpack_longs(data, bits, 4096)
                        lut = np.array([1 if n in AIR else 0 for n in names], dtype=np.int8)
                        kind = lut[idx]
                grid[s["Y"]] = kind
                continue
            any_water = True
            kinds = np.zeros(len(pal), dtype=np.int8)  # 0 other, 1 air, 2 water source, 3 flowing water
            for i, e in enumerate(pal):
                n = names[i]
                if n in AIR:
                    kinds[i] = 1
                elif n == "minecraft:water":
                    kinds[i] = 2 if entry_level(e) == "0" else 3
            if len(pal) == 1:
                grid[s["Y"]] = np.full(4096, kinds[0], dtype=np.int8)
            else:
                bits = max(4, int(np.ceil(np.log2(len(pal)))))
                idx = mca.unpack_longs(bs["data"], bits, 4096)
                grid[s["Y"]] = kinds[idx]
        if not any_water:
            continue
        chunks += 1
        ys = sorted(grid)
        y0, y1 = ys[0], ys[-1]
        vol = np.zeros(((y1 - y0 + 1) * 16, 16, 16), dtype=np.int8)
        for y in ys:
            vol[(y - y0) * 16:(y - y0 + 1) * 16] = grid[y].reshape(16, 16, 16)  # [y, z, x]
        src = vol == 2
        air = vol == 1
        bad = np.zeros_like(src)
        bad[:, :, :-1] |= src[:, :, :-1] & air[:, :, 1:]
        bad[:, :, 1:] |= src[:, :, 1:] & air[:, :, :-1]
        bad[:, :-1, :] |= src[:, :-1, :] & air[:, 1:, :]
        bad[:, 1:, :] |= src[:, 1:, :] & air[:, :-1, :]
        bad[1:] |= src[1:] & air[:-1]  # air below
        side = np.zeros_like(src)
        side[:, :, :-1] |= src[:, :, :-1] & air[:, :, 1:]
        side[:, :, 1:] |= src[:, :, 1:] & air[:, :, :-1]
        side[:, :-1, :] |= src[:, :-1, :] & air[:, 1:, :]
        side[:, 1:, :] |= src[:, 1:, :] & air[:, :-1, :]
        below = np.zeros_like(src)
        below[1:] |= src[1:] & air[:-1]
        # blocks that already have a fluid tick scheduled are not stuck: the game will settle them
        pending = np.zeros_like(src)
        for t in (nbt.get("fluid_ticks") or []):
            ty = int(t["y"]) - y0 * 16
            if 0 <= ty < pending.shape[0]:
                pending[ty, int(t["z"]) & 15, int(t["x"]) & 15] = True
        total["with_pending_tick"] += int((bad & pending).sum())
        total["fluid_ticks_total"] += len(nbt.get("fluid_ticks") or [])
        bad &= ~pending
        side &= ~pending
        below &= ~pending
        n_src = int(src.sum())
        n_bad = int(bad.sum())
        total["chunks_with_water"] += 0  # placeholder to keep the key order
        total["sources"] += n_src
        total["unsettled"] += n_bad
        total["unsettled_side"] += int(side.sum())
        total["unsettled_below"] += int(below.sum())
        yy = np.nonzero(bad.any(axis=(1, 2)))[0]
        for y in yy:
            world_y = (y0 * 16 + int(y))
            c = int(bad[y].sum())
            band = "above sea (y>=63)" if world_y >= 63 else "at or below sea (y<=62)"
            total["unsettled " + band] += c
            total["y%04d" % world_y] += c
        if n_bad:
            total["chunks_with_unsettled"] += 1
            if len(examples) < 5:
                zs, xs = np.nonzero(bad.any(axis=0))
                ys2 = np.nonzero(bad.any(axis=(1, 2)))[0]
                examples.append((lx, lz, n_bad, int(y0 * 16 + ys2.min()), int(y0 * 16 + ys2.max())))
    total["chunks_scanned"] = chunks
    return total, os.path.basename(path), examples


if __name__ == "__main__":
    world = sys.argv[1]
    out = sys.argv[2]
    files = sorted(glob.glob(os.path.join(world, "dimensions", "minecraft", "overworld", "region", "*.mca")))
    grand = Counter()
    per_region = {}
    with Pool(4) as pool:
        for r in pool.imap_unordered(scan, files):
            if r is None:
                continue
            total, name, examples = r
            grand.update(total)
            per_region[name] = {"unsettled": total["unsettled"], "sources": total["sources"], "examples": examples}
    json.dump({"total": dict(grand), "regions": per_region}, open(out, "w"), indent=1, sort_keys=True)
    print(json.dumps(dict(grand), indent=1, sort_keys=True))
    worst = sorted(per_region.items(), key=lambda kv: -kv[1]["unsettled"])[:6]
    print("regions with the most:", [(k, v["unsettled"]) for k, v in worst])
