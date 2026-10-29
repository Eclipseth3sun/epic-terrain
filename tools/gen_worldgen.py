#!/usr/bin/env python3
"""Generates the worldgen JSON of the Earth world type under src/main/resources.

The Earth world's terrain is made by Java (EarthField, a density function); what this script writes is the datapack around it: its density functions and
noise settings (vanilla's, with the Earth ground under vanilla's own caves and aquifers), the world preset, three biomes of its own (volcano, atoll and
crater lake, cloned from vanilla's), the biome tags for /locate, and the surface rules that know those biomes. Vanilla pieces are read straight out of the
game jar and rewritten. Re-run after changing any constant:

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

TUNE = {
    "min_y": -64,
    "height": 576,                 # top at y=512
    "slide_top_start": 496,        # the caves fade out between these two y levels
    "slide_top_end": 512,
    "cloud_height": 330.33,
}


# ---------------------------------------------------------------------------------------------------------
# Tiny density-function builder
# ---------------------------------------------------------------------------------------------------------
def add(a, b): return {"type": "minecraft:add", "left": a, "right": b}
def mul(a, b): return {"type": "minecraft:mul", "left": a, "right": b}
def mn(a, b): return {"type": "minecraft:min", "left": a, "right": b}


def grad_y(y0, v0, y1, v1):
    return {"type": "minecraft:gradient", "axis": "y", "from_coordinate": y0, "from_value": v0,
            "to_coordinate": y1, "to_value": v1}


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


def build(g):
    t = TUNE
    V = "minecraft:overworld/"
    E = f"{NS}:overworld/"
    lang = {}
    # vanilla biome -> the biomes of ours that must be treated exactly like it (tags, spawns, surface rules)
    stand_in = {}

    # ---- biomes of the islands' features -----------------------------------------------------------------------------
    # Biomes for the features of the Earth world's islands, so that vanilla's /locate biome can find them: a volcano's summit and crater
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

    # Wherever vanilla treats a biome specially (tags, spawns, surface), treat its twin the same way.
    for tag in g.vanilla_tags("worldgen/biome"):
        entries = g.vanilla(f"tags/worldgen/biome/{tag}.json")["values"]
        ids = [e if isinstance(e, str) else e.get("id") for e in entries]
        twins = [twin for i in ids for twin in stand_in.get(i, [])]
        if twins:
            g.write(f"data/minecraft/tags/worldgen/biome/{tag}.json", {"values": twins})

    # The highlights of the Earth world, for vanilla's /locate biome #epicterrain:earth_... (made of vanilla biomes and tags, which is
    # what the Earth biome mapping answers with, plus the three island biomes above).
    tag_dir = f"data/{NS}/tags/worldgen/biome"
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
        # a twin of a vanilla biome is in every tag the original is in, ours included
        out = []
        for value in values:
            for entry in [value] + ([] if value.startswith("#") else stand_in.get(value, [])):
                if entry not in out:
                    out.append(entry)
        return out

    for name, values in earth_tags.items():
        g.write(f"{tag_dir}/{name}.json", {"values": with_twins(values)})

    # ---- surface rules, which know the island biomes ---------------------------------------------------------------
    def add_twins(node):
        if isinstance(node, dict):
            out = {}
            for key, value in node.items():
                if key == "biome_is":
                    ids = [value] if isinstance(value, str) else list(value)
                    twins = [twin for i in ids for twin in stand_in.get(i, [])]
                    out[key] = (ids + twins) if twins else value
                else:
                    out[key] = add_twins(value)
            return out
        if isinstance(node, list):
            return [add_twins(n) for n in node]
        return node

    rule_names = {V + "surface": E + "surface", V + "biome_surface": E + "biome_surface",
                  V + "under_biome_surface": E + "under_biome_surface"}
    for leaf in ("surface", "biome_surface", "under_biome_surface"):
        rule = add_twins(g.vanilla(f"worldgen/material_rule/overworld/{leaf}.json"))
        g.write(f"data/{NS}/worldgen/material_rule/overworld/{leaf}.json", substitute(rule, rule_names, []))
    g.write(f"data/{NS}/worldgen/material_rule/overworld.json",
            substitute(g.vanilla("worldgen/material_rule/overworld.json"), rule_names, []))

    # ---- dimension type ---------------------------------------------------------------------------------------
    dim = g.vanilla("dimension_type/overworld.json")
    dim["min_y"], dim["height"], dim["logical_height"] = t["min_y"], t["height"], t["height"]
    dim["attributes"]["minecraft:visual/cloud_height"] = t["cloud_height"]
    g.write(f"data/{NS}/dimension_type/overworld.json", dim)

    # ---- the Earth world type ---------------------------------------------------------------------------------
    # Its terrain, surface height and 'is dry land' come from one Java density function (EarthField); the ground is not built from noise. Settings are
    # vanilla's overworld with those in the router, the taller world and the surface rules above, and vanilla's caves, aquifers and carvers underneath.
    earth_density = g.fn("earth/density", {"type": f"{NS}:earth_field", "output": "density"})
    earth_surface = g.fn("earth/surface", {"type": f"{NS}:earth_field", "output": "surface"})
    earth_land = g.fn("earth/land", {"type": f"{NS}:earth_field", "output": "land"})

    # The underground is vanilla's, reused as it is: the cheese, spaghetti, noodle and pillar caves, the cave entrances, the structures' terrain adaptation (the
    # beardifier) and, below, the aquifers. Vanilla builds its density as "terrain with caves cut into it", and keys the caves on how far down the terrain's own
    # density is (its sloped cheese, 1.5625 and more being deep enough for caves, about 12 blocks down). So the Earth ground takes the place of the sloped cheese
    # at vanilla's slope (0.125 a block; and 0.08 a block once squeezed, which is what the beardifier's strength was made for). The ground itself is kept out
    # of the interpolated part (vanilla interpolates the whole density over 4 x 8 blocks, which would blur a river three blocks wide into the bank): the
    # final density is the exact ground, or the interpolated caves, whichever is lower.
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
    g.write("data/minecraft/tags/worldgen/world_preset/normal.json", {"values": [f"{NS}:earth"]})
    lang[f"generator.{NS}.earth"] = "Earth"
    g.write(f"assets/{NS}/lang/en_us.json", lang)


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
