"""Compares planned river nodes with the blocks a real world generated at those spots.

usage: python check_nodes.py <world dir> <river_nodes.csv>
"""
import csv
import math
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mca  # noqa: E402

world, nodes_csv = sys.argv[1], sys.argv[2]
region_dir = os.path.join(world, "dimensions", "minecraft", "overworld", "region")
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
regions = {}


def chunk_at(cx, cz):
    rx, rz = cx >> 5, cz >> 5
    if (rx, rz) not in regions:
        path = os.path.join(region_dir, f"r.{rx}.{rz}.mca")
        loaded = {}
        if os.path.exists(path):
            for lx, lz, chunk in mca.chunks_in_region(path):
                if chunk.get("Status") == "minecraft:full":
                    loaded[(lx, lz)] = {s.get("Y"): s for s in chunk.get("sections", [])}
        regions[(rx, rz)] = loaded
    return regions[(rx, rz)].get((cx & 31, cz & 31))


def pname(e):
    """A block state palette entry's block id: 26.x wraps plain strings as {'': id} and writes compounds as {id, properties}."""
    if isinstance(e, str):
        return e
    return e.get("Name") or e.get("id") or e.get("") or ""


def pprops(e):
    if isinstance(e, dict):
        return e.get("Properties") or e.get("properties") or {}
    return {}


def block_name(sections, x, y, z):
    section = sections.get(y >> 4)
    if section is None:
        return "?"
    states = section.get("block_states")
    entries = states.get("palette", [])
    names = [pname(e) for e in entries]
    if len(names) == 1 or "data" not in states:
        return names[0]
    bits = max(4, math.ceil(math.log2(len(names))))
    idx = mca.unpack_longs(states["data"], bits, 4096)
    return names[int(idx[((y & 15) * 16 + (z & 15)) * 16 + (x & 15)])]


def biome_name(sections, x, y, z):
    section = sections.get(y >> 4)
    if section is None:
        return "?"
    b = section.get("biomes", {})
    names = [p if isinstance(p, str) else p.get("Name", "") for p in b.get("palette", [])]
    if len(names) == 1 or "data" not in b:
        return names[0]
    bits = max(1, math.ceil(math.log2(len(names))))
    idx = mca.unpack_longs(b["data"], bits, 64)
    return names[int(idx[(((y & 15) >> 2) * 4 + ((z & 15) >> 2)) * 4 + ((x & 15) >> 2)])]


def chunk_rivers(sections):
    found = set()
    for s in sections.values():
        for p in s.get("biomes", {}).get("palette", []):
            n = p if isinstance(p, str) else p.get("Name", "")
            if "river" in n and n.startswith("epicterrain:"):
                found.add(n)
    return found


def column(x, z, y0, y1):
    sections = chunk_at(x >> 4, z >> 4)
    if sections is None:
        return None
    return {y: block_name(sections, x, y, z) for y in range(y0, y1 + 1)}


tally = Counter()
tally2 = Counter()
rows = []
with open(nodes_csv) as f:
    for row in csv.DictReader(f, delimiter=';'):
        x, z = int(math.floor(float(row["x"]))), int(math.floor(float(row["z"])))
        water, depth, width, ground = float(row["water"]), float(row["depth"]), float(row["width"]), float(row["ground"])
        top_expected = math.floor(water - depth)
        water_top_expected = math.floor(water) - 1
        col = column(x, z, top_expected - 6, water_top_expected + 8)
        if col is None:
            tally["no chunk"] += 1
            continue
        sections = chunk_at(x >> 4, z >> 4)
        label = biome_name(sections, x, int(ground), z)
        labelled = label.startswith("epicterrain:") and "river" in label
        in_chunk = bool(chunk_rivers(sections))
        waters = [y for y, n in col.items() if n == "minecraft:water"]
        solids = [y for y, n in col.items() if n not in AIR and n != "minecraft:water"]
        # the highest solid block below the highest water block (the bed), if there is water
        key = ("labelled" if labelled else ("chunk has a river biome elsewhere" if in_chunk else "NOT labelled")) + (", carved" if waters else ", not carved")
        tally2[key] += 1
        if waters:
            water_top = max(waters)
            bed = max([y for y in solids if y < water_top], default=None)
            ok_top = water_top == water_top_expected
            ok_bed = bed is not None and abs(bed - top_expected) <= 1
            if ok_top and ok_bed:
                tally["water and bed as planned"] += 1
            elif ok_top:
                tally["water top as planned, bed differs"] += 1
            else:
                tally["water present, level differs"] += 1
            rows.append((row["river"], row["i"], x, z, water, ground, "water_top=%d (planned %d), bed=%s (planned %d)" % (water_top, water_top_expected, bed, top_expected)))
        else:
            tally["no water"] += 1
            profile = "".join("A" if col[y] in AIR else ("G" if col[y] == "minecraft:gravel" else "#") for y in sorted(col))
            rows.append((row["river"], row["i"], x, z, water, ground, "NO WATER; column from y%d up: %s (planned bed %d, water top %d)" % (min(col), profile, top_expected, water_top_expected)))

print("nodes compared:", sum(tally.values()))
for k, v in tally.most_common():
    print("  %-40s %d" % (k, v))
print("label at the node's own spot vs carved:")
for k, v in tally2.most_common():
    print("  %-50s %d" % (k, v))
print("examples:")
for r in rows[:14]:
    print("  %s #%s at (%d, %d) water %.1f ground %.0f: %s" % r)
bad = [r for r in rows if r[6].startswith("NO WATER") or "differs" in r[6] or "(planned" in r[6] and r[6].split("water_top=")[1].split(" ")[0] != r[6].split("planned ")[1].split(")")[0]]
print("first mismatches:")
for r in bad[:12]:
    print("  %s #%s at (%d, %d) water %.1f ground %.0f: %s" % r)
