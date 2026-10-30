# Changelog

## Unreleased

* **Water settles.** In testing there were many water source blocks standing still that began to flow as soon as one was updated: the water of rivers, lakes and crater lakes (which stands at a level of its own
  above the sea) was never marked for the fluid update that vanilla gives the water it places, so a river's water stepped down from column to column with a source block beside air at each step, and a lake's
  one-block surface could hang over a cave the carvers had opened under it. In a world played for a while there were 2,136 such blocks in 377 chunks (counting only neighbours in the same chunk; 1,919 of them
  above the sea, most at the height of one high lake). New chunks now have every block of water that has air beside it or under it marked for that update, once the chunk is carved and decorated, so the game
  settles it when the chunk is full: a step of a river becomes a small fall, a lake over a cave drains into it. `-Depicterrain.earth.settle=false` turns it off. Chunks generated before this are not changed.
* A script, `tools/worldmap/scan_water.py`, counts the water in a saved world that would flow if it were updated, so a world can be checked.

## 1.3.0

* **Rolling hills in the lowland, by region.** Biomes used to change over ground that stayed flat for miles: in the seeds measured the height range within 512 blocks was 8 to 15 blocks under plains, forests, taiga,
  jungle and savanna (vanilla's is 46 to 49), and one could walk 5 km before the ground had changed by 20 blocks (vanilla's 90th percentile is 0.8 km). Flat, rolling and hill country now follow one another over
  kilometres, dry land rolls less than wet (and the driest, flattest land has low dunes), and dry hill country is terraced into mesas. Now 22 to 35 blocks within 512 blocks, about two thirds of vanilla's, and the
  90th percentile of the walk is 2.8 to 2.9 km. Hills part round rivers and lakes (their valleys and basins stay level) and are left off the shore and the mountains; the coasts, ranges, rivers and tallest
  peaks are as they were. New option `-Depicterrain.earth.hills=` (1 by default; 0 is the flat lowland, 2 twice the hills).
* **The biomes follow the same regions and the simulated rain.** Hill country has the erosion of vanilla's hills, so in the hot it is badlands where flat country is desert, and in the far inland the plateau biomes
  come with it; humidity leans harder on the simulated rain (`rainbias` is 0.8, was 0.5, and the continent's dry range is stretched over the one the climate works in), so deserts lie in rain shadows and
  forests, dark forests, jungles and old-growth taiga on the wet ground, as the simulation put the rain.
* **Existing Earth worlds will show seams** where new chunks meet old ones (the ground by up to some tens of blocks in the lowland, and the biomes in many places). A new world is not affected.

## 1.2.0

* **Flooded swamps and marshes.** Low, flat land near the sea and the rivers is now marsh: its ground stands at the sea level with pools and hummocks of some 40 blocks, about a third of it water (what
  vanilla's swamps measure), and its biome is swamp, or mangrove swamp in the warm. The ground and the biome come from one field, so a swamp is where the ground is marsh. Swamps and mangrove swamps are
  about 1 to 4% of the land of a world, as in vanilla (vanilla's are 1.4% and 0.8%). New option `-Depicterrain.earth.wetland=` (1 by default; 0 turns marsh off, 2 makes more).
* **The 12 overworld biomes the Earth world never produced now occur:** swamp and mangrove swamp (above), the four windswept biomes (patches of the hill country, and of the near-coast lowland), stony shore (stretches of
  coast), stony peaks (bare, warm summits among the snowy ones) and the four deep oceans. With them come the ocean monument (in the deep ocean beyond the continent, some 15 km out), swamp huts, swamp ruined portals and
  the swamp and windswept-forest abandoned camps, so every structure vanilla's overworld has now generates (checked on three seeds, `VanillaCoverageProbeTest`), and every placed feature of every overworld biome
  belongs to a biome that occurs.
* **Deep ocean was never asked for, which was a bug:** the biome mapping compared the sea floor, stored eased to 85 blocks, with real depths at 10.6 m a block, so the 1,500 m it wanted could not be reached. The sea
  is now deep ocean from 62 blocks down (about 40% of the sea of the continent's window, and all of the open ocean; vanilla's own deep oceans are 18 to 29 blocks deep).
* The new patches are noise-driven and scale with the `variety` dial (0 leaves the bands clean).
* **Existing Earth worlds will show seams** where new chunks meet old ones: biomes change in many places, and the ground changes by a few blocks in marsh country. A new world is not affected.

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
