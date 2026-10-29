# Epic Terrain: user guide

Epic Terrain 1.0.1 is a Fabric mod for **Minecraft Java Edition 26.3**. It adds one world type next to vanilla's (Default, Superflat, Amplified and the rest are unchanged): **Earth (prototype)**.

An Earth world is a procedural continent. It is simulated from plate tectonics, rock layers, erosion and climate, so mountain belts, rivers that run from the mountains to the sea, lakes, coasts and
islands come from one physical process instead of layers of noise. Vanilla's biomes, caves, ores and structures are used on top of that ground. It is a world made for exploring, and a tall one: the
overworld is 576 blocks high (y -64 to 512).

Contents: [what you need](#what-you-need) · [installing](#installing) · [creating a world](#creating-a-world) · [the Earth world](#earth-prototype-world) ·
[shaders and distant terrain](#shaders-and-distant-terrain) · [options](#options-for-the-earth-world) · [troubleshooting](#troubleshooting) · [limitations](#known-limitations) · [reporting problems](#reporting-problems)

## What you need

Minecraft versions move fast and mods are tied to them, so use exactly these. Everything below is for **Minecraft Java Edition 26.3**.

**Required**

| Piece | Version | Where to get it |
|---|---|---|
| Fabric Loader | 0.20.1 or newer | [Fabric installer](https://fabricmc.net/use/installer/) |
| Fabric API | 0.165.0+26.3 (the version this mod is built against; newer 26.3 builds should work but were not tried) | [Modrinth: Fabric API](https://modrinth.com/mod/fabric-api) |
| Epic Terrain | 1.0.1 | [GitHub Releases](https://github.com/Eclipseth3sun/epic-terrain/releases) (`epicterrain-1.0.1.jar`) |

**Optional, for better graphics and a far view.** These versions were checked against Modrinth's lists for 26.3 on 2026-10-28.

| Piece | Version | Notes |
|---|---|---|
| [Sodium](https://modrinth.com/mod/sodium) | **0.9.2** (`sodium-fabric-0.9.2+mc26.3.jar`) | Faster rendering, and required by Iris and Voxy. Use 0.9.2 and not the 0.9.3 beta: Iris 1.11.7 and Voxy 0.2.20-beta both ask for exactly 0.9.2. |
| [Iris Shaders](https://modrinth.com/mod/iris) | **1.11.7** (`iris-fabric-1.11.7+mc26.3.jar`) | Shader packs. Needs Sodium 0.9.2. |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | **3.3.4** (`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`, 28 MB) | Draws simplified terrain far past your render distance, and generates it for you. **Recommended** (see below). |
| [Voxy](https://modrinth.com/mod/voxy) | 0.2.20-beta (`voxy-0.2.20-beta.jar`) | An alternative to Distant Horizons. It only shows terrain you have already loaded, so it is a poor fit for a world you have not explored yet. Needs Sodium 0.9.2 and Fabric API. Use one of the two, not both. |

**What has been tried.** The author played an Earth world in the real launcher in early-game survival: tamed a horse and rode about 15,000 blocks across the plains to the mountains, with Fabric API
0.165.0+26.3, Sodium 0.9.2, Iris 1.11.7, Distant Horizons 3.3.4 and the shader pack Complementary Reimagined r5.7.1. Voxy, dedicated servers and other worldgen mods have **not** been tried, and the caves
have had little play. Nothing above is a guarantee for your machine; if something does not work, see [reporting problems](#reporting-problems).

## Installing

1. **Install Fabric** for Minecraft 26.3 with the [Fabric installer](https://fabricmc.net/use/installer/): pick 26.3 and loader 0.20.1 or newer, and let it create a launcher profile.
2. **Open your mods folder.** Windows: `%APPDATA%\.minecraft\mods` (create it if it is not there). macOS: `~/Library/Application Support/minecraft/mods`. Linux: `~/.minecraft/mods`.
3. **Put the jars in it:** `fabric-api-0.165.0+26.3.jar` and `epicterrain-1.0.1.jar`, plus any optional mods from the table above. Remove older Epic Terrain jars so there is only one.
4. **Give the game memory** (recommended with Distant Horizons, and harmless without it). In the launcher, open the Fabric profile, then **More options** (or **Edit**) and in **JVM arguments** change
   `-Xmx2G` to `-Xmx6G` or more if your computer has the memory. The Earth world itself needs little (a full test of its simulation fits in 2 GB), but Distant Horizons keeps a lot.
5. **Start the game** with the Fabric profile. The log (`.minecraft/logs/latest.log`) should contain the line `Epic Terrain loaded`.

To uninstall, remove the jar. Worlds made with the Earth world type need the mod to open again.

## Creating a world

**Singleplayer > Create New World**, then on the **World** tab press the **World Type** button until it says **Earth (prototype)**. Everything else on that screen works as usual.
The seed works as usual too: the same seed gives the same world.

* **Earth worlds take a minute to start.** The continent is simulated when the world loads, with nothing on the screen to say so: expect 45 to 55 seconds on the author's PC, longer on a slower one, and again every
  time you open that world (nothing is saved to disk yet). The log says `Simulating the Earth window ...` and then `The Earth window is ready ...`.
* **Worlds from earlier versions.** Version 1.0.1 removed the first world type, **Epic Terrain** (vanilla terrain with ranges blended in). A world made with it will not open any more, because the
  world names a generator that is no longer in the mod (what the game shows then was not tried). Earth worlds are not affected. To keep playing an old Epic Terrain world, keep version 1.0.0 for it.

## Earth (prototype) world

### What you get

* **A continent 37.5 km across**, centred on the spawn. In the seeds measured about two thirds of that square is land. Past its edge there is deep ocean with islands in it. The world does not tile or continue
  past the edge yet, so a long boat trip finds only sea and islands. For scale: crossing it takes about two and a half hours on foot and about twenty minutes by elytra.
* **Mountain belts shaped by tectonics and rock.** Young ranges are tall and pointed (peaks around y 480); old ones are lower and rolling. Layers of hard and soft rock make cuesta ridges, terraced hills and
  domes. Fine relief (gullies and crags) is added at walking scale.
* **Rivers that start in the mountains and run to the sea**, 3 blocks wide at the smallest and wider as more land drains into them (about 70 blocks at the largest), cut into the ground with valleys and
  floodplains and with their water at a level of its own (so a river can stand well above the sea). Lakes fill basins and stand at their own level too.
* **Islands in the open ocean:** chains of volcanoes (a young volcano with a crater, sometimes holding a crater lake, then older worn ones), atolls (rings of coral islets round a lagoon), arcs of islands, and
  mushroom islands at about vanilla's rate for the ocean area. Small islands also stand in the seas on the continent.
* **Vanilla's biomes**, chosen from vanilla's own climate table with the height, rainfall and distance from the sea of the simulated ground, plus vanilla's randomness in temperature and humidity (there is no
  fixed latitude). Mountains turn from forest to meadow to snow as they climb. Each coast gets a beach.
* **Vanilla's underground:** caves (cheese, spaghetti and noodle caves, cave entrances, ravines), water and lava pockets, ore veins, deepslate, bedrock, and the cave biomes: lush, dripstone and sulfur caves, and
  the deep dark under mountains. Structures (villages, mineshafts, strongholds and the rest) are vanilla's and use the biome tags.
* **Three biomes of its own,** so that islands can be found: `epicterrain:volcano`, `epicterrain:atoll` and `epicterrain:crater_lake`.

There is no Customize screen; the settings are [startup options](#options-for-the-earth-world).

### Finding things

`/locate biome` with these tags uses vanilla's own search (6,400 blocks around you):

| Tag | Finds |
|---|---|
| `#epicterrain:earth_mushroom_island` | a mushroom island (about one per 270 km² of open ocean, so from inland the search usually finds none) |
| `#epicterrain:earth_volcano`, `#epicterrain:earth_atoll`, `#epicterrain:earth_crater_lake` | a volcano's summit, an atoll's reef, a crater lake |
| `#epicterrain:earth_islands` | any of those, or a mushroom island |
| `#epicterrain:earth_peaks`, `#epicterrain:earth_mountains`, `#epicterrain:earth_highlands` | the tall summits; mountains proper (with volcanoes, slopes and groves); the whole mountain band |
| `#epicterrain:earth_open_ocean` | the open ocean, where the islands are |
| `#epicterrain:earth_badlands` | the layered-rock country, where the climate reaches the hot badlands |

`/locate biome minecraft:lush_caves` (or dripstone or sulfur caves) finds cave biomes underground. **A search that has found nothing stops after 5 seconds** with vanilla's "could not find" message,
instead of freezing the server while it looks everywhere; so a rare thing can be reported missing when it is there. `/locate structure` and explorer maps use the same limit (see
`-Depicterrain.earth.locateSeconds` below to change it).

### Seeds, saving and updating

* The same world seed gives the same world every time, on any computer, as long as the [options](#options-for-the-earth-world) and the mod version are the same.
* **The options are not saved with the world.** If you start the game with different options than the world was made with, the unexplored parts will not match the explored parts. Keep them the same.
* **Updating the mod may change the Earth world.** The model is a prototype and later versions may change it; terrain generated by an older version would then not match new chunks beside it. Until the
  model is declared stable, treat an Earth world as tied to the version it was made with.
* Deleting a world deletes it for good, but a new world with the same seed has the same terrain (what you built is gone).

### Dedicated servers

Put the same `epicterrain` and Fabric API jars (not Sodium, Iris or Voxy, which are client mods) in the server's `mods` folder and set `level-type=epicterrain:earth` in `server.properties`
before the first start. **Not tried yet.** The first start of an Earth world simulates the continent as above, which takes a minute.

## Shaders and distant terrain

The world is built to be seen from a distance, and **Distant Horizons is the one to use with it**: it generates distant terrain itself, so you can see the continent from the start. Voxy only shows terrain
you have already loaded.

* **Memory:** give the game `-Xmx6G` or more (see [installing](#installing)).
* **What to expect:** distant terrain is built from real chunks of the Earth world (a chunk takes about 30 ms on one thread in tests, so a large radius takes a while to fill in). Be patient the first time.
* **Shaders:** shader packs go in the `shaderpacks` folder and are chosen in the game's video settings with Iris installed. How a pack draws Distant Horizons' terrain is up to the pack; some do not support it.
  Only Complementary Reimagined r5.7.1 was used with this mod.
* Both Iris and Voxy need **Sodium 0.9.2**; see [what you need](#what-you-need).

## Options for the Earth world

The Earth world can be changed with startup options. Add them to the launcher profile's **JVM arguments** (or the server's start command) as `-Depicterrain.earth.NAME=VALUE`, for example
`-Depicterrain.earth.cell=64 -Depicterrain.earth.locateSeconds=15`. **They change the world and are not saved with it**, so use the same ones every time you open a world.

| Option | Default | What it does |
|---|---|---|
| `cell` | 32 | Blocks per cell of the simulation. 64 is rougher but starts about four times faster; smaller is finer and slower and needs more memory. |
| `window` | 1.5 | Side of the simulated square, in plates (with the default plate size, 37.5 km). |
| `plate` | 25 | Size of a plate, and so of a continent, in km. |
| `continental` | 0.8 | Share of plates that carry continents (the rest are ocean floor). |
| `water` | 0.15 | Share of a continental plate that lies under water (shelf seas, gulfs). |
| `boundary` | 0.75 | Share of the big mountains that stand on plate boundaries; the rest are mid-plate. |
| `belt` | 3 | Width of a medium mountain belt, in km. |
| `steepness` | 2 | How many times steeper than real the terrain is. |
| `temperature` | 12 | Mean yearly temperature at sea level at the middle of the window, in °C. |
| `steps` | 140 | Time steps of the simulation (fewer is faster and rougher). |
| `rock` | 1 | How much rock layers matter: 0 is uniform rock, 2 is twice the contrast. |
| `tilt` | 0.25 | How steeply the rock beds dip, in metres per block. Where the beds meet the surface they make the faint bands on the plains; a smaller tilt widens them. |
| `bend`, `bendScale` | 2.5, 12000 | How far the outcrops of the beds curve (a multiplier on a 6 km warp) and the size of the waves of that warp, in blocks. Version 1.0.0 used `tilt=0.35`, `bend=1`, `bendScale=16000`, which gives straighter, more parallel bands. |
| `crag` | 1 | Strength of the fine relief: 0 is smooth, 2 twice as craggy. |
| `riverWidth` | 3 | Width of the smallest river, in blocks. |
| `riverDepth`, `floodplain` | 1, 1 | Multiply the depth of every river and the width of every floodplain. |
| `lapse`, `rainbias`, `variety` | 0.3, 0.5, 0.5 | How fast the climate cools with height; how far the simulated rainfall moves humidity (0 is pure vanilla, 1 is fully); how much the edges of the biome zones wander (0 is clean zones, 1 is a lot). |
| `mushroom`, `volcanic`, `islandSize` | 4.1, 1, 1 | Mushroom islands per 1,000 km² of open ocean (4.1 is about vanilla's rate); a multiplier on how common volcanic islands are; a multiplier on the size of every island. |
| `locateSeconds` | 5 | How long a `/locate` search may run before it gives up. |

## Troubleshooting

| What you see | What it means |
|---|---|
| The world sits on the loading screen for a minute. | Normal: the continent is being simulated. A coarser `cell` makes it quicker. |
| Out-of-memory crash or an "OutOfMemoryError" in the log. | Raise `-Xmx` (see [installing](#installing)), or use `-Depicterrain.earth.cell=64`. |
| The game does not start, or the mod list lacks Epic Terrain. | Check Fabric Loader 0.20.1+ and Fabric API 0.165.0+26.3 are installed for **26.3**, and there is only one Epic Terrain jar. Read `.minecraft/logs/latest.log` from the top. |
| `/locate` says it could not find something that should be there. | The 5-second limit, or it is beyond 6,400 blocks. Raise `locateSeconds`, or travel. |
| Distant Horizons fills in slowly. | It is generating real chunks through the Earth model; give it time, or lower its quality settings. |
| A shader pack shows no distant terrain. | The pack probably does not support Distant Horizons. Try another pack, or turn Distant Horizons off. |
| New chunks do not match old ones. | The options or the mod version differ from when the world was made (see [seeds](#seeds-saving-and-updating)). Version 1.0.1 curved the rock-layer bands, so an Earth world made with 1.0.0 shows a seam in the plains where new chunks begin; the options above set the 1.0.0 pattern back. |
| An Earth world made with version 0.2.0 has no caves in the parts you explored before. | 0.2.0 had no caves; 1.0.0 puts caves in newly generated chunks only. |

## Known limitations

* The continent is a single 37.5 km window; there is no more land beyond it, only ocean and islands.
* The simulation is not saved: every launch of a world simulates again (about a minute).
* Riverbeds are plain dirt or gravel from the surface rules; there are no waterfalls, deltas or oxbow lakes yet.
* Caves open the surface on about 8% of dry columns (vanilla's cave entrances), and where a cave lies just under the ground the surface can sit 1 to 3 blocks lower than the terrain model draws it (a fifth to two fifths of the columns in the land chunks tested); dripstone caves are most of the cave biomes at 30 to 80 blocks down, and lush caves are rare.
* Structures on this terrain, the cave decoration and dedicated servers have not been tried; the classic carvers and the underground have had little play.
* No glaciers or snowfields beyond what vanilla's biomes give; badlands appear only where the climate reaches them.

## Reporting problems

Open an issue at [github.com/Eclipseth3sun/epic-terrain/issues](https://github.com/Eclipseth3sun/epic-terrain/issues) and include: the mod version (1.0.1), your Minecraft, Fabric Loader, Fabric API and any other mod versions,
what world type and seed, what you did, and the end of `.minecraft/logs/latest.log` (and the file in `.minecraft/crash-reports` if the game crashed). For terrain you think looks wrong, a screenshot with F3
open and the coordinates is the most useful thing. How the mod works inside, and what has been measured, is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
