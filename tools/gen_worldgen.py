#!/usr/bin/env python3
"""Generates Epic Terrain's worldgen JSON under src/main/resources.

The density-function graph is built from small helpers so the whole terrain model (selector masks, range
profiles, vanilla blending) lives in one readable place. Vanilla pieces are read straight out of the game
jar and rewritten, so vanilla regions of an Epic Terrain world stay byte-for-byte equivalent to vanilla
terrain. Re-run after changing any constant:

    python tools/gen_worldgen.py [--jar PATH_TO_26.3.jar]
"""
import argparse
import json
import os
import shutil
import sys
import zipfile

NS = "epicterrain"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")

# ---------------------------------------------------------------------------------------------------------
# Tunables. Heights are blocks of uplift above the vanilla inland base (~y 65-100); the world tops out at 512.
# ---------------------------------------------------------------------------------------------------------
TUNE = {
    "min_y": -64,
    "height": 576,                 # top at y=512
    "slide_top_start": 496,        # epic terrain fades to air between these two y levels
    "slide_top_end": 512,
    "cloud_height": 330.33,

    # How many ranges exist is the world-creation slider (see EpicAmount); this is the default for datapack-only worlds
    # (dedicated servers using level-type=epicterrain:epic): the chance that a range slot on land holds a range.
    "range_occupancy": 0.20,

    # Plan-view layout (see RangeField.Layout). The world is cut into cells of cell_size blocks, each with `slots`
    # independent range slots. Every range stays inside its cell's reach, so ranges never leak further than this.
    "cell_size": 6144,
    "slots": 4,
    "line_share": 0.35,            # straight-ish ranges
    "arc_share": 0.45,             # curved ranges; whatever is left over is bowls (arcs that nearly close)
    "wall_share": 0.45,            # share of ranges that are Teton-style walls (the rest have foothills)
    "line_length": (3500, 7500),
    "arc_radius": (1600, 2800),
    "arc_sweep": (70, 200),        # degrees
    "bowl_sweep": (230, 330),
    "end_taper": 0.14,             # fraction of a range's length over which each end fades out

    "warp_amplitude": 420.0,       # blocks of wobble so ranges are "somewhat" lines and arcs, not ruler-straight
    "height_scale": 1.05,          # multiplies every range's height (the config/height_scale datapack knob)
    "peak_base": 205.0,            # typical summit uplift (blocks)
    "peak_variation": 60.0,        # +/- along a range
    "detail_floor": 0.6,           # how far individual peaks may dip along a crest (1.0 = perfectly even crest)

    # Cross-sections, in blocks from the range's spine. Beyond the outer skirt edge the range is exactly zero.
    "foothill_core": (50, 560),
    "foothill_skirt": (0, 950),
    "foothill_skirt_height": 80.0, # rolling foothills out to the skirt edge
    "wall_core": (125, 400),
    "wall_skirt": (0, 440),
    "wall_skirt_height": 22.0,

    # Continentalness (-1 deep ocean .. 1 far inland) over which uplift fades in. Only the deep ocean (< -0.455) switches a
    # range off, so ranges run unbroken across coasts and shallow water instead of being chopped up by the coastline.
    "land_wall": (-0.55, -0.25),
    "land_foot": (-0.55, -0.25),

    # Uplift (blocks) over which the biome climate turns to peaks, and where the epic summit biomes begin. The summit
    # zone starts just after the climate is fully "peak" so vanilla reliably answers with one of its peak biomes.
    "peak_zone": (100.0, 140.0),
    "summit_zone_uplift": 100.0,   # where the climate starts to turn to peaks (see peak_zone); vanilla peak biomes can appear from here
    "foothill_zone_uplift": 3.0,   # anything with at least this much uplift, below the summits, is labelled "foothills"

    # Rivers (planned in Java, see river/RiverPlanner): the chance that a range also has one grand river, which runs to a sea of
    # at least 10 km2. Everything else about rivers (profiles, routing) is plain Java defaults in river/RiverConfig.
    # TEMPORARY while the grand rivers are tuned: 0.15 so there are enough to find (the design value is 0.05). It also makes the first
    # look at a new area wait for more plans (about 2x at 50%), because every chunk waits for each grand river in reach.
    "grand_chance": 0.15,

    "influence_ramp": (4.0, 40.0),    # uplift (blocks) over which vanilla factor/jaggedness/relief hand over to epic
    "vanilla_cap_inside_ranges": -0.15,  # offset cap applied to vanilla relief under a range (y ~ 109)
    "factor_foothill": 4.7,
    "factor_wall": 3.6,
    "jagged_foothill": 0.28,
    "jagged_wall": 0.50,
}


# ---------------------------------------------------------------------------------------------------------
# Tiny density-function builder
# ---------------------------------------------------------------------------------------------------------
def add(a, b): return {"type": "minecraft:add", "left": a, "right": b}
def sub(a, b): return {"type": "minecraft:sub", "left": a, "right": b}
def mul(a, b): return {"type": "minecraft:mul", "left": a, "right": b}
def mn(a, b): return {"type": "minecraft:min", "left": a, "right": b}
def mx(a, b): return {"type": "minecraft:max", "left": a, "right": b}
def absf(x): return {"type": "minecraft:abs", "input": x}
def clamp(x, lo, hi): return {"type": "minecraft:clamp", "input": x, "min": lo, "max": hi}
def lerp(alpha, a, b): return {"type": "minecraft:lerp", "alpha": alpha, "first": a, "second": b}
def powf(base, exponent): return {"type": "minecraft:pow", "base": base, "exponent": exponent}
def cache(x): return {"type": "minecraft:cache", "input": x}


def range_choice(x, lo, hi, inside, outside):
    return {"type": "minecraft:range_choice", "input": x, "min_inclusive": lo, "max_exclusive": hi,
            "when_in_range": inside, "when_out_of_range": outside}


def grad_y(y0, v0, y1, v1):
    return {"type": "minecraft:gradient", "axis": "y", "from_coordinate": y0, "from_value": v0,
            "to_coordinate": y1, "to_value": v1}


def noise(name, xz=1.0, y=0.0, shift_x=None, shift_z=None):
    node = {"type": "minecraft:noise", "noise": f"{NS}:{name}", "xz_scale": xz, "y_scale": y}
    if shift_x is not None:
        node["shift_x"] = shift_x
    if shift_z is not None:
        node["shift_z"] = shift_z
    return node


class Gen:
    def __init__(self, jar):
        self.zip = zipfile.ZipFile(jar)
        self.written = []

    def vanilla(self, path):
        return json.loads(self.zip.read("data/minecraft/" + path))

    def write(self, rel, obj):
        path = os.path.join(RES, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", newline="\n") as f:
            json.dump(obj, f, indent=2)
            f.write("\n")
        self.written.append(rel)

    def vanilla_tags(self, kind):
        prefix = f"data/minecraft/tags/{kind}/"
        return [n[len(prefix):-5] for n in self.zip.namelist() if n.startswith(prefix) and n.endswith(".json")]

    def fn(self, name, tree):
        """Registers a density function and returns a reference to it."""
        self.write(f"data/{NS}/worldgen/density_function/{name}.json", tree)
        return f"{NS}:{name}"

    def smooth(self, name, x, lo, hi):
        """smoothstep(x; lo, hi), with the shared clamp registered as its own function."""
        t = self.fn(name + "_t", clamp(mul(add(x, -lo), 1.0 / (hi - lo)), 0.0, 1.0))
        return self.fn(name, mul(mul(t, t), sub(3.0, mul(t, 2.0))))

    def noise_def(self, name, base_octave, octaves, modifiers=None, amplitude=1.0):
        obj = {"base_amplitude": amplitude, "base_octave": base_octave, "octave_count": octaves}
        if modifiers:
            obj["amplitude_modifiers"] = modifiers
        self.write(f"data/{NS}/worldgen/noise/{name}.json", obj)


def substitute(node, mapping, rules):
    if isinstance(node, str):
        return mapping.get(node, node)
    if isinstance(node, list):
        return [substitute(n, mapping, rules) for n in node]
    if isinstance(node, dict):
        for rule in rules:
            replaced = rule(node)
            if replaced is not None:
                return replaced
        return {k: substitute(v, mapping, rules) for k, v in node.items()}
    return node


def feature_order(g, biome_names):
    """Per decoration step, vanilla's feature order as {feature: position}.

    Minecraft refuses to start if two biomes list the same features in conflicting orders, so any biome we assemble from
    several vanilla pieces must keep to one global order. Every vanilla biome's list is a subsequence of this one.
    """
    import heapq
    steps = len(g.vanilla(f"worldgen/biome/{biome_names[0]}.json")["features"])
    orders = []
    for step in range(steps):
        successors, in_degree = {}, {}
        for name in biome_names:
            features = g.vanilla(f"worldgen/biome/{name}.json")["features"]
            listed = features[step] if step < len(features) else []
            for feature in listed:
                successors.setdefault(feature, set())
                in_degree.setdefault(feature, 0)
            for a, b in zip(listed, listed[1:]):
                if b not in successors[a]:
                    successors[a].add(b)
                    in_degree[b] += 1
        ready = [f for f, d in in_degree.items() if d == 0]
        heapq.heapify(ready)
        position = {}
        while ready:
            feature = heapq.heappop(ready)
            position[feature] = len(position)
            for following in successors[feature]:
                in_degree[following] -= 1
                if in_degree[following] == 0:
                    heapq.heappush(ready, following)
        assert len(position) == len(successors), f"vanilla feature order has a cycle in step {step}"
        orders.append(position)
    return orders


def build(g):
    t = TUNE
    V = "minecraft:overworld/"
    E = f"{NS}:overworld/"

    # ---- noises -------------------------------------------------------------------------------------
    g.noise_def("range_warp_a", -11, 2, [1.0, 0.5])
    g.noise_def("range_warp_b", -11, 2, [1.0, 0.5])
    g.noise_def("range_detail", -9, 3)
    g.noise_def("peak_height", -10, 2)
    g.noise_def("foothill_detail", -8, 3)

    # ---- user-facing knobs ----------------------------------------------------------------------------------
    # Plain numbers on purpose: a datapack can replace either file to retune a world with no code.
    #   range_occupancy: 0..1, the chance a range slot on land holds a range (the world-creation slider is this / 100)
    #   height_scale:    multiplies how tall ranges rise
    occupancy = g.fn("config/range_occupancy", t["range_occupancy"])
    height_scale = g.fn("config/height_scale", t["height_scale"])

    # ---- where, and what kind of, range ---------------------------------------------------------------
    # RangeField (Java) places lines, arcs and bowls; these are the noise inputs it shapes them with.
    warp_x = g.fn("range/warp_x", mul(noise("range_warp_a"), t["warp_amplitude"]))
    warp_z = g.fn("range/warp_z", mul(noise("range_warp_b"), t["warp_amplitude"]))
    peak_noise = g.fn("range/peak_noise", noise("peak_height"))
    detail_noise = g.fn("range/detail_noise", clamp(sub(1.0, absf(noise("range_detail"))), 0.0, 1.0))
    hill_noise = g.fn("range/hill_noise", lerp(clamp(add(0.5, mul(noise("foothill_detail"), 1.8)), 0.0, 1.0), 0.45, 1.0))

    def rng(pair):
        return [float(pair[0]), float(pair[1])]

    def range_field(output):
        return {
            "type": f"{NS}:range_field", "output": output,
            "occupancy": occupancy, "land": V + "continents",
            "warp_x": warp_x, "warp_z": warp_z,
            "peak_noise": peak_noise, "detail_noise": detail_noise, "hill_noise": hill_noise,
            "layout": {
                "cell_size": t["cell_size"], "slots": t["slots"], "line_share": t["line_share"], "arc_share": t["arc_share"],
                "wall_share": t["wall_share"], "line_length": rng(t["line_length"]), "arc_radius": rng(t["arc_radius"]),
                "arc_sweep": rng(t["arc_sweep"]), "bowl_sweep": rng(t["bowl_sweep"]), "taper": t["end_taper"],
            },
            "profile": {
                "peak_base": t["peak_base"], "peak_variation": t["peak_variation"], "detail_floor": t["detail_floor"],
                "foothill_core": rng(t["foothill_core"]), "foothill_skirt": rng(t["foothill_skirt"]),
                "foothill_skirt_height": t["foothill_skirt_height"],
                "wall_core": rng(t["wall_core"]), "wall_skirt": rng(t["wall_skirt"]), "wall_skirt_height": t["wall_skirt_height"],
                "foothill_land": rng(t["land_foot"]), "wall_land": rng(t["land_wall"]),
            },
        }

    uplift = g.fn("range/uplift", cache(mul(range_field("uplift"), height_scale)))
    kind = g.fn("range/kind", cache(range_field("kind")))  # 0 = foothill range, 1 = wall range
    distance = g.fn("range/distance", range_field("distance"))  # debugging: distance to the nearest spine (rivers also read the ranges from it)
    influence = g.smooth("range/influence", uplift, *t["influence_ramp"])

    # ---- terrain shaping: vanilla blended with epic ---------------------------------------------------
    v_offset = V + "offset"
    capped = lerp(influence, v_offset, mn(v_offset, t["vanilla_cap_inside_ranges"]))
    offset = g.fn("overworld/offset", cache(add(capped, mul(uplift, 1.0 / 128.0))))
    factor = g.fn("overworld/factor", cache(lerp(influence, V + "factor",
                                                  lerp(kind, t["factor_foothill"], t["factor_wall"]))))
    jagged = g.fn("overworld/jaggedness", cache(lerp(influence, V + "jaggedness",
                                                      lerp(kind, t["jagged_foothill"], t["jagged_wall"]))))

    # Vanilla's y gradient is clamped at y=320; extend it with the same slope so offsets above 1.0 keep working.
    y_gradient = g.fn("overworld/y_gradient", grad_y(-64, 1.5, 512, 1.5 - 3.0 * (576.0 / 384.0)))
    g.fn("overworld/depth", add(y_gradient, offset))

    top_slide = g.fn("overworld/top_slide", lerp(
        influence,
        grad_y(240, 1.0, 256, 0.0),
        grad_y(t["slide_top_start"], 1.0, t["slide_top_end"], 0.0)))

    mapping = {
        V + "offset": offset, V + "factor": factor, V + "jaggedness": jagged, V + "depth": E + "depth",
        V + "sloped_cheese": E + "sloped_cheese", V + "preliminary_surface_level": E + "preliminary_surface_level",
    }
    counts = {"slide": 0, "gradient": 0, "bound": 0}

    def rule(node):
        if node.get("type") == "minecraft:gradient" and node.get("axis") == "y":
            if (node["from_coordinate"], node["to_coordinate"]) == (240, 256):
                counts["slide"] += 1
                return top_slide
            if (node["from_coordinate"], node["to_coordinate"]) == (-64, 320):
                counts["gradient"] += 1
                return y_gradient
        if node.get("type") == "minecraft:clamp" and node.get("max") == 320.0 and node.get("min") == -40.0:
            counts["bound"] += 1
            return dict(node, input=substitute(node["input"], mapping, [rule]), max=512.0)
        return None

    for name in ("sloped_cheese", "preliminary_surface_level", "chunk_surface_level", "final_density"):
        src = g.vanilla(f"worldgen/density_function/overworld/{name}.json")
        g.fn(f"overworld/{name}", substitute(src, mapping | {V + "chunk_surface_level": E + "chunk_surface_level"}, [rule]))
    # The top slide appears in preliminary_surface_level and final_density; the y gradient and the
    # find_top_surface upper bound only in preliminary_surface_level.
    assert counts == {"slide": 2, "gradient": 1, "bound": 1}, counts

    # ---- climate seen by the biome source ---------------------------------------------------------------
    # Biomes read continentalness/erosion/weirdness, not terrain height, so push those toward mountain values
    # as uplift grows and vanilla's own mountain biomes (foothill meadows, groves, peaks) follow the terrain.
    w_foot = g.smooth("climate/zone_foot", uplift, 3.0, 40.0)
    w_slope = g.smooth("climate/zone_slope", uplift, 40.0, 100.0)
    w_peak = g.smooth("climate/zone_peak", uplift, *t["peak_zone"])

    v_cont, v_ero, v_ridge, v_temp = V + "continents", V + "erosion", V + "ridges", V + "temperature"
    g.fn("climate/continents", cache(lerp(w_foot, v_cont, mx(v_cont, 0.30))))
    g.fn("climate/erosion", cache(lerp(w_peak, lerp(w_slope, lerp(w_foot, v_ero, -0.25), -0.60), -0.90)))

    sign = clamp(mul(add(v_ridge, 0.001), 1000.0), -1.0, 1.0)
    away_from_zero = mul(sign, mx(absf(v_ridge), 0.28))
    g.fn("climate/ridges", cache(lerp(w_peak, lerp(w_slope, lerp(w_foot, v_ridge, away_from_zero), mul(sign, 0.30)),
                                      mul(sign, 0.67))))

    # Slopes above the snow line cool with altitude; the summit zone is always frozen, whatever the local climate.
    altitude = grad_y(250, 0.0, 450, 1.0)
    cooled = sub(v_temp, mul(mul(altitude, w_slope), 1.4))

    # Epic zones (see EpicBiomeSource): 1 = summit of a foothill range, 2 = summit of a wall range, 3 = the foothills
    # (everything else a range touches), 4 and 5 = river corridors (below). The zone number rides along in the temperature channel as +8 per zone and is
    # stripped again before vanilla picks a biome. Zones only label biomes; they never change the terrain.
    summit_code = g.fn("climate/summit_code", range_choice(kind, 0.5, 2.0, 2.0, 1.0))
    foothill_code = g.fn("climate/foothill_code", range_choice(uplift, t["foothill_zone_uplift"], t["summit_zone_uplift"], 3.0, 0.0))
    range_zone = g.fn("climate/range_zone", range_choice(uplift, t["summit_zone_uplift"], 100000.0, summit_code, foothill_code))
    # 4 = the corridor of a tributary, 5 = the corridor of a grand river (0 elsewhere). A river's corridor keeps its label even where it
    # crosses a range, so rivers can always be found.
    river_zone = g.fn("river/zone", {
        "type": f"{NS}:river_field", "output": "zone",
        "sloped_cheese": E + "sloped_cheese", "offset": E + "offset", "uplift": uplift, "land": V + "continents",
        "ranges": distance, "grand_chance": t["grand_chance"],
    })
    zone = g.fn("climate/zone", range_choice(river_zone, 0.5, 100.0, river_zone, range_zone))
    g.fn("climate/temperature", add(lerp(w_peak, cooled, -0.7), mul(zone, 8.0)))

    # ---- epic biomes, tags and surface rules -------------------------------------------------------------------
    # Two summit biomes, cloned from the vanilla peaks they stand in for, one per range kind. They exist so vanilla's
    # /locate biome can find epic mountains (and tell the two kinds apart).
    clones = {"epic_range_peaks": "frozen_peaks", "epic_wall_peaks": "jagged_peaks"}
    for name, source in clones.items():
        g.write(f"data/{NS}/worldgen/biome/{name}.json", g.vanilla(f"worldgen/biome/{source}.json"))
    # vanilla biome -> the epic biomes that must be treated exactly like it (tags, spawns, surface rules)
    stand_in = {"minecraft:frozen_peaks": [f"{NS}:epic_range_peaks"], "minecraft:jagged_peaks": [f"{NS}:epic_wall_peaks"]}

    # Foothill biomes: everything a range touches below its summits. They exist so that all epic terrain is labelled, and
    # so there is a visible, locatable band where the range hands over to vanilla. Each one is built from the vanilla
    # highland biome that already suits its climate (same snow/rain behaviour, mobs, colours and surface), with a few extra
    # highland features merged in. Every vanilla land biome maps to one of them (vegetation is the foothills' own; the
    # terrain, as everywhere, is untouched).
    foothills = {
        "foothills_cold": {
            "title": "Cold", "base": "grove", "extra": ["minecraft:forest_rock"],
            "land": ["snowy_plains", "snowy_taiga", "snowy_slopes", "ice_spikes", "grove"]},
        "foothills_temperate": {
            "title": "Temperate", "base": "meadow", "extra": ["minecraft:forest_rock", "minecraft:trees_windswept_hills"],
            "land": ["plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
                     "dark_forest", "dappled_forest", "meadow", "cherry_grove", "pale_garden", "taiga",
                     "old_growth_pine_taiga", "old_growth_spruce_taiga", "windswept_hills", "windswept_forest",
                     "windswept_gravelly_hills"]},
        "foothills_dry": {
            "title": "Dry", "base": "savanna_plateau", "extra": [],
            "land": ["desert", "savanna", "savanna_plateau", "windswept_savanna", "badlands", "eroded_badlands", "wooded_badlands"]},
        "foothills_wet": {
            "title": "Wet", "base": "sparse_jungle", "extra": [],
            "land": ["jungle", "sparse_jungle", "bamboo_jungle", "swamp", "mangrove_swamp"]},
    }
    not_land = ("ocean", "river", "beach", "caves", "deep_dark", "mushroom", "stony_shore", "_peaks")
    overworld = [v.split(":", 1)[1] for v in g.vanilla("tags/worldgen/biome/is_overworld.json")["values"] if isinstance(v, str)]
    land = [bare for bare in overworld if not any(word in bare for word in not_land)]
    assigned = [bare for group in foothills.values() for bare in group["land"]]
    assert sorted(assigned) == sorted(land), (
        "every vanilla land biome must belong to exactly one foothill group; "
        f"unassigned: {sorted(set(land) - set(assigned))}, unknown/duplicate: {sorted(set(assigned) - set(land))}")

    order = feature_order(g, overworld)
    lang = {}
    variants = {}
    for name, group in foothills.items():
        biome = g.vanilla(f"worldgen/biome/{group['base']}.json")
        features = [list(step) for step in biome["features"]]
        for extra in group["extra"]:
            step = next(i for i, position in enumerate(order) if extra in position)
            if extra not in features[step]:
                features[step].append(extra)
        for i, step_features in enumerate(features):
            step_features.sort(key=lambda f: order[i][f])
        biome["features"] = features
        g.write(f"data/{NS}/worldgen/biome/{name}.json", biome)
        lang[f"biome.{NS}.{name}"] = f"Epic Foothills ({group['title']})"
        for bare in group["land"]:
            variants[f"minecraft:{bare}"] = f"{NS}:{name}"
        # treated like the biome it is built on (tags, spawns, surface rules)
        stand_in.setdefault(f"minecraft:{group['base']}", []).append(f"{NS}:{name}")

    # River biomes: clones of vanilla's river and frozen river (same water, mobs and surface), one pair for the corridors of tributaries
    # and one for grand rivers, so /locate can tell them apart. Each also lists the feature that cuts the channel and fills it with water
    # (RiverChannelFeature), which runs for any chunk that contains one of them.
    rivers = {
        "epic_river": ("river", "Epic River"),
        "epic_frozen_river": ("frozen_river", "Epic Frozen River"),
        "epic_grand_river": ("river", "Epic Grand River"),
        "epic_frozen_grand_river": ("frozen_river", "Epic Frozen Grand River"),
    }
    g.write(f"data/{NS}/worldgen/feature/river_channel.json", {"type": f"{NS}:river_channel"})
    g.write(f"data/{NS}/worldgen/placed_feature/river_channel.json", {"feature": f"{NS}:river_channel", "placement": []})
    for name, (source, title) in rivers.items():
        biome = g.vanilla(f"worldgen/biome/{source}.json")
        features = [list(step) for step in biome["features"]]
        assert features[0] == [], "vanilla river biomes list nothing in the first decoration step"
        features[0] = [f"{NS}:river_channel"]
        # Vanilla springs (a single source block in rock with one open face) love the walls a carved gorge exposes, and then pour
        # sheets of water down the hillside; an epic river already has all the water it needs.
        features = [[f for f in step if f != "minecraft:spring_water"] for step in features]
        biome["features"] = features
        g.write(f"data/{NS}/worldgen/biome/{name}.json", biome)
        lang[f"biome.{NS}.{name}"] = title
        stand_in.setdefault(f"minecraft:{source}", []).append(f"{NS}:{name}")
    # Biomes for the features of the Earth world type's islands, so that vanilla's /locate biome can find them: a volcano's summit and crater
    # rim (stony peaks), an atoll's reef flat and islets (a beach, so turtles and sand and shipwrecks come with it) and a crater lake (a river:
    # fresh water). Each is a clone of the vanilla biome it stands in for, so it is in the same vanilla tags and gets the same surface and mobs.
    island_biomes = {
        "volcano": ("stony_peaks", "Volcano"),
        "atoll": ("beach", "Atoll"),
        "crater_lake": ("river", "Crater Lake"),
    }
    for name, (source, title) in island_biomes.items():
        g.write(f"data/{NS}/worldgen/biome/{name}.json", g.vanilla(f"worldgen/biome/{source}.json"))
        lang[f"biome.{NS}.{name}"] = title
        stand_in.setdefault(f"minecraft:{source}", []).append(f"{NS}:{name}")

    # Which river biome stands in for what vanilla would have put there: frozen where it is cold, plain otherwise. Oceans, caves and
    # mushroom fields are left alone (a river corridor is only ever labelled over dry ground anyway).
    cold = set(foothills["foothills_cold"]["land"]) | {"frozen_peaks", "jagged_peaks", "frozen_river", "snowy_beach"}
    skipped = ("ocean", "caves", "deep_dark", "mushroom")
    river_land = [bare for bare in overworld if not any(word in bare for word in skipped)]
    river_variants = {
        kind: [{"from": f"minecraft:{bare}", "to": f"{NS}:{'epic_frozen_' if bare in cold else 'epic_'}{kind}"} for bare in river_land]
        for kind in ("river", "grand_river")
    }

    # Wherever vanilla treats a biome specially (tags, spawns, surface), treat its epic twin the same way.
    for tag in g.vanilla_tags("worldgen/biome"):
        entries = g.vanilla(f"tags/worldgen/biome/{tag}.json")["values"]
        ids = [e if isinstance(e, str) else e.get("id") for e in entries]
        twins = [twin for i in ids for twin in stand_in.get(i, [])]
        if twins:
            g.write(f"data/minecraft/tags/worldgen/biome/{tag}.json", {"values": twins})

    tag_dir = f"data/{NS}/tags/worldgen/biome"
    g.write(f"{tag_dir}/replaced_by_epic_peaks.json",
            {"values": ["minecraft:frozen_peaks", "minecraft:jagged_peaks", "minecraft:stony_peaks"]})
    g.write(f"{tag_dir}/epic_range.json", {"values": [f"{NS}:epic_range_peaks"]})
    g.write(f"{tag_dir}/epic_wall.json", {"values": [f"{NS}:epic_wall_peaks"]})
    g.write(f"{tag_dir}/epic_mountain.json", {"values": [f"#{NS}:epic_range", f"#{NS}:epic_wall"]})
    g.write(f"{tag_dir}/epic_foothills.json", {"values": [f"{NS}:{name}" for name in foothills]})
    g.write(f"{tag_dir}/epic_river.json", {"values": [f"{NS}:epic_river", f"{NS}:epic_frozen_river"]})
    g.write(f"{tag_dir}/epic_grand_river.json", {"values": [f"{NS}:epic_grand_river", f"{NS}:epic_frozen_grand_river"]})
    g.write(f"{tag_dir}/epic_rivers.json", {"values": [f"#{NS}:epic_river", f"#{NS}:epic_grand_river"]})
    g.write(f"{tag_dir}/epic_terrain.json", {"values": [f"#{NS}:epic_mountain", f"#{NS}:epic_foothills", f"#{NS}:epic_rivers"]})

    # The highlights of the Earth world type, for vanilla's /locate biome #epicterrain:earth_... (made of vanilla biomes and tags, which is
    # what the Earth biome mapping answers with, plus the three island biomes above).
    earth_tags = {
        "earth_mushroom_island": ["minecraft:mushroom_fields"],
        "earth_volcano": [f"{NS}:volcano"],
        "earth_atoll": [f"{NS}:atoll"],
        "earth_crater_lake": [f"{NS}:crater_lake"],
        "earth_islands": ["minecraft:mushroom_fields", f"#{NS}:earth_volcano", f"#{NS}:earth_atoll", f"#{NS}:earth_crater_lake"],
        "earth_peaks": ["minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:stony_peaks"],
        "earth_mountains": [f"#{NS}:earth_peaks", f"#{NS}:earth_volcano", "minecraft:snowy_slopes", "minecraft:grove"],
        "earth_highlands": ["#minecraft:is_mountain"],
        "earth_open_ocean": ["#minecraft:is_deep_ocean"],
        "earth_badlands": ["#minecraft:is_badlands"],
    }

    def with_twins(values):
        # a copy of a vanilla biome is in every tag the original is in, ours included (EpicBiomeSourceTest checks it)
        out = []
        for value in values:
            for entry in [value] + ([] if value.startswith("#") else stand_in.get(value, [])):
                if entry not in out:
                    out.append(entry)
        return out

    for name, values in earth_tags.items():
        g.write(f"{tag_dir}/{name}.json", {"values": with_twins(values)})

    def add_epic_biomes(node):
        if isinstance(node, dict):
            out = {}
            for key, value in node.items():
                if key == "biome_is":
                    ids = [value] if isinstance(value, str) else list(value)
                    twins = [twin for i in ids for twin in stand_in.get(i, [])]
                    out[key] = (ids + twins) if twins else value
                else:
                    out[key] = add_epic_biomes(value)
            return out
        if isinstance(node, list):
            return [add_epic_biomes(n) for n in node]
        return node

    rule_names = {V + "surface": E + "surface", V + "biome_surface": E + "biome_surface",
                  V + "under_biome_surface": E + "under_biome_surface"}
    for leaf in ("surface", "biome_surface", "under_biome_surface"):
        rule = add_epic_biomes(g.vanilla(f"worldgen/material_rule/overworld/{leaf}.json"))
        g.write(f"data/{NS}/worldgen/material_rule/overworld/{leaf}.json", substitute(rule, rule_names, []))
    g.write(f"data/{NS}/worldgen/material_rule/overworld.json",
            substitute(g.vanilla("worldgen/material_rule/overworld.json"), rule_names, []))

    # ---- noise settings -----------------------------------------------------------------------------------
    ns = g.vanilla("worldgen/noise_settings/overworld.json")
    ns["noise"] = {"min_y": t["min_y"], "height": t["height"]}
    ns["material_rule"] = f"{NS}:overworld"
    router = ns["noise_router"]
    router.update({
        "continents": f"{NS}:climate/continents", "erosion": f"{NS}:climate/erosion", "ridges": f"{NS}:climate/ridges",
        "temperature": f"{NS}:climate/temperature", "depth": E + "depth",
        "chunk_surface_level": E + "chunk_surface_level", "final_density": E + "final_density",
    })
    ns["aquifers"]["surface_level"] = E + "preliminary_surface_level"
    ns["aquifers"]["exclusion"] = substitute(ns["aquifers"]["exclusion"], {V + "depth": E + "depth"}, [])
    ns.pop("debug_functions", None)
    g.write(f"data/{NS}/worldgen/noise_settings/overworld.json", ns)

    # ---- dimension type ---------------------------------------------------------------------------------------
    dim = g.vanilla("dimension_type/overworld.json")
    dim["min_y"], dim["height"], dim["logical_height"] = t["min_y"], t["height"], t["height"]
    dim["attributes"]["minecraft:visual/cloud_height"] = t["cloud_height"]
    g.write(f"data/{NS}/dimension_type/overworld.json", dim)

    # ---- world preset -----------------------------------------------------------------------------------------
    preset = g.vanilla("worldgen/world_preset/normal.json")
    over = preset["dimensions"]["minecraft:overworld"]
    over["type"] = f"{NS}:overworld"
    over["generator"]["settings"] = f"{NS}:overworld"
    over["generator"]["biome_source"] = {
        "type": f"{NS}:epic_multi_noise",
        "base": {"preset": "minecraft:overworld"},
        "zones": [
            {"biome": f"{NS}:epic_range_peaks", "replaces": f"#{NS}:replaced_by_epic_peaks"},
            {"biome": f"{NS}:epic_wall_peaks", "replaces": f"#{NS}:replaced_by_epic_peaks"},
            {"variants": [{"from": src, "to": dst} for src, dst in variants.items()]},
            {"variants": river_variants["river"]},
            {"variants": river_variants["grand_river"]},
        ],
    }
    g.write(f"data/{NS}/worldgen/world_preset/epic.json", preset)

    # ---- the Earth world type ---------------------------------------------------------------------------------
    # Its terrain, surface height and 'is dry land' come from one Java density function (EarthField); the ground is not built from noise. Settings are
    # vanilla's overworld with those in the router, the Epic type's taller world and surface rules, and vanilla's caves, aquifers and carvers underneath.
    earth_density = g.fn("earth/density", {"type": f"{NS}:earth_field", "output": "density"})
    earth_surface = g.fn("earth/surface", {"type": f"{NS}:earth_field", "output": "surface"})
    earth_land = g.fn("earth/land", {"type": f"{NS}:earth_field", "output": "land"})

    # The underground is vanilla's, reused as it is: the cheese, spaghetti, noodle and pillar caves, the cave entrances, the structures' terrain adaptation (the
    # beardifier) and, below, the aquifers. Vanilla builds its density as "terrain with caves cut into it", and keys the caves on how far down the terrain's own
    # density is (its sloped cheese, 1.5625 and more being deep enough for caves, about 12 blocks down). So the Earth ground takes the place of the sloped cheese
    # at vanilla's slope (0.125 a block; and 0.08 a block once squeezed, which is what the beardifier's strength was made for). The ground itself is kept out
    # of the interpolated part (vanilla interpolates the whole density over 4 x 8 blocks, which would blur a river three blocks wide into the bank): the
    # final density is the exact ground, or the interpolated caves, whichever is lower.
    V = "minecraft:overworld/"
    earth_terrain = g.fn("earth/terrain", mul(earth_density, 0.08))
    earth_cheese = g.fn("earth/sloped_cheese", mul(earth_density, 0.125))
    earth_top_slide = grad_y(t["slide_top_start"], 1.0, t["slide_top_end"], 0.0)  # vanilla's fades the caves out at 240 to 256; the Earth world is taller
    earth_counts = {"slide": 0}

    def earth_slide_rule(node):
        if node.get("type") == "minecraft:gradient" and node.get("axis") == "y" and (node["from_coordinate"], node["to_coordinate"]) == (240, 256):
            earth_counts["slide"] += 1
            return earth_top_slide
        return None

    vanilla_final = g.vanilla("worldgen/density_function/overworld/final_density.json")
    # add(min(squeeze(interpolated(...terrain and caves...)), noodle caves), beardifier)
    assert vanilla_final["type"] == "minecraft:add" and vanilla_final["left"]["type"] == "minecraft:min" and vanilla_final["left"]["left"]["type"] == "minecraft:squeeze"
    assert vanilla_final["right"]["type"] == "minecraft:beardifier" and vanilla_final["left"]["right"] == V + "caves/noodle"
    caves = substitute(vanilla_final["left"]["left"], {V + "sloped_cheese": earth_cheese}, [earth_slide_rule])
    assert earth_counts["slide"] == 1, earth_counts
    earth_final = g.fn("earth/final_density", add(mn(mn(caves, earth_terrain), vanilla_final["left"]["right"]), vanilla_final["right"]))

    earth_settings = g.vanilla("worldgen/noise_settings/overworld.json")
    earth_settings["noise"] = {**earth_settings["noise"], "min_y": t["min_y"], "height": t["height"]}
    earth_settings["material_rule"] = f"{NS}:overworld"
    earth_settings["noise_router"].update({
        "continents": earth_surface, "erosion": 0.0, "depth": 0.0,
        "chunk_surface_level": earth_surface, "final_density": earth_final,
    })
    # Vanilla's aquifers (the water and lava pockets of the underground, and the flooding of caves below the water table) with the Earth ground as the surface they
    # hang from. Where a river or a lake stands above the sea, the generator's fluid picker still says how high its water is, and the aquifers see that. The
    # exclusion (vanilla keeps aquifers out of the deep, mountainous parts) is vanilla's lowland value: it reads vanilla's terrain, which this world does not have.
    earth_settings["aquifers"]["surface_level"] = earth_surface
    earth_settings["aquifers"]["exclusion"] = -0.225
    earth_settings.pop("debug_functions", None)
    earth_settings["spawn_target"] = [{earth_land: [0.5, 1.0]}]
    g.write(f"data/{NS}/worldgen/noise_settings/earth.json", earth_settings)

    earth_preset = g.vanilla("worldgen/world_preset/normal.json")
    earth_over = earth_preset["dimensions"]["minecraft:overworld"]
    earth_over["type"] = f"{NS}:overworld"
    earth_over["generator"] = {
        "type": f"{NS}:earth_noise",
        "settings": f"{NS}:earth",
        "biome_source": {
            "type": f"{NS}:earth",
            "base": {"preset": "minecraft:overworld"},
            "volcano": f"{NS}:volcano",
            "atoll": f"{NS}:atoll",
            "crater_lake": f"{NS}:crater_lake",
        },
    }
    g.write(f"data/{NS}/worldgen/world_preset/earth.json", earth_preset)
    lang[f"generator.{NS}.earth"] = "Earth (prototype)"
    g.write("data/minecraft/tags/worldgen/world_preset/normal.json", {"values": [f"{NS}:epic", f"{NS}:earth"]})
    g.write(f"assets/{NS}/lang/en_us.json", {
        **lang,
        f"generator.{NS}.epic": "Epic Terrain",
        f"biome.{NS}.epic_range_peaks": "Epic Range Peaks",
        f"biome.{NS}.epic_wall_peaks": "Epic Wall Peaks",
        f"{NS}.customize.title": "Customize Epic Terrain",
        f"{NS}.customize.amount": "Epic Terrain: %s%%",
        f"{NS}.customize.hint.scale": "How much of the world is epic mountain country. 1% is a few lone massifs; 100% is ranges everywhere.",
        f"{NS}.customize.hint.default": "Default: %s%%. The rest of the world generates exactly like vanilla.",
    })


def main():
    default_jar = os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "versions", "26.3", "26.3.jar")
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=default_jar)
    args = parser.parse_args()
    if not os.path.exists(args.jar):
        sys.exit(f"Minecraft jar not found: {args.jar}")

    # Start from a clean slate so renamed or removed functions don't leave stale files behind.
    shutil.rmtree(os.path.join(RES, "data", NS), ignore_errors=True)
    shutil.rmtree(os.path.join(RES, "data", "minecraft"), ignore_errors=True)  # only ever holds tags generated below
    g = Gen(args.jar)
    build(g)
    print(f"wrote {len(g.written)} files under {RES}")


if __name__ == "__main__":
    main()
