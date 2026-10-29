# Changelog

## 1.1.1

* **Renamed (names shown only): the mod is now called Tectonic Terrain, and its world type "Earth"** (it was "Earth (prototype)"). The mod id `epicterrain`, the jar name `epicterrain-*.jar`, the world preset
  `epicterrain:earth`, the biome and tag ids and the startup options are unchanged, so existing Earth worlds open as before. The log line at startup is now `Tectonic Terrain loaded`.

## 1.1.0

* **Removed: the "Epic Terrain" world type.** The mod now has one world type, **Earth (prototype)**, which is closer to what the mod is for. With it went the Customize slider, the epic range and river
  biomes (`epicterrain:epic_*`, `foothills_*`) and their `/locate` tags, the epic rivers, and the code behind them. **A world made with 1.0.0's Epic Terrain type will not open with this version**
  (it names a generator that is gone); keep 1.0.0 for such a world. Earth worlds, the Earth biomes and tags (`#epicterrain:earth_*`) and the Earth options are unchanged. The mod id and the name
  `Epic Terrain` are kept so that Earth worlds still open.
* **Earth world: the bands of the rock layers on the plains curve instead of running as long parallel diagonal streaks.** Seen from above, the 1.0.0 plains were combed in straight bands about
  200 blocks apart across the whole continent. The beds now dip a little less (0.25 m a block, from 0.35) and their outcrops bend more (the warp is 2.5 times larger and its waves 12 km across, from
  16 km), so the bands flow in gentle S-curves. How bumpy the ground is does not change (the ripple is 4.6 blocks high, against 4.8), and the coasts, mountain ranges and tallest peaks are the same; lakes
  and rivers shift slightly because erosion comes out a little differently.
* New options `-Depicterrain.earth.tilt`, `.bend` and `.bendScale` for those three numbers (`tilt=0.35 bend=1 bendScale=16000` is the 1.0.0 pattern).
* **Earth worlds made with 1.0.0 will show a seam** where newly generated chunks meet old ones, because the terrain pattern changed. A new world is not affected.

## 1.0.0

The first release. Two world types for Minecraft Java 26.3 (Fabric Loader 0.20.1+, Fabric API 0.165.0+26.3).

### Earth (prototype) world type
* A continent 37.5 km across, simulated from plate tectonics, rock layers, erosion and climate; deep ocean and islands beyond it.
* Rivers from the simulation's own drainage (3 blocks wide at the smallest, about 70 at the largest), cut into the ground with valleys and floodplains, with water at levels of their own; lakes.
* Islands in the open ocean: hotspot chains of volcanoes (with crater lakes), atolls, island arcs, and mushroom islands at about vanilla's rate; islands in the seas on the continent.
* Vanilla's biomes, chosen from vanilla's climate table by the simulated height, rainfall and distance from the sea, with beaches on every coast; three biomes of its own (volcano, atoll, crater lake) and
  biome tags for `/locate` (`#epicterrain:earth_*`).
* Vanilla's underground: noise caves, cave entrances, ravines and the classic carvers, aquifers (kept below the ground by a small mixin so that rivers above the sea keep their water), ore veins, deepslate and
  bedrock, structures' terrain adaptation, and the cave biomes (lush, dripstone and sulfur caves, the deep dark under mountains).
* `/locate biome`, `/locate structure`, explorer maps and eyes of ender give up after 5 seconds (`-Depicterrain.earth.locateSeconds`) instead of freezing the server.
* Startup options for the size and character of the continent (`-Depicterrain.earth.*`, listed in the user guide).

### Epic Terrain world type
* Mountain ranges (lines, arcs, bowls; foothill and wall ranges) blended into vanilla terrain with a hard outer edge, four foothill biomes, summit biomes, planned rivers and a Customize slider for how
  many ranges there are.

### Notes
* Earth worlds are tied to the mod version they were made with until the model is declared stable; options are not saved with the world.
* Earlier, unreleased builds: 0.1.0 (Epic Terrain), 0.2.0 (the Earth world without caves, tried in the real launcher with Distant Horizons), 0.3.0 (the underground and the search limit).
* Known gaps are listed in the user guide.
