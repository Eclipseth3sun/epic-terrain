"""Draws a top-down map of the chunks a world has generated, straight from its region files, and (given a vanilla baseline for
the same seed) measures how far the terrain differs from vanilla and whether every difference is labelled epic.

usage: python map_world.py <world folder> <output folder> [blocks_per_pixel] [baseline.bin]
"""
import glob
import gzip
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw

import mca

WORLD, OUT = sys.argv[1], sys.argv[2]
BPP = int(sys.argv[3]) if len(sys.argv) > 3 else 2
BASELINE = sys.argv[4] if len(sys.argv) > 4 else None
MIN_Y = -64
SEA = 63
os.makedirs(OUT, exist_ok=True)

LABELS = {'range': 1, 'wall': 2, 'foothills': 3, 'river': 4}


def classify(name):
    if name == 'epicterrain:epic_range_peaks':
        return 1
    if name == 'epicterrain:epic_wall_peaks':
        return 2
    if name.startswith('epicterrain:foothills'):
        return 3
    if name.startswith('epicterrain:') and 'river' in name:
        return 4
    return 0


region_dir = os.path.join(WORLD, 'dimensions', 'minecraft', 'overworld', 'region')
files = glob.glob(region_dir + '/r.*.mca')
coords = [tuple(map(int, re.match(r'r\.(-?\d+)\.(-?\d+)\.mca', os.path.basename(f)).groups())) for f in files]
min_rx, max_rx = min(c[0] for c in coords), max(c[0] for c in coords)
min_rz, max_rz = min(c[1] for c in coords), max(c[1] for c in coords)
width = (max_rx - min_rx + 1) * 512
depth = (max_rz - min_rz + 1) * 512
origin_x, origin_z = min_rx * 512, min_rz * 512
print('regions x %d..%d, z %d..%d -> %d x %d blocks' % (min_rx, max_rx, min_rz, max_rz, width, depth))

surface = np.zeros((depth, width), dtype=np.int16)   # y of the top face of the highest non-air block
floor = np.zeros((depth, width), dtype=np.int16)     # same, but ignoring water
have = np.zeros((depth, width), dtype=bool)          # column belongs to a fully generated chunk
epic = np.zeros((depth, width), dtype=np.uint8)      # 0 none, 1 range summit, 2 wall summit, 3 foothills, 4 river corridor
chunk_status = {}
biome_names = {}


def top(longs):
    return mca.unpack_longs(longs, 10, 256).reshape(16, 16).astype(np.int16) + MIN_Y


for (rx, rz), path in zip(coords, files):
    for lx, lz, chunk in mca.chunks_in_region(path):
        status = chunk.get('Status', '?')
        chunk_status[status] = chunk_status.get(status, 0) + 1
        hm = chunk.get('Heightmaps')
        if status != 'minecraft:full' or not hm or 'WORLD_SURFACE' not in hm or 'OCEAN_FLOOR' not in hm:
            continue
        x0 = (rx * 32 + lx) * 16 - origin_x
        z0 = (rz * 32 + lz) * 16 - origin_z
        surface[z0:z0 + 16, x0:x0 + 16] = top(hm['WORLD_SURFACE'])
        floor[z0:z0 + 16, x0:x0 + 16] = top(hm['OCEAN_FLOOR'])
        have[z0:z0 + 16, x0:x0 + 16] = True
        view = epic[z0:z0 + 16, x0:x0 + 16]
        for section in chunk.get('sections', []):
            biomes = section.get('biomes', {})
            palette = [p if isinstance(p, str) else p.get('Name', '') for p in biomes.get('palette', [])]
            for p in palette:
                biome_names[p] = biome_names.get(p, 0) + 1
            kinds = [classify(p) for p in palette]
            if not any(kinds):
                continue
            if len(palette) == 1:
                idx = np.zeros((4, 4, 4), dtype=np.int64)
            else:
                bits = max(1, math.ceil(math.log2(len(palette))))
                idx = mca.unpack_longs(biomes['data'], bits, 64).reshape(4, 4, 4)  # [y][z][x]
            for i, kind in enumerate(kinds):
                if kind:
                    cells = (idx == i).any(axis=0)
                    block = np.kron(cells, np.ones((4, 4), dtype=bool))
                    # summits win over foothills where both appear in a column
                    view[block & ((view == 0) | ((view == 3) & (kind < 3)))] = kind

np.savez_compressed(os.path.join(OUT, 'arrays.npz'), surface=surface, floor=floor, have=have, epic=epic, origin=np.array([origin_x, origin_z]))
print('chunks by status:', chunk_status)
print('epic biomes seen:', {k: v for k, v in sorted(biome_names.items()) if k.startswith('epicterrain')})
print('generated columns: %d (%.1f km2)' % (have.sum(), have.sum() / 1e6))

# -- colours ------------------------------------------------------------------------------------------
stops = np.array([
    [0, 0.20, 0.50, 0.20], [90, 0.45, 0.65, 0.30], [160, 0.75, 0.70, 0.40], [240, 0.55, 0.42, 0.30],
    [340, 0.55, 0.55, 0.55], [430, 0.85, 0.85, 0.88], [512, 1.0, 1.0, 1.0]])
TINT = {1: (0.0, 1.0, 1.0), 2: (1.0, 0.0, 0.8), 3: (1.0, 0.6, 0.0)}


def render(stride, tint_epic):
    s = surface[::stride, ::stride].astype(np.float32)
    f = floor[::stride, ::stride].astype(np.float32)
    ok = have[::stride, ::stride]
    e = epic[::stride, ::stride]
    water = (f < SEA) & (s >= SEA) & (s <= SEA + 1)
    rel = np.clip(f - SEA, 0, 512)
    land = np.stack([np.interp(rel, stops[:, 0], stops[:, i]) for i in (1, 2, 3)], axis=-1)
    depth_m = np.clip((SEA - f) / 60.0, 0, 1)
    sea = np.stack([0.10 - 0.06 * depth_m, 0.35 - 0.20 * depth_m, 0.70 - 0.25 * depth_m], axis=-1)
    rgb = np.where(water[..., None], sea, land)
    ground = np.where(water, SEA, f)
    west = np.roll(ground, 1, axis=1)
    north = np.roll(ground, 1, axis=0)
    shade = ((west - ground) + (north - ground)) / (1.2 * stride)
    rgb = np.clip(rgb * (1.0 + np.clip(shade * 0.9, -0.45, 0.45))[..., None], 0, 1)
    if tint_epic:
        for code, colour in TINT.items():
            m = (e == code) & ok & ~water
            rgb[m] = rgb[m] * 0.55 + np.array(colour) * 0.45
    rgb[~ok] = (0.04, 0.04, 0.05)
    return (rgb * 255).astype(np.uint8)


def annotate(img, stride, label):
    draw = ImageDraw.Draw(img)
    for x in range(origin_x, origin_x + width + 1, 512):
        px = (x - origin_x) // stride
        draw.line([(px, 0), (px, img.height)], fill=(90, 90, 100), width=1)
        draw.text((px + 3, 3), str(x), fill=(255, 255, 255))
    for z in range(origin_z, origin_z + depth + 1, 512):
        pz = (z - origin_z) // stride
        draw.line([(0, pz), (img.width, pz)], fill=(90, 90, 100), width=1)
        draw.text((3, pz + 3), str(z), fill=(255, 255, 255))
    ox, oz = (0 - origin_x) // stride, (0 - origin_z) // stride
    draw.ellipse([ox - 6, oz - 6, ox + 6, oz + 6], outline=(255, 255, 0), width=2)
    draw.text((ox + 9, oz - 6), '0,0', fill=(255, 255, 0))
    for p in glob.glob(os.path.join(WORLD, 'players', 'data', '*.dat')):
        try:
            pos = mca.parse_nbt(gzip.open(p).read()).get('Pos')
            px, pz = (pos[0] - origin_x) / stride, (pos[2] - origin_z) / stride
            draw.ellipse([px - 6, pz - 6, px + 6, pz + 6], outline=(255, 60, 60), width=3)
            draw.text((px + 9, pz - 6), 'player %d,%d' % (pos[0], pos[2]), fill=(255, 120, 120))
        except Exception as ex:  # noqa: BLE001
            print('could not read player position:', ex)
    draw.text((6, img.height - 14), label, fill=(255, 255, 255))
    return img


for name, tint in (('terrain', False), ('epic_zones', True)):
    img = Image.fromarray(render(BPP, tint))
    annotate(img, BPP, '%s: %d blocks/px; grid = 512-block regions; cyan = foothill-range summit, magenta = wall-range summit, orange = foothills' % (name, BPP))
    path = os.path.join(OUT, 'world_%s.png' % name)
    img.save(path)
    print('wrote', path, img.size)

# -- numbers ------------------------------------------------------------------------------------------------
ok = have
print('highest ground in the explored area: y=%d' % floor[ok].max())
names = {0: 'unlabelled (vanilla biome)', 1: 'foothill-range summit', 2: 'wall-range summit', 3: 'foothills', 4: 'river corridor'}
for code in (3, 1, 2, 0):
    mask = ok & (epic == code) & ((floor >= SEA) if code == 0 else True)
    g = floor[mask]
    if g.size:
        print('%-27s: %8d columns (%.2f km2), ground y  p10=%d  median=%d  p90=%d  p99=%d  max=%d' % (names[code], g.size, g.size / 1e6, *np.percentile(g, [10, 50, 90, 99]), g.max()))

# -- against vanilla --------------------------------------------------------------------------------------------
if BASELINE and os.path.exists(BASELINE):
    bx0, bz0, step, bw, bd = map(int, open(BASELINE + '.txt').read().split())
    base = np.fromfile(BASELINE, dtype='>i2').reshape(bd, bw).astype(np.int32)
    # per baseline cell: the typical ground height actually generated there and how much of it is labelled epic
    # the baseline is the vanilla world surface (water surface over oceans), so compare like with like: sea level over water
    water_cols = (floor < SEA) & (surface >= SEA) & (surface <= SEA + 1)
    compare = np.where(water_cols, SEA, floor).astype(np.int16)
    actual = np.full((bd, bw), np.nan, dtype=np.float32)
    labelled = np.zeros((bd, bw), dtype=np.float32)
    complete = np.zeros((bd, bw), dtype=bool)
    for r in range(bd):
        for c in range(bw):
            x = bx0 + c * step - origin_x
            z = bz0 + r * step - origin_z
            if x < 0 or z < 0 or x + step > width or z + step > depth:
                continue
            cell_have = have[z:z + step, x:x + step]
            if not cell_have.all():
                continue
            complete[r, c] = True
            actual[r, c] = np.median(compare[z:z + step, x:x + step])
            labelled[r, c] = (epic[z:z + step, x:x + step] > 0).mean()
    delta = actual - base.astype(np.float32)
    is_epic = labelled > 0.5
    valid = complete & ~np.isnan(delta)
    # water: baseline ground under the sea is the sea floor in both, so the comparison still holds
    print('\nvs vanilla, %d x %d-block cells compared (%.1f km2)' % (valid.sum(), step, valid.sum() * step * step / 1e6))
    for label, mask in (('labelled epic', valid & is_epic), ('NOT labelled epic', valid & ~is_epic)):
        d = delta[mask]
        if d.size == 0:
            continue
        print('  %-18s %6d cells: median %+.1f  p5 %+.1f  p95 %+.1f | higher than vanilla by >10: %.1f%%, >20: %.1f%%, lower by >10: %.1f%%, within +-3: %.1f%%' % (
            label, d.size, np.median(d), *np.percentile(d, [5, 95]), 100 * (d > 10).mean(), 100 * (d > 20).mean(), 100 * (d < -10).mean(), 100 * (np.abs(d) <= 3).mean()))

    # how far does a difference reach beyond the labelled zone? (distance in cells from the nearest labelled cell)
    dist = np.where(is_epic & valid, 0, 99).astype(np.int32)
    frontier = is_epic & valid
    reach = frontier.copy()
    print('  difference from vanilla by distance outside the labelled zone (cells are %d blocks):' % step)
    for ring in range(1, 9):
        grown = reach.copy()
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):
            grown |= np.roll(np.roll(reach, dr, axis=0), dc, axis=1)
        shell = grown & ~reach & valid & ~is_epic
        reach = grown
        d = delta[shell]
        if d.size:
            print('    %2d-%2d blocks outside: %5d cells, median %+.1f, mean |diff| %.1f, higher by >10: %.1f%%, differs by >10 either way: %.1f%%' % (
                (ring - 1) * step, ring * step, d.size, np.median(d), np.abs(d).mean(), 100 * (d > 10).mean(), 100 * (np.abs(d) > 10).mean()))
    far = valid & ~is_epic & ~reach
    if far.any():
        d = delta[far]
        print('    beyond that:            %5d cells, median %+.1f, mean |diff| %.1f, differs by >10 either way: %.1f%%' % (
            d.size, np.median(d), np.abs(d).mean(), 100 * (np.abs(d) > 10).mean()))

    # the suspicious ones: clearly higher than vanilla but not labelled
    suspects = valid & ~is_epic & (delta > 10)
    print('  unlabelled cells more than 10 blocks above vanilla: %d' % suspects.sum())
    order = np.argwhere(suspects)
    if len(order):
        top_cells = sorted(order, key=lambda rc: -delta[rc[0], rc[1]])[:10]
        for r, c in top_cells:
            print('     x=%6d z=%6d  actual %4d vs vanilla %4d (%+.0f)  labelled share %.0f%%' % (
                bx0 + c * step + step // 2, bz0 + r * step + step // 2, actual[r, c], base[r, c], delta[r, c], 100 * labelled[r, c]))

    # delta picture
    img = np.zeros((bd, bw, 3), dtype=np.float32)
    norm = np.clip(delta / 60.0, -1, 1)
    up = np.clip(norm, 0, 1)
    down = np.clip(-norm, 0, 1)
    img[..., 0] = 0.92 - 0.45 * down
    img[..., 1] = 0.92 - 0.55 * up - 0.55 * down
    img[..., 2] = 0.92 - 0.45 * up
    img[~valid] = (0.04, 0.04, 0.05)
    big = np.kron((img * 255).astype(np.uint8), np.ones((4, 4, 1), dtype=np.uint8))
    out = Image.fromarray(big)
    draw = ImageDraw.Draw(out)
    # outline the labelled zone in white
    edge = is_epic & ~(np.roll(is_epic, 1, 0) & np.roll(is_epic, -1, 0) & np.roll(is_epic, 1, 1) & np.roll(is_epic, -1, 1))
    for r, c in np.argwhere(edge & valid):
        draw.rectangle([c * 4, r * 4, c * 4 + 3, r * 4 + 3], fill=(0, 0, 0))
    draw.text((6, out.height - 14), 'height vs vanilla (red = higher, blue = lower, clipped at 60 blocks); black outline = labelled epic; %d-block cells' % step, fill=(0, 0, 0))
    path = os.path.join(OUT, 'world_vs_vanilla.png')
    out.save(path)
    print('wrote', path, out.size)
