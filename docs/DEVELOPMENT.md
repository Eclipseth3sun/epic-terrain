# Development notes

How the mod is built, how the Earth world works inside, what was measured, and what is not done. For installing and playing, see [the user guide](../USER_GUIDE.md); for the
list of changes, [the changelog](../CHANGELOG.md). The long sections on the Earth world type are a working log: each says when it was written and what was and was not checked then.
The mod's first world type, "Epic Terrain" (mountain ranges blended into vanilla terrain, with planned rivers and a Customize slider), was removed in 1.1.0; where the log below mentions
it, that is history, and the code is in the 1.0.0 tag.

## Development

```
./gradlew build          # compile + run the tests
./gradlew runClient      # launch Minecraft with the mod (singleplayer testing)
./gradlew preview        # render terrain maps to build/preview (the Earth sections below say which tests)
python tools/gen_worldgen.py   # regenerate the worldgen JSON after changing a constant
```

The worldgen JSON under `src/main/resources/data/epicterrain` is **generated** by `tools/gen_worldgen.py`. The terrain itself is made by Java (the Earth model); the script writes the datapack around
it, and the few constants it has sit in the `TUNE` block at the top. Vanilla functions are read out of the game jar and rewritten, so don't edit the generated JSON by hand.

### Code map

| Piece | Where | What it does |
| --- | --- | --- |
| `EpicTerrain` | root | the entry point: registers the chunk generator, biome source and density function type |
| `Tectonics`, `PlateGrid`, `ContinentSimulator`, `Continent`, `Erosion`, `Climate` | `earth/` | the simulation of the continent: plates, uplift, erosion, rainfall and temperature (see the sections below) |
| `Landscape`, `Strata`, `Detail`, `Noise` | `earth/` | what is added to the simulated skeleton: rock layers, walking-scale relief, shared noise |
| `Rivers`, `Meander`, `RiverValley` | `earth/` | rivers from the simulation's own drainage, their bends, and the valley each cuts into the ground |
| `Wetlands` | `earth/` | the marsh: how marshy a column is (low ground, inland, rainy, a wide wetness noise) and the ground of a marsh (pools and hummocks at the sea level); the ground and the biome both read it |
| `Hills` | `earth/` | the lowland's rolling hills, mesas and dunes by region (laid in `Detail.blocks`), and the character of a region, which the biome mapping reads too |
| `Islands`, `BiomeClimate`, `EarthMetrics`, `EarthConfig` | `earth/` | islands in the open ocean, the biome mapping, measurements for the tests, and the `-Depicterrain.earth.*` options |
| `EarthWorld`, `EarthWorlds` | `earth/` | the simulated window behind one call, `sample(x, z)`; one per world seed, kept until the server stops |
| `EarthField` | `worldgen/` | the density function that turns that call into ground (`earth/density`, `earth/surface`, `earth/land`) |
| `EarthChunkGenerator`, `EarthFluidPicker` | `worldgen/` | vanilla's noise generator with the water level asked of the model column by column |
| `EarthBiomeSource` | `worldgen/` | vanilla's multi-noise table asked about the model's numbers, the three island biomes, and the cave biomes underground |
| `SearchBudget` | `worldgen/` | the 5-second limit on `/locate` and the other searches |
| `NoiseBasedAquiferMixin` | `mixin/` | keeps vanilla's aquifers below the ground so that rivers above the sea keep their water |
| `epicterrain.accesswidener` | resources | makes `NoiseBasedChunkGenerator` extendable (vanilla marks it final) |

The generator is only constructed where registries are already bound, and never calls `Holder.value()` while a codec builds it: registries load in parallel, so a holder that is not yet
bound would fail the load. `EarthFluidPicker` takes the settings lazily for that reason.

### The Earth-like terrain prototype (`com.epicterrain.earth`, stage A)

The model behind the Earth world type, first written as an offline prototype and connected to the game in a later stage (below): continents that come out of simulated plate
tectonics, erosion and climate instead of layered noise. Everything is in real units (metres, years) and is compressed into blocks at the end: a block of height is 10.6 m,
and a block of width is `steepness` times that.

* **Plates** (`Tectonics`, `PlateGrid`): a jittered Voronoi grid of plates about 25 km across (edges wobbled by noise), a pure function of
  the seed so no continent needs to know about another. 80% carry continental crust, the rest ocean floor, and each moves in some
  direction. What a boundary does follows from how the two plates move and what they are made of: collision (two continents closing),
  subduction (ocean floor dives under a continent: coastal range, volcanic arc, trench, dry basin behind), island arc, rift, passive margin,
  spreading ridge, transform. A second, coarser plate map stands for an earlier age: where its boundaries once collided there are
  *fossil* belts, now mid-plate. Mid-plate relief also comes from hotspot swells with volcano chains, basement domes, and block uplifts far
  inland of a subduction zone. About 75% of the relief is at boundaries (`boundary`), the rest mid-plate.
* **History** (`Landscape`, `Erosion`): the forces act in episodes of a 60-million-year history, which is what makes mountains young or old:
  fossil belts rise early and switch off (some are squeezed up again late), mid-plate swells rise in the middle, today's boundaries switch
  on in the second half and are still rising at the end, volcanoes build their cones last, and a slow background uplift of the whole
  continent keeps plains from going flat. Each step lifts the ground, finds where every cell's water goes (priority-flood: lakes fill to their
  spill point), cuts valleys with the stream-power law (implicit, so any step length is stable), drops what was cut where the slope eases, and
  lets soil creep. Rock hardness varies from place to place so valleys do not come out evenly spaced.
* **Water and climate** (`ContinentSimulator`, `Climate`): sea level is chosen afterwards so that exactly `water` of the continental plates
  is under it, so gulfs and inland seas appear where tectonics made basins; the window's edge falls away to a shelf and coast, as a
  continent's would. Rain comes with the wind off the sea, falls on windward slopes and leaves a rain shadow; temperature falls with height;
  biome classes follow. Rivers are wherever enough area drains, lakes are the filled depressions.
* **Dials** (`EarthConfig`, set with `-Depicterrain.earth.<name>`): `plate` (km, default 25), `continental` (0.8), `water` (0.15),
  `boundary` (0.75), `belt` (km, the medium belt's width, 3), `steepness` (2), `cell` (blocks per grid cell, 32 for quick looks, 16 for
  detail), `window` (side in plates, 1.5), `temperature`, `steps`. Steepness only sets how many real kilometres a block stands for:
  because belt width and peak height are fixed in blocks, it hardly changes the result. The erosion constants can be tried with
  `-Depicterrain.earth.k=`, `.d=`, `.kappa=`, `.uplift=`.

```
./gradlew preview --no-configuration-cache --tests com.epicterrain.earth.EarthPreviewTest -Depicterrain.seed=4242 [-Depicterrain.earth.compare=true]
```

writes `build/preview/earth_<seed>_*`: shaded relief with rivers and lakes, plates and boundaries (red collision, orange subduction, magenta
island arc, yellow rift, cyan passive margin, blue spreading, green transform; purple tint fossil belts, red tint young belts), uplift
episodes, rainfall, biomes, drainage, a cross-section, a zoom on the highest range, a contact sheet and, with `compare`, the same window with
belts 2, 3 and 4 km wide. It also prints numbers that check the model against real landscapes: river concavity (real: 0.3 to 0.6), Hack's
law exponent (0.5 to 0.6), the share of the continental plates under water (equals the dial), how high young and fossil belts stand, and
where the high ground is. A 1,172 x 1,172 cell window takes about 50 s to simulate at 32 blocks a cell.

What keeps a range from looking artificial: the crest wanders and varies in width and strength along its length, there is a finer
texture in the uplift, a belt thins out toward a corner where three plates meet (`PlateGrid.Hit.distance2`) instead of ending in a wedge,
rock hardness varies from patch to patch, soil creep is stronger on the shelf and coast at the window's edge so headlands are rounded,
and a lake bed is evened out sideways each step (sediment arrives along single paths, which otherwise leaves stripes in a filling lake).

Water on flat or smoothly sloping ground needs care, because the usual single-direction flow routing sends every drop the same way and
draws perfectly straight rivers at 45 degrees. Two things prevent it: across flat ground (pits, filled lakes) cells are taken in a smooth random
order (a tie-breaker of a few millimetres, `flatKey`), so water wanders; and on a slope each cell turns to the neighbour with the best slope
after a fixed random weighting of the eight directions (55% to 145%, `directionWeight`), so flow lines zigzag and merge. The preview prints
the longest straight run of plains river (it was 38 cells, now 11), and a test keeps it under 20.

A lake is a flooded depression at least one block (10.6 m) deep, which is the least the game can show; a shallower flood (about 9% of the land
floods at all, but the median is only 6.6 m deep) is just wet low ground at the same level. About 3.5% of the land is lake.

Known flaws at stage A: the coast still has a faint regular scalloping, slopes at the 32-block grid are gentle (nothing over 30 degrees:
cliffs and crags are not modelled), there are no badlands (they need layered rock) and no glaciers, and nothing yet turns the result into
blocks, biomes or rivers in the game. (`Climate`'s own biome classes are only a rough first look; the real answer is below.)

#### Biomes: vanilla's own table, steered by height (`BiomeClimate`)

Vanilla picks a biome from six climate numbers: temperature, humidity, continentalness (how far inland), erosion (0 mountainous, 6 flat and
swampy), depth, and weirdness (how peak-like; its sign picks between paired biomes). The world is infinite, so none of it can follow latitude.
So the model keeps vanilla's own noise for temperature, humidity and the sign of weirdness (regional variety exactly as vanilla has it) and
adds what the terrain knows:

* **Height cools**: the temperature number falls 0.3 per real kilometre (`lapse`), so a 4.4 km peak is frozen wherever it stands.
* **Relief picks the mountain zone**, not height above sea (a broad plateau is still plains). Relief is how far ground stands above the lowland
  around it (closing then opening of the height map: valleys filled, ranges narrower than about 7 km removed). Bands: lowland under 300 m,
  foothills 300 to 900, montane 900 to 1,800, subalpine 1,800 to 3,000, peaks above. Each band sets erosion and weirdness (0.25 / -0.3 / -0.55 /
  -0.9 / -0.9 and 0.4 / 0.4 / 0.3 / 0.3 / 0.67), and vanilla's table then answers: foothills and montane give meadow, cherry grove, forest, birch,
  pale garden, old-growth pine or spruce taiga (by the weirdness sign), jungle, savanna plateau; subalpine gives snowy slopes or grove; peaks give
  frozen or jagged peaks (stony peaks if warm). The regional temperature and humidity decide which, so a mountain is not only ever peaks.
* **Rain shadow** (`rainbias`, 0 to 1): the simulated rainfall moves humidity, windward slopes wetter and the lee drier; 0 is pure vanilla.
* **Variety** (`variety`, 0 to 1): noise wanders the band edges (up to 90% of the relief at 1), the parameters and the temperature (a tenth), so
  zones interleave and a cold region still has milder flanks.
* Rivers get the valley setting (weirdness 0, erosion -0.1) so vanilla answers river or frozen river; coast and sea floor set continentalness.

```
./gradlew preview --no-configuration-cache --tests com.epicterrain.earth.EarthBiomePreviewTest -Depicterrain.seed=4242 [-Depicterrain.earth.compare=true]
```

asks the real vanilla biome source about every cell and writes `earth_<seed>_c32_vanilla_biomes.png` (with a legend of land shares; `compare`
adds the same range at variety 0, 0.5 and 1), printing which biomes each band got. `MountainBiomeProbe` prints the raw table the design is built on
(temperature across, humidity down, for each erosion and weirdness), and `EarthBiomeClimateTest` pins the behaviour against that table.
On seed 4242 at the defaults: 81% lowland, 11% foothills, 4% montane, 2% subalpine, 1.6% peaks; over all the mountain bands cherry grove is 7.8%,
meadow 29%, grove 7%, snowy slopes 12%, frozen and jagged peaks 8.6%, taiga 2%, pale garden 3%, and old-growth taiga only 1.8%: it needs a cold
and wet slope, which the lapse mostly turns into snow.


How big is it (seed 4242, defaults; `EarthBiomePreviewTest.sizes` prints these): the window is 37.5 x 37.5 km (1,407 km2) around a 25 km plate;
land is 934 km2 (66%) in one landmass about 35 x 35 km; ground with more than 900 m of relief is 72 km2 (the biggest massif 52 km2, a box of
6 x 14 km), the longest river is 43 km, the biggest lake 10.6 km2 (3.7 km across), and the highest ground reaches the top of the world (the
preview caps heights at y 500). Crossing the 37.5 km takes 2.4 hours on foot, 78 minutes by boat, 21 minutes by elytra. The land has 34 biomes.
A visitor stands in a biome patch of about 1 km2 on average (1.1 km across); plains, snowy plains, savanna and meadow patches are 1.2 to 1.5 km2,
forest 0.7, taiga 0.4, birch forest 0.1, snowy slopes 3 km2 (2 km across), and the biggest single patches 3 to 6 km2; there are also plenty of
tiny ones (the median patch is about 100 x 100 blocks). Walking in a straight line the biome changes about every 280 blocks; vanilla's own
source over the same square changes every 210 blocks and its patches are about a third of the area (0.3 km2), because vanilla also varies
erosion, weirdness and continentalness every few hundred blocks, where here they change only with relief.

### Walking-scale detail (`Detail`)

The simulation works on 32-block cells, so by itself the ground is smooth between cells: over 16 blocks (the baseline used for vanilla and for the removed Epic
ranges) it averages 1 degree in the lowlands, 4 in the foothills and 8 in the montane band, subalpine ground and peaks, with nothing steeper
than 30 degrees. `Detail` lays the walking-scale relief back over a bicubic reading of the simulated heights, as a function of position only (so
any chunk can ask for any column and neighbours agree):

- **Where:** none on ground with under 80 m of relief (plains, shores and the sea floor are left alone), all of it from 1,400 m, and half as much
  (the `age` factor) in old ranges that are not also young; it fades out within 45 m of sea level and, to 8%, beside a river. Land stays land: a
  cut can take away at most 60% of the height there is, so the simulation alone decides what is above and below sea level.
- **What:** mostly *gullies*: ribs and channels running down the fall line, in three scales (stripes 120, 56 and 26 blocks apart, cutting up to
  28, 13 and 5.5 blocks at crag 1 before the strength factor). The stripes follow the fall line of the smooth ground at the point itself, measured
  over about one spacing, so the coarse ones run straight down it and the fine ones follow the local ground and wander (a stray of 0.22, 0.5 and 0.8
  radians). Patches on a jittered grid each lay stripes with a phase of their own and the patches around a point are added with their phases
  (like phacelle noise), so the pattern is continuous; where they disagree the stripes fade. Where the ground is level (a summit, a saddle, a
  plateau) there is no fall line and the gullies fade out. On top of that: swells (170 blocks), fine roughness (15 blocks) and, in the foothills,
  a little low roughness.
- **Slopes it gives** (seed 4242, 16-block baseline, `EarthDetailPreviewTest`): foothills 8.4 degrees mean, montane 23.8, subalpine 26.5, peaks
  27.6 (41% steeper than 30 degrees, 16% steeper than 45, 3% steeper than 60). Vanilla measured the same way: peaks 35.5 mean (30% over 45), hills
  24.4. So mountains are a little gentler than vanilla's jagged peaks and about as steep as its hills; `-Depicterrain.earth.crag` scales it (2 is
  twice as cut). Over one block the mean step to the neighbouring column is 0.8 blocks and the worst seen 5.
- **What did not work, and why:** isotropic noise (crumple with no ridges or valleys, and it needed 3 times the strength to reach 30 degrees on
  the peaks, so it was all texture); stripes with a direction fixed per patch (fall lines turn all the way round a summit faster than a patch is wide,
  so the stripes lost the slope: along the fall line they changed nearly as fast as across it); and the direction measured over a fixed 90 blocks
  (near level ground the angle swings fast and, times the distance to the patch centre, twisted the stripes into 25-block steps between
  neighbouring columns).
- **Limits.** The gullies are a texture over the simulated ground, not erosion of it, so they do not join up into a drainage network and do not
  deepen toward the rivers; summits and saddles are smooth domes (no fall line); rock layers, cliffs and benches are stage B. It is a prototype of
  the shape and cost, not the in-game version: that needs chunk-local evaluation and no continent-wide arrays.
- **Tests** (`EarthDetailTest`, seed 7 so the small test continent has mountains): the same position gives the same ground whatever was asked
  before; crag 0 is exactly the smooth ground; plains, shores and the sea floor are untouched; land stays land and sea stays sea; the change is
  bounded (under 80 blocks, 54 seen) and really there (mean 11 blocks on high ground); doubling the crag about doubles it; the coarse gullies run
  down the slope (2.7 times more change across it than along it for the coarsest, 1.7 for the middle one; the finest is texture) and neighbouring columns never differ by
  more than 6 blocks.

### Rivers of the Earth model (`Rivers`)

Decision (2026-10-28): the Earth world type does not use the planned grand-river model; its rivers come from the simulation's own drainage, and the
widths follow one rule. (The planned grand-river model belonged to the Epic world type, since removed.)

- **Where:** every cell drains to one neighbour and a cell is a river where at least 400,000 square blocks (180 km2 real) drain through it. That
  tree is cut into rivers: each starts at a source and follows its cells downstream, carrying on through a confluence along the branch that
  drains more, until it reaches the water or is taken in by a bigger one. A lake ends the rivers that run into it and its outlet starts a new one.
  On seed 4242 that is 771 rivers.
- **Width** (the user agreed this rule): at true scale these rivers would be hair-thin (a block is 21 m across, so the biggest river of the window,
  98,000 km2 of catchment, is about 10 blocks), so width is an exaggeration: 3 blocks at the smallest river, growing with the square root of the
  drainage area, `3 * sqrt(area / 400,000 blocks2)`, which is 70 blocks for the biggest catchment (218 million blocks2). `-Depicterrain.earth.riverWidth`
  sets the 3.
- **Shape:** the cells are 32 blocks apart and the route is a chain of 45-degree steps, so each river is corner-cut twice, relaxed to the scale of its
  width and given bends by the original model's `Meander` (made public for this) where the ground is gentle; the nodes are 16 blocks apart. A
  tributary ends on the finished line of the river it joins (the nearest node, within 14 cells).
- **Numbers** (seed 4242): 1.19 million blocks of river in the 37.5 km window, 24% more than along the cell chains because of the bends; by width,
  504,000 blocks under 5 wide, 396,000 at 5 to 10, 131,000 at 10 to 20, 99,000 at 20 to 40 and 57,000 at 40 or more; the longest single river is
  48 km, the widest long one (27 km) is 68 blocks wide at its end; of the 81 rivers 10 or more blocks wide at the mouth, 2% of their nodes bend
  tighter than their own width. Built in 0.3 s. `build/preview/earth_4242_c32_rivers_closeups.png` and `..._rivers_overview.png`.
- **Limits.** Only lines and widths: nothing carves the ground yet (the close-ups draw the water on top of the shaded ground), so there are no
  valleys, banks, gorges or waterfalls, no water levels, and the lakes are not joined to the lines; `Detail` still fades its relief near the
  cells flagged as river, not near the finished lines, which now wander up to several hundred blocks from the cells on a wide river; the widest
  river on the map is a short link between two lakes (940 blocks), not a trunk. The original model's `RiverCarver`, bed floor and cave seal are the
  likely way to carve them.
- **Tests** (`EarthRiversTest`, on the real seed 4242 window): the width rule; every river cell on exactly one river; every river starts at a source
  and ends in the water or on a cell of a bigger river; tributaries end on the finished line of the river they join (exactly, for nearly all);
  width never shrinks downstream and nodes are evenly spaced; the widest river is 60 to 80 blocks; the bends add 5% to 60% to the length; under
  10% of wide rivers' nodes bend tighter than the river is wide; building twice gives the same rivers.

### River valleys of the Earth model (`RiverValley`)

The user said (2026-10-28) that carving rivers should NOT be done the way the Epic world type (since removed) did it: "it straight up looked unnatural". That
approach cut a channel into ground that knew nothing of the river (a parabolic bed under flat water, walls at a fixed 0.8 slope, a lip along the
bank, a bed floor, cave seals, water placed afterwards by a feature). Here the river is one more term in the ground's height function and the water
is only "wherever the ground is under the river's level", so the ground and the water cannot disagree and nothing has to be patched up:

- **A water profile first.** Each river's level follows the bed the simulation settled on along the cells it routed the river over (monotone by
  construction, and the valley bottom), 1.2 blocks under it, lightly smoothed, never rising downstream, falling no faster than `0.5 / (1 + width/8)`
  blocks per block (a steeper reach is cut into a gorge of at most 14 blocks instead), starting from a shallow head and ending exactly at the level of
  the sea, a lake or the bigger river it joins. (First try: the ground at the nodes, running minimum, as the Epic planner did. Every dip near the
  source set the level for the rest of the river: a 12 km gorge. Reading the simulation's own bed fixed it: ground more than 8 blocks above the water
  at a node went from 2.1% of nodes to 0.1%.)
- **A cross-section that follows from it.** Depth `1.6 + 0.1 * width` blocks (at most 8), a bed that is deeper toward the outside of a bend and a bank
  that is steeper there, shallower and gentler on the inside; a bank that rises to 1.2 blocks above the water over `max(1.5, 0.18 * width)` blocks;
  and where the ground is gentle a flat floodplain beside it that the ground is eased toward (up to 3 blocks of build-up, 14 of cut), up to 3.4 half-widths
  wide for the widest rivers and almost none where the ground is steep.
- **Water as a level.** A column is water where the ground is under the river's level, or under a lake's surface. A lake is the simulation's lake
  cells blurred twice and read between cells, flooded where the ground is under the surface by more the nearer the edge of the lake cells, so the
  shore is a rounded curve that follows the ground (the first version, whole cells within a cell's distance, made polygon lakes).
- **Everything is zero beyond a river's reach** (at most about 180 blocks from a 70-wide river's line); a river is never perched beyond its banks
  (the ground beside a bank is under the water at 2% of 3,000 samples); the whole thing is a pure function of position.
- **Numbers** (seed 4242): the level rounded to a block steps once every 82 blocks on rivers under 5 wide, 112 at 5 to 10, 211 at 10 to 20, 522 at 20 to 40
  and 1,285 at 40 and over; the deepest cut along a river's line is 18 blocks; the steepest drop of the water between two nodes (16 blocks) is 5.7.
  `build/preview/earth_4242_c32_valleys_closeups.png` and `..._valleys_sections.png` (cross-sections before and after, and a long profile). Dials:
  `-Depicterrain.earth.riverDepth` and `-Depicterrain.earth.floodplain` (both multiply, 1 by default).
- **Limits.** Prototype only, nothing is in the game. Steep reaches still step in whole blocks (no waterfalls yet: that is a later stage, and the water
  there will look like a stair of static sources until then); the levels are continuous, rounding to blocks and the surface and bed blocks (gravel,
  sand, mud) are not done; no caves are kept out of the river's way (the Epic floating bed came from caves, which here belong to the density function);
  the relief detail is not yet faded near the finished river lines; no estuary, delta or oxbow shapes; lakes get no outlet shaping.
- **Tests** (`EarthRiverValleyTest`, the real seed 4242 window): the water never rises downstream or goes under the sea; a tributary ends exactly at the
  level of the river it joins and no drop between nodes passes 6 blocks; the bed is at least a block under the water and the channel is full of
  water; the ground beside the banks is under the water at under 3% of places; the ground is built up by at most 3 blocks and untouched 500 blocks from
  any river; the cut is within the floodplain's limit plus the bed; every sampled water column is under its level; lake interiors are water at the lake's
  surface; big rivers step rarely; building twice and asking in any order give the same ground.

### Rock layers (`Strata`, stage B)

The user's next stage after the rivers (2026-10-28, "Yes, next stage"; I read it as the stage I had queued: rock layers, so that mesas, benches, cuestas,
ridge-and-valley and granite domes can appear, which the first stage's uniform rock could not make). Dial `-Depicterrain.earth.rock`: 0 is the old
uniform rock exactly, 1 the default, 2 twice the contrast.

- **The column.** `Strata` is a stack of beds about 70 m (6 to 7 blocks) thick, their edges jittered by 30%, each hard (erodes at 0.15 of an average
  bed), firm (0.45), medium (1.0) or soft (2.5), with chances 28/22/25/25%, so an average bed is about 1 and the overall erosion rate does not change.
- **The surface in the column.** The simulation tracks `strat` for every cell: how high in the column the surface stands. Uplift carries rock and
  surface up together, so only erosion and deposition change it; erosion therefore walks a cell down through the beds, and a hard bed stands as a cliff,
  a bench or the cap of a mesa. The bed at the surface multiplies the stream-power erodibility (as an exponent: the dial) and a hard bed also resists
  creep (`sqrt(hardness)`, between 0.3 and 1 times), which is what lets a cliff keep its face.
- **Tilt, folds, domes.** `Landscape.addStrata`: the beds are tilted over the window (0.35 m per block, warped over 16 km so the dip changes from place
  to place, flattens or even reverses into a dome or basin, and the outcrops sweep in curves), folded in waves 4.2 km across near the belts of
  mountains (ridge-and-valley country), and gently warped; how layered the rock is varies over about 9 km (0.15, massive shield rock, to 1.25,
  thin sedimentary beds); granite domes (hard, 0.2) are scattered through it, more of them in and beside the belts.
- **What it does** (seed 4242, `EarthRockPreviewTest`): ground steeper than 10 degrees between cells goes from 3.9% to 5.3% of the land, over 20 degrees from
  0.1% to 1.2%, over 35 from 0% to 0.2%; the outcrop map looks like a geological map (curved bands, domes, folds), the plain gets long low cuesta ridges, the
  hills terraces and scarps; lakes grow by 16% (36,700 cells against 31,600) and the highest ground is 435 blocks (440). The surface is mostly hard
  rock (16% of the land at hardness < 0.4, only 2.5% soft in the small test world) because soft beds wear away and leave the hard ones standing.
- **Costs on what was built before.** The continent changes, so every earlier seed 4242 picture and number changed too (rivers: 632 rivers, 1.42 million
  blocks, widest 71 blocks). Four tests had to be updated with a reason each (see below). The hard ledges also make steeper river reaches and cliff
  tops: the steepest drop of the water between two nodes is 8 blocks (was 5.7), 7% of the places beside a bank are lower than the water (was 2%),
  the added detail steps up to 10 blocks between neighbouring columns on the steepest crags (was 5), and a river that runs along a cliff top is cut up to
  60 blocks down for a stretch (10 of 30,000 nodes). Two fixes came out of it: a tributary may join up to 14 cells (was 4) from where the cells had it, and
  a river that leaves a lake starts at the lake's level at full depth.
- **Not done.** Badlands as terrain (dense gullies in soft, dry rock) and as biomes (vanilla's `badlands` family needs our own mapping from the soft-rock
  outcrop and aridity: the preview does not do it), glaciation above about 3 km, volcano refinements, rock type for block palettes (sandstone, red sand,
  terracotta bands: the strata column is exactly what would give them), and waterfalls.
- **Tests.** `EarthStrataTest` (4: hardness values, the average bed is about 1, bed thickness, determinism) and `EarthRockTest` (5: rock 0 makes every
  cell 1.0, hard and soft rock outcrop and more with the dial, layers make the land steeper on two seeds, same seed same rock, the dial survives every `with`).
  Updated: the Detail guard is on what the detail adds and is now 10 blocks; the young-versus-fossil belt test is judged over all the worlds and per world
  within 15% (seed 4244 now has a fossil belt of 369 blocks against 368, hard beds preserve it); the river-valley tests allow falls up to 12 blocks
  between nodes, a big river to step once every 40 blocks, 1% of nodes cut deeper than the usual limit, and 10% of places perched beside a bank.

### Rock-layer streaks and their tuning (1.1.0)

The first survival playtest (2026-10-28/29) of 1.0.0 found that, on a map, the plains were combed in long parallel NE-SW bands (not very noticeable at ground level). They are the outcrops of the tilted
beds (`Landscape.addStrata`): spacing is the bed thickness (about 70 m) over the dip (0.35 m a block), so about 200 blocks, and the dip direction is the same over the whole window with only a 6 km
warp over 16 km waves to bend it. The bands are confined to where the rock is strongly layered (regions about 9 km across), and their relief is small: **a ripple 4.5 to 5 blocks high in every
setting tried**, so tuning them changes how the land looks from above and not how it feels to walk over.

Three dials (`tilt`, `bend`, `bendScale`, read when the landscape is built) and `EarthStreakPreviewTest` (preview-tagged: one seed, several settings, the ground dumped for drawing) were added, and
the seed of that world (-6214704096356883042) was built with each setting. "Parallel" is the mean coherence of the band-passed relief in 2 km windows over the central plains (1 is a comb):

| Setting | tilt, bend, waves | ripple (blocks) | parallel | look |
|---|---|---|---|---|
| A (1.0.0) | 0.35, 1, 16 km | 4.8 | 0.77 | straight diagonal bands |
| B | 0.20, 1, 16 km | 4.5 | 0.73 | the same, a little wider |
| C | 0.35, 2, 16 km | 4.8 | 0.78 | the same |
| D | 0.22, 2, 16 km | 4.5 | 0.74 | the same |
| E | 0.22, 3, 10 km | 4.8 | 0.59 | arcs and whorls |
| **F (the default since 1.1.0)** | 0.25, 2.5, 12 km | 4.6 | 0.66 | bands that curve in gentle S-shapes |
| G | 0.25, 2, 9 km | 5.0 | 0.54 | arcs and whorls |

Only a strong bend changes the pattern; a gentler tilt or a plain doubling of the warp looks almost like 1.0.0. Coasts, mountains and the tallest peaks (432 to 454 blocks above sea, against 447) are the same
in every setting. F was chosen as the smallest change that visibly breaks the comb. The 1.0.0 setting is `tilt=0.35 bend=1 bendScale=16000`.

### Islands (`Islands`, stage C)

Approved 2026-10-28: "build the island system as you described, with some being special, like atolls, volcanos, etc.", with a driving factor for
the mountains on them as on Earth, most islands in ordinary biomes, and the mushroom island rate close to vanilla's. Islands never stand on a
continent: they are in vast oceans (oceanic plates) or in seas on a plate. Dials: `-Depicterrain.earth.mushroom` (islands per 1,000 km2 of open
ocean, default 4.1), `volcanic` (multiplies hotspot and arc chances, 1) and `islandSize` (multiplies radii, 1).

- **What drives them.** *Hotspot chains* (Hawaii): 40% of oceanic plates have a plume; the plate slides over it, so the islands trail back along the
  plate's motion (the chain direction is the plate's velocity reversed, tested exactly), 4 to 8 of them 2.3 to 3.6 km apart, getting older along
  the line: a young volcano (crest up to 380 blocks, a crater, a snowy summit by the lapse rate), a second that is young or already old, old worn volcanoes
  (crest 25 to 170, no crater, radial valleys cut deep), and then atolls (a ring of reef, a lagoon at -6 to -9 blocks, sandy islets up to +7 where the ring is
  highest, a steep drop outside). *Island arcs* (the Aleutians): on the overriding plate of an ocean-ocean convergence, 800 +/- 900 blocks back from the
  boundary, a volcano per 2.4 km cell with chance 0.6, young (70%) or old. *Mushroom islands*: low and gentle (crest 6 to 24, radius 250 to 900, median
  475) with no volcano behind them. *Fragments*: in the seas of a simulated window, small hilly pieces of continental crust (crest 22 to 97, radius 250 to
  650), in water at least 1.2 km from land plus their own radius, on a shelf sea floor, 3 km apart, never on the window's rim.
- **Where.** Everything in the open ocean is a pure function of the seed and the position, from the plate skeleton alone (no simulation): a mushroom island
  needs oceanic crust with none of the continental kind within 6 km (a ring of 16 directions at 3 and 6 km), a volcanic island 2.5 km. Priority is chain,
  then arc, then mushroom; a lower-priority island that would come within its radius plus 1.5 km (3 km for a mushroom island) of a higher one is left out.
  Of a 300 km square 5.9% to 7.3% (5,300 to 6,600 km2) is open ocean in this sense.
- **Height.** An island is a height above sea level that rises out of whatever floor is under it (83 blocks down over ocean crust) and is exactly the floor
  beyond its footprint (a compact flank, `floor * (1 - (1 - u)^2.2)`), so the sea floor needs no change and there is no seam. Volcano cone
  `crest * (1 - t)^1.15`, a crater (at most 16% of the crest, less with age) with a rim, 3 octaves of radial valleys that deepen with age and die out
  toward the shore (a valley mouth is a bay), elongated 1.0 to 1.3 times along the chain or the arc, with a wobbly shoreline. The steepest step between
  neighbouring blocks is 1.3 blocks (it was 22 before the valleys were made to die out at the shore).
- **Biomes.** Ordinary islands get what vanilla's table gives for their height, relief above the sea and climate, through `BiomeClimate` (the preview used
  rain 1,200 mm + 0.35 per metre and the real coast distance on the grid): on seed 4242 a 286-block volcano has a snowy summit and grove, meadow, forest and
  taiga flanks, an old volcano meadow, forest and birch forest with a sandy fringe, a fragment in the cold sea taiga and snowy plains. Mushroom islands set
  continentalness to -1.1 over the island and its shallows (within 1.15 radii and above -12 blocks), which vanilla's table answers with `mushroom_fields`
  for every temperature and humidity (tested); the ring around keeps the ocean value -0.7, which is deep ocean (warm ocean in the hottest climates, as vanilla
  has no deep warm ocean).
- **Mushroom rate against vanilla.** Vanilla (measured on two seeds, 80 km squares): about 1 island of a fifth of a square kilometre or more per 260 km2 of its
  ocean and 1 per 900 km2 of the whole world. Ours: 1 per 268 km2 of open ocean over three seeds (244 to 310 per seed), so the rate an ocean-goer meets them at
  matches, but only 1 per about 4,000 km2 of the world, because open ocean is 6-7% of the world here and ocean is 28% of vanilla's. Raising the dial (to about
  16) would match the world rate and crowd the ocean four times as much; sea islands add roughly one per window.
- **Counts** in a 300 km square (seeds 4242, 11, 7): mushroom 27, 17, 24; young volcanoes 23, 33, 18; old volcanoes 22, 19, 7; atolls 7, 9, 1.
- **Finishing (same day).** (1) *Beaches.* Vanilla's table only gives a beach where weirdness is small and not a valley's (about 0.05 to 0.27, either
  sign) and erosion is 0 or more, and the lowland's usual weirdness of 0.4 is outside that, so coasts of lowland got forest or plains where vanilla's noise
  was positive and a beach only by luck. `BiomeClimate` now asks for 0.2 in the coast band (96 blocks) of the lowland: this also puts a beach on every
  lowland coast of the continents (it was patchy, which was the 'beaches thin' note), and makes atolls sand. Hot climates (temperature over 0.55) still
  answer desert, as vanilla does. (2) *Island climate in one place*: `Islands.climate` (relief is the island's own height, rain 1,200 mm and more with height, the coast
  bands from the distance to the water) and `Islands.mushroomClimate` (continentalness -1.1) replace what the preview did by hand. (3) *Crater lakes*: half of the young
  volcanoes (8 of 38 over three seeds were found to have one, the rest too shallow) have a lake at half the crater's depth, never above the lowest point of
  the rim less a block and a half, only inside the rim (`craterLake`, `craterWater`; tested). (4) *Walking-scale roughness* on volcanoes: the same swells (170 blocks)
  and crags (15 blocks) as `Detail`, scaled by how high the island is, dying out at the shore; the steepest step between neighbouring blocks is 1.6.
- **Limits.** Prototype only: not in the game, and islands are not yet part of the continent or `BiomeClimate.compute` (the preview colours them through
  `Islands.climate`); a volcano is a smooth cone with radial valleys and crags, not a simulated landscape (an island could be run through the erosion model if it needs
  more); no streams or rivers on islands, no coral reefs or kelp; a lookup walks about 100 cache entries, so a game would index it by chunk; sea islands exist only in a
  simulated window, and with 15% continental water that is about one per window.
- **Tests** (`EarthIslandsTest`, 13; the two new ones are the crater lake and the beach and cold summit): same seed same islands in any order; islands are on oceanic crust with the clear margin and their land is over oceanic
  crust; a mushroom island has 3 km around it; an island is the floor beyond its footprint, comes down to the floor at its edge and has no cliff; each
  kind's height and shape (a mushroom island is low and gentle, a young volcano tall, an old one worn, an atoll has a lagoon and islets); chains trail back
  along the plate's motion and age along their length; arc volcanoes are on the overriding plate beside an island arc; the mushroom rate is within 200 to
  340 km2 per island; the dials; the label; vanilla's table answers mushroom fields and deep ocean; fragments keep their distance from land and the rim.

### Water against land on the skeleton (`EarthWaterMapTest`)

The island map shows crust type, not sea level, so the skeleton's 200 km square (centred on 0, 0, eight plates across) was simulated whole, with 128-block
cells (`-Depicterrain.earth.window=8`, `cell=128`, `cx=0`, `cz=0`; 87 s, `build/preview/earth_4242_water_map.png`). Seed 4242, leaving out the outer 14 km, which is
the simulation's own rim held down to the sea: land 69.9%, sea 28.7%, lakes 1.4% of the square. Continental plates are 83.8% of it and are 83.4% land,
15.0% sea (exactly the continental-water dial: sea level is the 15th percentile of their height), 1.6% lakes; oceanic plates are 16.2% of it and all water.
Of the 3,715 km2 of sea on continental plates, 2,145 km2 is open to an oceanic plate (mostly the shelf around it) and 1,570 km2 is in enclosed seas (29 of 5 km2
or more); the biggest bodies are 270 to 330 km2. So most of the water is the ocean plates and their shelves, with straits and gulfs at the rift and transform
boundaries; where continental plates meet at a collision or with no rift, the land is continuous. Coarser cells than the default 32 blocks, so the rivers and
lakes are rough, and no islands are drawn (they are not in the continent yet).

### Finding the highlights with vanilla's own `/locate biome`

The user asked (2026-10-28) for a few biome tags for the highlights, reusing the native biome search. The Earth biome mapping answers with vanilla biomes, so
the tags are made of vanilla biomes and tags (`src/main/resources/data/epicterrain/tags/worldgen/biome/earth_*.json`), and `/locate biome #epicterrain:<name>` is
vanilla's search, nothing of ours:

| Tag | Holds | What it finds |
|---|---|---|
| `earth_mushroom_island` | `mushroom_fields` | a mushroom island |
| `earth_peaks` | `jagged_peaks`, `frozen_peaks`, `stony_peaks` | the tall summits |
| `earth_mountains` | `#earth_peaks`, `#earth_volcano`, `snowy_slopes`, `grove` | mountains proper |
| `earth_highlands` | `#minecraft:is_mountain` | the whole mountain band, meadows, cherry groves and volcanoes included |
| `earth_volcano` | `epicterrain:volcano` | the summit of a young volcano |
| `earth_atoll` | `epicterrain:atoll` | an atoll's reef and islets |
| `earth_crater_lake` | `epicterrain:crater_lake` | a crater lake |
| `earth_islands` | `mushroom_fields`, `#earth_volcano`, `#earth_atoll`, `#earth_crater_lake` | any of the island features |
| `earth_open_ocean` | `#minecraft:is_deep_ocean` | the open ocean, where the islands are |
| `earth_badlands` | `#minecraft:is_badlands` | the layered-rock country, where the biome mapping reaches the hot badlands |

`EarthTagsTest` (2 tests) loads them through the game's registry loader: every entry is a real biome or tag, each tag resolves to at least one biome, and the
members are the right ones (a meadow is a highland but not a mountain, a shelf sea is not the open ocean, `earth_mushroom_island` is exactly mushroom fields).
Vanilla's search looks within 6.4 km of the player: mushroom islands come about once per 268 km2 of open ocean, so from the middle of a continent the search
will usually find none; from a boat in the open ocean it will.

**Volcano, atoll and crater lake biomes** (added the same day at the user's request). Volcanoes, atolls and crater lakes are terrain, so they have three small biomes
of their own, written by `tools/gen_worldgen.py` (as the river biomes of the Epic type were): each is a copy of a vanilla biome (`volcano` of stony peaks, `atoll` of beach,
`crater_lake` of river) and is added to every vanilla tag that biome is in, and to the surface rules that name it, so it gets the same mobs, surface and
structures (an atoll is a beach for turtles and beached shipwrecks, a volcano a mountain biome, a crater lake a river). `Islands.highlight` says where they apply:
a crater lake's water; the summit zone of a young volcano (inside 45% of its radius and 30 blocks or more above the sea, hundreds of blocks across); an atoll's reef
flat and islets (a little wider than the reef, no deeper than 3 blocks). A volcano summit is therefore bare stony peak and not the snowy peak the lapse rate would
give. `EarthIslandsTest` checks it on 45 volcano summits (8 with a crater lake) and 7 atolls, and `EarthTagsTest` the tags and the copies of vanilla's tags.
GOTCHA: the generator clears `data/epicterrain` and `data/minecraft` before it writes, so the `earth_*` tags are written by it too (a first version of them was
hand-written files, which the next run would have deleted). It also adds to each earth tag the copies of the vanilla biomes it lists (stony
peaks' copy `volcano`, beach's copy `atoll`, river's copy `crater_lake`), because a copy must be in every tag its original is in; `EarthTagsTest` enforces that (the same rule, tested
in the days of the Epic biomes, failed on the first version).

### The Earth world type in the game

Built 2026-10-28 on the user's go-ahead ("Approve, build it"). It was built as a second world type next to Epic Terrain (removed in 1.1.0): **Create New World, World Type,
"Earth"** (the button said "Earth (prototype)" until the rename; the preset `epicterrain:earth`; a dedicated server would use `level-type=epicterrain:earth`, which has not been tried). This section was written before the first play;
it was then played in the development client and, as version 0.2.0 (without caves), in the real launcher with Distant Horizons, where the author reported it "ran good on 32 chunks, + 128 from
distant horizons". What it says "tested" was tested headlessly through the game's own loaders and generator (`EarthGameTest`, 20 tests).

**What it is made of.**

| Piece | Class / file | What it does |
|---|---|---|
| `EarthWorld`, `EarthWorlds` | `com.epicterrain.earth` | the simulated window with detail, rivers, valleys, islands and climate behind one call, `sample(x, z)` (ground, water level, whose water). The window is placed so that its middle is the world's origin. One per world seed, kept until the server stops |
| `EarthField` | `worldgen`, density function type `epicterrain:earth_field` | three outputs of that call as density functions: `earth/density` (`63 + ground - y`), `earth/surface` (the ground's y) and `earth/land` (+1 on dry land, for the spawn target). They go in the noise router as `final_density`, `chunk_surface_level` and `continents`; nothing else of the terrain is made of noise |
| `EarthChunkGenerator`, `EarthFluidPicker` | `worldgen`, `epicterrain:earth_noise` | vanilla's noise generator with one change: where the water is. Vanilla fills everything open below y 63 at one sea level; the picker asks the model, column by column, so a river or a lake or a crater lake stands at its own level above the sea. Vanilla's aquifers are kept, but only below the ground (`NoiseBasedAquiferMixin`, see "The underground"). It also bounds the structure search (`SearchBudget`) |
| `EarthBiomeSource` | `worldgen`, `epicterrain:earth` | the biome mapping of `BiomeClimate` and `Islands` (vanilla's table, asked about the model's numbers) plus the volcano, atoll and crater lake biomes by name, and below the surface vanilla's cave biomes, asked with a depth (see "The underground") |
| Datapack | `tools/gen_worldgen.py` | `earth/*` density functions, noise settings `epicterrain:earth` (vanilla's, with that router, 576 blocks of height, its own surface rules, vanilla's aquifers, spawn target = `earth/land` in 0.5 to 1) and world preset `epicterrain:earth`. The biomes are vanilla's own, carvers and all |

**What you will see.**

* **The first load takes a minute or more with nothing on the screen to say so.** The window is simulated when the world's terrain function is first compiled, on
  the server thread: `Simulating the Earth window for seed ...` in the log, then `The Earth window is ready: ...` with the timings. A world you leave and re-enter is
  simulated again (nothing is cached on disk yet). The default dials are the prototype's: 32-block cells, 1.5 plates, 140 steps.
* **The world is a 37.5 km square around the origin** (so the spawn lands on the continent: the spawn target looks for dry land more than 6 blocks above the water). **Beyond it
  is deep ocean**, with the island catalogue standing in it (volcano chains, arcs, atolls, mushroom islands), and the sea floor blends from the window's rim to the open ocean
  floor over 3 km. The terrain does not tile or continue yet: out past the window there is only sea and islands.
* **Different dials** (the same options as the previews, forwarded to the game by `build.gradle`): `./gradlew runClient -Depicterrain.earth.cell=64 -Depicterrain.earth.steps=80`
  makes a rougher window in much less time (the work grows with the square of the number of cells across). `window` (plates across), `plate` (km), `water`, `rock`, `steepness` and the rest of `EarthConfig` work the same way.
  A dedicated server takes them as ordinary `-D` options.
* **Seed.** The density-function compiler does not hand a function the world seed, only random sources derived from it, so the model's seed is a number derived from the
  world seed: the same world seed gives the same world every time, but it is not the world seed itself (world seed 4242 is not the preview tools' seed 4242). The log line
  `Simulating the Earth window for seed N` says which one it is; pass that N as `-Depicterrain.seed=N` to the preview tools to draw the world you are standing in.
* **Finding things.** `/locate biome #epicterrain:earth_mushroom_island` (and the other `earth_*` tags above) are vanilla's own search; the three island biomes are `epicterrain:volcano`, `atoll` and `crater_lake`.

**Tested headlessly** (`EarthGameTest`, a small coarse window so it takes seconds: 96-block cells, 1.2 plates, 50 steps; everything else as in the game): the preset loads through
the registry loader with the generator and biome source; the compiled climate sampler and the generator find the same world; the aquifers and the right height; the surface
biomes carve like vanilla's; the decoration order of every possible biome sorts (the game refuses to start otherwise); `getBaseHeight` equals the model's ground for 400 random columns (median error 0, 90th 1; caves open the surface in about 8%); the sea beyond the window is 63 deep water over stone; 80 columns of river and lake water stand at the modelled level, none wrong; no ocean biome on dry
land, only biomes the source says it can answer with; every island feature of the catalogue within 60 km (147 columns on land) has the right biome; the spawn target is a perfect fit on
dry land and a poor one in deep sea; and **three real chunks** (a river, the origin, the open sea) are built through the game's own `createBiomes` and `buildTerrain`
(fill, fluid picker and surface rules): the highest solid block and the water level of at least 75% of the 768 columns are the model's (the rest are cave openings), with grass and dirt on land and gravel on the sea floor;
24,000 columns across 140 km asked by many threads at once of a cold world (the island catalogue fills its caches as it goes) get the same answers as one thread asking a warm one;
and building 64 chunks (biomes, fill, surface; no features) on one thread took 30.6 ms each on the test window, warm-up included (a rough guide only: the full-size window has
more rivers and islands to look up, and the game builds chunks on several threads).

**Not done, not tested, or likely to look wrong** (this is a first look, not a finished product):

* **Played, but not much.** The development client and the real launcher (0.2.0, no caves) have run it; a dedicated server has not been started, and the 1.0.0 underground has had little play. The headless chunk test uses an empty structure manager and no world-gen region, so structures,
  features (trees, ores, flowers) and the lighting of a real chunk were not covered by tests; they are vanilla code on vanilla biomes.
* **Structures** (villages, temples, ...) are placed by the vanilla structure sets and the vanilla biome tags. Their terrain adaptation (the beardifier) is back in the density, so villages and the like get their foundations; the test chunks have no structures, so none of that has run.
* **Riverbeds** are whatever the surface rules give (dirt under the water of a river); gravel and clay beds, waterfalls, and the Detail layer fading near the finished river lines are not done.
* **Caves, canyons, aquifers and cave biomes are back** (next section); the classic carvers (cave, canyon) need a world-generation region and are not covered by the chunk test.
* **No disk cache** of the simulation, no customize screen for the Earth type (the dials are `-D` options), the window does not tile, glaciation and badlands mapping are open items.
* **Memory.** The window at the default dials is 1,170 x 1,170 cells; the tests simulate it (seed 4242) inside a 2 GB heap, so the game's default should do, but that is not
  checked in the game, and a lower `cell` makes the window much bigger. If the world fails to load with an out-of-memory error, use a coarser `cell`.

### The underground (vanilla's caves on the Earth ground)

The first play (2026-10-28) ran with caves off. On the user's "time to add back the rest of vanilla world gen like caves and such" the Earth world got vanilla's underground back, reusing
vanilla's own pieces and not inventing a cave system:

* **Noise caves, entrances, pillars, noodles and the terrain adaptation of structures** (`earth/final_density`, built by `tools/gen_worldgen.py` from vanilla's `final_density`).
  Vanilla's density is "terrain with caves cut in", and it lets the caves in only where the terrain's own density is deep enough (its sloped cheese, 1.5625 and more, about 12 blocks down). So
  the Earth ground stands in for the sloped cheese at vanilla's slope (`earth/sloped_cheese` = 0.125 a block; the beardifier and the cave gating were made for that), the top slide that fades the
  caves out is moved from y 240-256 to 496-512 (the Earth world is taller), and the result is `min(exact ground, interpolated caves)`: vanilla interpolates its whole density over 4 x 8 blocks, which
  would smear a river three blocks wide, so the ground itself is kept out of the interpolated part. The beardifier is added last, as vanilla does.
* **Classic carvers** (cave, canyon, underwater cave): the biomes are vanilla's again, with their `carvers` lists. (The first build had carver-less copies of every biome; they are gone, and the
  `variants` field of the biome source is optional now.)
* **Aquifers** (water and lava pockets, flooded caves under the sea, the lava of the bottom): `epicterrain:earth` keeps vanilla's `aquifers` with the Earth ground as `surface_level`. **They had to
  be kept from the ground and above**: vanilla's aquifer takes the water above the ground to be the sea at one level and, beside a river standing twenty blocks above the sea, replaced its water with
  levels of its own and barrier blocks (74 of 80 test columns of river water were wrong). `NoiseBasedAquiferMixin` (a new common mixin, `epicterrain.mixins.json`) answers for any open block at or
  above the ground of its column from the fluid picker alone and leaves everything below to vanilla. The mixin does nothing in a world whose picker is not ours.
* **Cave biomes.** Vanilla's table puts lush, dripstone and sulfur caves at a depth of 0.2 to 0.9 and the deep dark at 1.1 and more; depth is 0 at the surface and grows by 3 for every 384 blocks
  down (measured against vanilla's own `overworld/depth`: 0.01 at the surface of a plain, 0.21 at 26 blocks down, 0.91 at 115). `EarthBiomeSource` asks the table with the depth below the Earth
  ground, per tenth of depth (and the deep dark at 1.2), and takes a cave biome where the table gives one and the biome of the surface otherwise. Dripstone wants far inland, so below the surface the
  continentalness is made regional (`BiomeClimate.undergroundContinentalness`: low at a coast, 0.75 three kilometres in, slow swings of 0.5). The depth is held at 1.0 above y -10 so that the deep
  dark stays at the bottom of the world and not inside a tall mountain. `/locate biome minecraft:lush_caves` works (heights are asked per column).
* **Measured** (small test window): 24 land chunks, 8.5% of the volume more than 10 blocks under the ground is empty, 765 lava blocks, 593 water blocks, deepslate 368,000, bedrock, 698 ore-vein blocks
  (granite, tuff, copper, iron). Cave biomes at 20 / 30 / 45 / 60 / 80 / 110 blocks below the surface: surface biome throughout at 20, then dripstone 52% / 67% / 78% / 67% / 38% and lush 1% / 3% /
  4% / 3% / 1% of land columns. **Dripstone is the bulk** (vanilla's table picks it for most inland climates at those depths, and the Earth's inland continentalness is high); lush needs a humid
  climate and is rare; the deep dark needs erosion of -0.2 or less (mountains) and appears only below them. A dial for the mix is easy to add if it is wrong in play.
* **Cave openings.** About 8% of dry columns (33 of 397 cell corners) have their top solid block lower than the model's ground because an entrance or a noodle cave opens the surface. That is
  vanilla's behaviour on any ground; it is not tuned. Seen with the 1.1.0 test chunks (`EarthGameTest.realChunksAreBuiltWithTheModelsGroundWaterAndSurface`): no column is ever higher than the
  model's ground, 0 to 11 of 256 columns are opened by more than 3 blocks, and 55 / 102 / 28 of 256 (21%, 40%, 11%) are thinned by 1 to 3 blocks where a cave lies just under the ground, because the
  density is interpolated over 4 x 8 blocks and a cave's top bleeds into the cell above it. So over caves the surface can sit a few blocks lower than the model draws it.
* **Cost.** A chunk (biomes, fill, surface, no features) took 28.8 ms on one thread in the test, against 20.5 ms before the caves. A futile biome search of 6.4 km took 3.5 s on the test window.
* **A bug the single-class runs hid** (found by the whole suite, fixed): `EarthChunkGenerator` first read the noise settings in its constructor. Registries load in parallel and the world preset
  (which holds the generator) can be decoded before the noise settings it names are bound, so on some runs the preset failed to parse and every test that loads the registries failed (39 of 150
  in that run). In the game it would have been an intermittent failure of the world list. The picker now reads the settings at first use, and
  `EarthGameTest.theGeneratorCanBeBuiltBeforeItsNoiseSettingsAreLoaded` builds a generator over an unbound holder.
* **Not covered by tests**: the classic carvers (they need a world-generation region), structures on Earth terrain, the cave decoration features, mob spawns.

**Searches (`/locate`) and their limit** (added after the first play, 2026-10-28). The first session showed `/locate biome` freezing the server for 11 to 20 s per search
and an ocean-monument `/locate structure` still running after four minutes, when the user quit. Causes, from the log and from timing a biome lookup on the full-size window
(7 us over open ocean, 18 to 23 us over the continent):

* **`/locate biome`** asks vanilla's biome source about every height of every column of a spiral out to 6.4 km: 160,000 columns times 9 heights of this 576-block world.
  `EarthBiomeSource.findClosestBiome3d` is the same spiral over the columns only (the biome does not change with height), so the work is a ninth, and it stops at the limit
  below. A futile search of the whole radius from the origin (an atoll, from the middle of the continent) now takes 2.9 s on the full-size window (the 11 to 20 s before).
* **`/locate structure`** (and explorer maps, eyes of ender) looks at up to 100 placement cells in every direction and generates the structure starts of every candidate
  chunk on the way; an ocean monument's start checks the biomes in a 29-block cube, 3,375 lookups, which is 25 to 75 ms a candidate and tens of thousands of candidates. The
  resolver now answers that check from the 225 columns (`getBiomesWithin`; 1.2 to 2.9 ms a candidate), and `EarthChunkGenerator.findNearestMapStructure` runs vanilla's search
  in growing radii (`SearchBudget.growing`: 2 cells, then up to double while the cost expected of the next step, from the cost per new cell of the last, fits what is left of the
  limit). It returns what vanilla would (the first ring that has one) or null, which vanilla reports as "could not find ... within a reasonable distance".
* **The limit is 5 s** (`-Depicterrain.earth.locateSeconds=N`). Past it the search says it found nothing, so something rare enough can be reported missing when it is there; raise
  the limit to look further. The monument search was NOT reproduced here (it needs a running level): the driver's logic is tested on a fake clock (`SearchBudgetTest`, 6 tests),
  the biome search and the 29-block check on the real source (`EarthGameTest`), and the cause above is the log plus arithmetic, not a measured monument search.
* **A smaller change on the way:** `Islands.near` remembers its answer for a point per thread (one biome lookup asked it four or five times), which also took a chunk from
  30.6 to 20.5 ms in the test (`EarthIslandsTest.rememberingTheIslandsOfAPointDoesNotChangeTheAnswer`, including many threads at once).

### Trying it in the real launcher, with shaders and Distant Horizons

`./gradlew assemble` (no tests) writes `build/libs/epicterrain-1.2.0.jar` (about 160 KB; the `-sources.jar` next to it is not needed to play). It holds the classes, the
mixin (`epicterrain.mixins.json`), the generated datapack (`data/`), the language file, the
icon and the access widener (namespace `official`, as the unobfuscated 26.3 needs). Versions: 0.2.0 was the first one tried in the launcher (the Earth world type without caves, with Distant
Horizons, which ran well at 32 chunks plus 128 of Distant Horizons); 0.3.0 added vanilla's underground (see "The underground") and the search limits; 1.0.0 is the same code under a release number, with the license and the mod's contact details in its metadata. The release jar was built from the tree of the last full test run (169 tests, 0 failures, 0 errors, 0 skipped). Version 0.2.0 has been loaded by the real launcher; 1.0.0 as such has not yet.

**What goes in `%APPDATA%\.minecraft\mods`** (versions as Modrinth lists them for Minecraft 26.3, checked 2026-10-28; Iris 1.11.7 asks for exactly Sodium 0.9.2, so not the 0.9.3 beta):

| File | Where from |
|---|---|
| `epicterrain-1.2.0.jar` | `build/libs` here |
| `fabric-api-0.165.0+26.3.jar` (what the mod is built against; newer 26.3 builds should do) | [Fabric API](https://modrinth.com/mod/fabric-api) |
| `sodium-fabric-0.9.2+mc26.3.jar` | [Sodium](https://modrinth.com/mod/sodium) |
| `iris-fabric-1.11.7+mc26.3.jar` | [Iris](https://modrinth.com/mod/iris) |
| `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` (28 MB) | [Distant Horizons](https://modrinth.com/mod/distanthorizons) |

The launcher needs a Fabric profile for 26.3 first (Fabric Loader 0.20.1 or newer, from the [Fabric installer](https://fabricmc.net/use/installer/)); shader packs go in `shaderpacks`. Give the
profile more memory (in the launcher's JVM arguments, `-Xmx6G` or more): Distant Horizons keeps a lot, and the Earth window is simulated in the same process. Options for the Earth world
(`-Depicterrain.earth.locateSeconds=15`, `-Depicterrain.earth.cell=64`, ...) go in the same field. Worlds of the development client live in this project's `run/saves`, not in `.minecraft/saves`.

**What is and is not known about the combination.**
* Known: all four mods have 26.3 releases (Distant Horizons 3.3.4 of 2026-09-29, Iris 1.11.7 of 2026-09-30, Sodium 0.9.2 of 2026-09-15). That is all that was checked.
* Not tried: this mod together with any of them. Distant Horizons builds its distant terrain by asking the world's chunk generator for chunks on its own threads, so every far chunk goes through
  `EarthChunkGenerator` and the Earth model; the model is safe to ask from many threads at once (`EarthGameTest`, 24,000 columns on a cold world) and a chunk took about 20 ms on one thread
  in the test (biomes, fill and surface, no features), but how many chunks per second Distant Horizons gets from it in practice is for the first run to show.
* From general knowledge, not checked for 26.3: shaders and Distant Horizons together work best with shader packs that support it; with others the distant terrain may render without the
  shader or not at all, which is the pack's doing and not this mod's.
* A faster route for later, if the generated chunks are too slow for Distant Horizons: its API lets a mod supply the distant terrain itself, and the Earth model can answer a column in a few
  microseconds, so the far terrain would not need real chunks. Not started.

### Does everything vanilla has in the overworld happen here? (2026-10-29)

Asked by the user before they stopped development ("make sure all vanilla features and structures are present"; they cannot search for them in game at this scale). Two checks, neither of which needs the game.

**1. A headless audit, `VanillaCoverageProbeTest`** (preview-tagged; `./gradlew preview --no-configuration-cache --tests '*VanillaCoverageProbeTest' -Depicterrain.coverage=SEED[,SEED...]`, about 80 s a seed, writes `build/preview/vanilla_coverage_SEED.txt`).
It simulates the full 37.5 km world and then
* counts every biome from the biome source: the surface over the window (every 64 blocks), the underground at eight depths (every 128 blocks) and 100 km round the window; it lists the overworld biomes that never occur, and the nearest occurrence of each to the spawn so it can be visited;
* checks every placed feature of every vanilla overworld biome against the biomes that occur;
* generates structure starts for real: for each structure set the candidate chunks nearest the spawn go through the generator's own `createStructures` with the game's template manager, until each structure whose biomes occur has been seen once or the search reaches 150 km. That proves the biome, the ground, the template pool and the bounding box; it does not place the pieces (that needs a live level, which this harness does not have), and features are not run for the same reason.
(`BiomeTableDumpProbeTest` prints where vanilla's biome table chooses a given biome. `WorldgenHarness` now also binds the built-in registries' tags and offers `allRegistries`, which structure generation needs.)

**Result, seeds -9086509278680195954 (the world the user played), 4242 and 11, all alike.**
* **Structures: every one whose biomes occur generates**, nearest to the spawn at 0.1 to 16 km (42 of the 47 overworld structures): the five villages, pillager outposts, mansions, both mineshafts, strongholds (128 ring positions, the first about 1.5 km out), ancient cities, trial chambers, trail ruins, igloos, desert and jungle pyramids, shipwrecks (both), ocean ruins (both), buried treasure, ruined portals (desert, jungle, mountain, ocean and plain) and 16 of the 18 abandoned camps. No candidate chunk threw an exception.
* **Not generated, because their biomes never occur:** the ocean monument (needs a deep ocean biome), the swamp hut, the swamp ruined portal and the swamp and windswept-forest abandoned camps.
* **Biomes that never occur (12 of vanilla's 56 overworld biomes, 11 on seed 11):** `swamp`, `mangrove_swamp`, `windswept_hills`, `windswept_gravelly_hills`, `windswept_forest`, `windswept_savanna`, `stony_shore`, `deep_ocean`, `deep_cold_ocean`, `deep_lukewarm_ocean`, `deep_frozen_ocean`, `stony_peaks` (seen on seed 11 only), and, of the mod's own three, the crater lake (rare, and the sampling is coarse). Of the 171 placed features of the overworld's biomes, 153 are carried by a biome that occurs; the 18 that are not (swamp and mangrove trees, lily pads, swamp sugar cane and mushrooms, firefly bushes, windswept trees, the deep oceans' seagrass) all belong to the missing biomes.
* **Why**, from vanilla's table (`BiomeTableDumpProbeTest`) and `BiomeClimate`: swamps want erosion of 0.55 or more and the model's flattest land is 0.33; the windswept biomes live in the one erosion band of 0.45 to 0.55, which the model never sets; the stony shore wants a coast (continentalness -0.19 to -0.11) with mountainous erosion, but any ground that is not lowland is given a far-inland continentalness; stony peaks want a summit temperature of 0.2 to 0.55, and the lapse rate (0.3 a km) makes every summit colder than that; the deep oceans want a continentalness below -0.455, which the model gives to a sea floor 1,500 m down, that is 141 blocks, and the deepest sea floor of the world is 100 blocks (the open ocean is 60 to 100), so the test is never met.

**2. A scan of the world the user played** (`New World`, 113,221 chunks, 79,189 of them fully generated by the game, 34 biomes, 479 kinds of block; the scan counted what each chunk's palettes and structure starts hold). All the ores (every vanilla one for the overworld, plain and deepslate), amethyst geodes, dripstone, sculk and its shriekers and catalysts, glow lichen, cave vines, moss and azaleas, clay, gravel, ice, packed ice, blue ice, powder snow, cacti, sugar cane, bamboo, melons, pumpkins, sweet berries, giant mushrooms, podzol, mud, bee nests, trial spawners and vaults, mineshaft cobwebs and spawners, and the structure starts of villages (five kinds), igloos, outposts, a mansion, strongholds, ancient cities (32), trial chambers (88), desert pyramids, ruined portals, shipwrecks, an ocean ruin, buried treasure and trail ruins are in the saved chunks. Missing there only: what belongs to biomes that world did not reach (red sand, mycelium, lily pads, coral, jungle pyramids), and the swamp, windswept and deep-ocean things above.

### Closing the gaps the audit found: marsh and the rarer rows of the table (2026-10-29)

The user's answer to the findings above ("I want the biome mechanics themselves to resemble vanilla in terms of how it feels in practice. I should be able to find flooded swamplands and marshes. If that means increasing
the biome noise and variance to allow some stretching as to what conditions need to be met, I'm okay with some whimsy"; and of the oceans: "felt deep enough, at least 50-100 blocks deep ... maybe the mechanic is defined
differently"). So the fixes change how the model feeds vanilla's table, and the ground where vanilla's own would be different, and are not labels laid over the old ground.

* **Swamps are marsh, in the ground and in the biome (`Wetlands`).** Calibrated against vanilla's own swamps, measured on a vanilla seed (`BiomeGapStatsProbeTest`): the top solid block is, against y 62, 3 under at the 10th percentile,
  level in the middle and 6 over at the 90th, and a third of the columns (33 to 36%) have water on top. The marsh weight is 0 above 11 blocks over the sea (full under 3, with a wobble of a few blocks on the edge), 0 within
  40 to 200 blocks of the shore (that is a beach), and otherwise a score of a wide wetness noise (4,200 blocks), 0.30 times the rain (500 to 1,200 mm) and 0.25 times nearness to the sea; the continent is dry (median 500 to 630 mm a
  year), so the noise carries most of it ("whimsy": a dry continent still has marshes, a wet one more). The ground blends from the simulated ground to the marsh surface (`-0.6 + 11 * pools(36 blocks) + 1.2 * hummocks(13)`)
  over a weight of 0.05 to 0.6; ground under the sea level floods through the generator's existing sea-level fill, and rivers and lakes (columns that have water of their own) are left as they are, so rivers cross the marsh.
  The biome is land of erosion 0.72 and weirdness 0.30 wherever the blend is half or more, which vanilla's table answers swamp, or mangrove swamp above a temperature of 0.2 (swamp rows exist only for some weirdness bands, which is
  why 0.30). Result on three seeds: swamp 0.7 to 2.7% and mangrove swamp 0.3 to 1.3% of the land (vanilla 1.4% and 0.8%), ground 10% -4, 50% 0 to 1, 90% +5, flooded 36 to 39%.
* **The rarer rows of the table are reached by patches, scaled by the variety dial** (`BiomeClimate.cell`; each is a noise against a threshold, so with variety 0 the bands stay clean): *windswept* is erosion 0.50 (vanilla's shattered band,
  the only one it lives in) on about 5% of the foothills and montane country and of the near-coast lowland (the latter is where the table has windswept savanna); *stony shore* is a coast of erosion -0.30 on a third of the lowland coast, and
  any coast of high ground (which used to be forced far inland); *stony peaks* are summits of the peaks band at a temperature of 0.32 (vanilla's stony peaks are warm; the lapse rate never leaves a summit that) on about a third of them.
  Shares on three seeds, against vanilla's measured ones: windswept 0.7 to 3.2% of land (vanilla 1.3%), stony shore 1.0 to 1.4% (2.1%), stony peaks 0.1% (0.17%).
* **The sea.** `EarthBiomeSource` handed `BiomeClimate.cell` the ground in blocks times 10.6 m, and the sea floor is stored eased to 85 blocks (`Continent.blocksAbove`), so the deepest floor read as 900 m and never reached the 1,500 m
  of deep ocean; the preview path passed real metres and so worked, which is why nothing showed it. `Continent.metresOf` undoes the easing, and the deep ocean now begins at 1,200 m (62 blocks down; vanilla's are 18 to 29 blocks
  deep, its oceans 9 to 18, so any of this is deep by its measure, and the continent's sea is 60 to 100 blocks down in the open). 33 to 45% of the sea in the window is deep ocean (vanilla's: 29%), and all of the open ocean beyond it.
* **Checked** by the audit on three seeds: every overworld biome occurs (the mod's own crater lake is rare and was missing on one seed), all 171 placed features of the overworld's biomes are carried by a biome that occurs, and all 47
  overworld structures generate (the ocean monument about 15 to 17 km out, in the deep ocean past the continent; swamp huts 4 to 10 km; the nearest swamp 4 to 9 km). By `WetlandsTest`, `EarthBiomeClimateTest`
  (`theRowsOfTheTableTheBandsOnceMissedCanBeAsked`, `theSeaIsDeepOceanFromTheDepthOfARealDeepSea`) and `EarthGameTest.marshesAreSwampsWhoseGroundIsHalfFloodedLikeVanillas` (a kilometre of marsh: all swamp, a third to a half
  water, and real chunks flood where the model says). `BiomeGapPreviewTest` draws the biomes of a whole world and a close-up of its best marsh (`-Depicterrain.gapPreview=SEED`).
* **Not done:** the windswept savanna is rare (0.01 to 0.13% of land; vanilla 0.35%); the offline preview maps (`BiomeClimate.compute`) do not draw marsh; a marsh's edge follows a ground contour with a wobble; mangrove swamps have the
  swamp's pools, with no tidal flats of their own; the new ground changes existing worlds by a few blocks in marsh country (seams).

### Hills in the lowland, and biomes that follow them (2026-10-30)

The user's words: "I want to better match the geographic area to the biomes. I'll go through many different biomes while it remains flat for many miles ... different biomes should have slightly more variation
in geographic features", and, to the plan below, "Prototype it" (flat in the dry and swampy, rolling in the middle, hilly in places; hot hilly regions badlands and mesas; biomes following the simulated rain; a `hills`
dial; about two thirds of vanilla's roughness).

* **Measured first** (`TerrainVarietyProbeTest`, `TableGridProbeTest`; vanilla seed 4242, Earth seeds 4242 and 6258605606326987467): how far one walks before the ground is 20 blocks from where one started: vanilla median 128 m, 90th
  percentile 770 m; an Earth world median 1.15 km, 90th 4.7 to 5.2 km. The height range within 512 blocks, medians: vanilla plains 46, forest 49, taiga 48, jungle 46, savanna 47, desert 31, swamp 31, meadow 65, badlands 63;
  Earth plains 8 to 12, forest 11 to 15, taiga 12, jungle 8 to 20, savanna 6 to 19, desert 13 to 20. In vanilla the families barely differ in roughness either (rolling everywhere, flat only in desert and swamp); its table
  gives the same families at the erosion of plains and of hills except in the hot (desert at erosion 0.25, badlands at -0.1 and under). So the cause is the simulation's: broad plains, relief only where ranges stand.
* **`Hills`, laid on the ground in `Detail.blocks`** (not in `smoothBlocks`, so the river network, the marsh and the shore are exactly as they were): a grid, one value a cell, of hills in blocks, bicubic between cells, times a
  fade of 1 to 0 over 6 cells from any river or lake. Its parts: the *character* of a region (a noise 3.6 km across mapped to 0, flat, through 1, hilly: about a sixth of the land is flat, a quarter hilly); hills of three sizes
  (950, 475 and 240 blocks across) of `58 * (0.2 + 0.8 * character) * (0.6 + 0.4 * wetness)` blocks; none under 2 blocks of height and fully from 30, none where the relief is over 120 to 450 m (mountains have their own),
  and a hollow takes at most 55% of a point's height; *mesa* terraces (steps of 6.5 to 11.5 blocks, varying by region, rounded) in dry hilly country; *dunes* (4.5 blocks, long ridges 85 apart across, 300 along, of a regional
  direction) on dry, flat, inland lowland. The calibration was by the probe: amplitude 34, shape 2 gave plains 15; 48 gave 22; 58 and 950 blocks gave 24 to 36.
* **The biome source reads the same fields.** `World.hilliness` is the character; lowland erosion falls by 0.38 over a character of 0.70 to 0.95 (to about -0.13, vanilla's third band); `BiomeClimate.climateRain` maps the
  continent's rain (middle 650 mm, a wet place 1,100) onto the 1,000 mm the climate calls neutral at 1.85 mm to the mm, and `rainbias` is 0.8. Hills 0 gives no hill country in the biomes either.
* **Result**: height range within 512 blocks, medians, Earth seeds 4242 / 6258605606326987467: plains 25 / 32, forest 26 / 32, taiga 24 / 34, savanna 27 / 33, jungle 22 / 32, desert 22 / 27, meadow 30 / 35, swamp 13 (flat marsh);
  all land 28 / 34 (vanilla 49). The walk: median 640 m, 90th percentile 2.9 / 2.8 km. Shares on seed 4242 against 1.2.0 (of the window): plains 10.1 to 6.5%, forest 9.3 to 7.8%, dark forest 0.9 to 2.3%, jungle 0.9 to 1.5%,
  old-growth taiga 0.5 to 1.2%, taiga 2.8 to 3.8%, desert 1.2 to 1.0%; every biome, every placed feature and every structure still occurs (`VanillaCoverageProbeTest`). `HillsTest`, `EarthBiomeClimateTest` and the detail
  tests cover it; `HillsPreviewTest` draws a stretch with and without the hills (`-Depicterrain.hillsPreview=SEED,X,Z`).
* **Not done:** the biome families do not each have a landform of their own (no jungle ridges, no tundra domes): the terrain does not see vanilla's temperature, which is in the biome source's noise, so the link is through
  rain and region. A model-level climate would allow that, at the price of replacing vanilla's regional noise. The hills are noise and not erosion (they do not follow drainage, though the rivers' valleys part them).

## Design notes

* Biomes and terrain shape are separate in modern worldgen. The Earth world keeps vanilla's biome table and asks it about the simulated ground's height, rainfall and distance from the sea, so
  vanilla's own biomes follow the terrain and every structure and mod that reads biome tags works unchanged.
* Vanilla's `offset`, `factor`, `jaggedness`, depth, surface-level and slide functions are copied and rewritten rather than replaced, so caves, aquifers, ore veins, structures and surface rules keep
  working unchanged.
* The first world type (removed in 1.1.0) cut ranges into vanilla terrain with a custom density function and planned its rivers over the finished ground. The user judged its carved rivers unnatural
  ("it straight up looked unnatural"), which is why the Earth world's rivers come out of the same simulation as its mountains, and then the mod was cut down to the Earth world.
