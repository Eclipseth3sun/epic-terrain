# Changelog

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
