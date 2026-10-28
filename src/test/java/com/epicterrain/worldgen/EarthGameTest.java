package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthConfig;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;
import com.epicterrain.earth.Islands;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Earth world type through the real pipeline: the world preset loaded by the registry loader, its chunk generator and biome source, a random
 * state compiled from the real noise settings. The window is a small, coarse one (so the simulation takes seconds, not a minute); everything
 * else is as in the game.
 */
class EarthGameTest {
	private static final long SEED = 4242L;
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);
	private static final String[] DIALS = { "cell", "window", "steps" };

	private static WorldgenHarness harness;
	private static EarthChunkGenerator generator;
	private static RandomState state;
	private static EarthWorld world;

	@BeforeAll
	static void build() {
		System.setProperty("epicterrain.earth.cell", "96");
		System.setProperty("epicterrain.earth.window", "1.2");
		System.setProperty("epicterrain.earth.steps", "50");
		EarthWorlds.clear();
		harness = WorldgenHarness.shared();
		WorldPreset preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
		assertTrue(preset.overworld().orElseThrow().generator() instanceof EarthChunkGenerator, "the preset's generator");
		generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		state = harness.randomState(SEED, settings);
		// the generator finds its world the first time it is used with a random state
		generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		world = generator.world();
	}

	@AfterAll
	static void restore() {
		for (String dial : DIALS) {
			System.clearProperty("epicterrain.earth." + dial);
		}
		EarthWorlds.clear();
	}

	/** The biome of the surface at a column: asked just above the ground, since below it there are the biomes of the caves. */
	private static Holder<Biome> biomeAt(BiomeResolver resolver, int x, int z) {
		double[] out = new double[3];
		world.sampleBlock(x, z, out);
		int y = (int) Math.ceil(EarthField.SEA_LEVEL_Y + Math.max(out[0], -30.0)) + 4;
		return resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4));
	}

	@Test
	void theGeneratorFindsTheWorldTheFieldWasCompiledFor() {
		assertNotNull(world, "the generator's world");
		Climate.Sampler sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		assertTrue(EarthField.worldOf(sampler.continentalness().sampler()) == world, "the climate sampler reads the same world, not a fallback");
	}

	@Test
	void theGeneratorCanBeBuiltBeforeItsNoiseSettingsAreLoaded() {
		// the registries load in parallel, and the world preset (which holds the generator) can be decoded before the noise settings it names are bound; an earlier build of the
		// fluid picker read the settings in the constructor and failed the whole world list on some runs (it showed in the full suite, not in a single class)
		Holder.Reference<NoiseGeneratorSettings> unbound = Holder.Reference.createStandAlone(harness.registries.lookupOrThrow(Registries.NOISE_SETTINGS),
			ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.parse("epicterrain:not_loaded_yet")));
		assertNotNull(new EarthChunkGenerator(generator.getBiomeSource(), unbound));
	}

	@Test
	void theSettingsKeepVanillasAquifersAndHaveTheEarthRouter() {
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		assertTrue(settings.aquifers().isPresent(), "vanilla's aquifers, which the generator only lets work below the ground");
		assertEquals(-64, settings.noiseSettings().minY());
		assertEquals(576, settings.noiseSettings().height());
	}
	@Test
	void theBiomesAreVanillasWithTheirCavesAndCanyons() {
		Map<String, Holder<Biome>> byName = new HashMap<>();
		for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
			byName.put(biome.getRegisteredName(), biome);
		}
		assertTrue(byName.size() > 50, "the source can answer with vanilla's biomes and the three island biomes, got " + byName.size());
		for (String name : new String[] { "minecraft:plains", "minecraft:desert", "minecraft:ocean", "minecraft:stony_peaks", "minecraft:river", "epicterrain:volcano", "epicterrain:atoll",
			"epicterrain:crater_lake" }) {
			assertNotNull(byName.get(name), name + " is a possible biome");
			assertTrue(byName.get(name).value().getGenerationSettings().getCarvers().iterator().hasNext(), name + " has carvers");
		}
		for (String name : new String[] { "minecraft:lush_caves", "minecraft:dripstone_caves", "minecraft:deep_dark" }) {
			assertTrue(byName.containsKey(name), name + " can be answered with, below the ground");
		}
		assertTrue(byName.keySet().stream().noneMatch(name -> name.startsWith("epicterrain:earth_")), "no copies of vanilla's biomes any more");
	}
	@Test
	void theBiomesFeaturesCanBeOrderedWithoutACycle() {
		// the game sorts the features of every possible biome into one order when the generator is first used, and refuses to if two biomes disagree
		List<Holder<Biome>> biomes = new ArrayList<>(generator.getBiomeSource().possibleBiomes());
		var steps = FeatureSorter.buildFeaturesPerStep(biomes, biome -> biome.value().getGenerationSettings().features(), true);
		assertTrue(!steps.isEmpty(), "decoration steps");
	}

	@Test
	void groundOfTheGeneratorFollowsTheModel() {
		double[] window = world.window();
		Random random = new Random(7);
		double[] out = new double[3];
		int n = 400;
		int[] errors = new int[n];
		for (int i = 0; i < n; i++) {
			int x = (int) (window[0] + random.nextDouble() * (window[1] - window[0]));
			int z = (int) (window[2] + random.nextDouble() * (window[3] - window[2]));
			world.sampleBlock(x, z, out);
			int actual = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
			// the first free block: one above the highest block with density > 0, or the water's surface
			int expected = Double.isNaN(out[1]) ? (int) Math.ceil(63 + Math.max(out[0], 0.0)) : 63 + (int) Math.round(Math.max(out[1], 0.0));
			errors[i] = Math.abs(actual - expected);
		}
		Arrays.sort(errors);
		System.out.printf("surface of getBaseHeight vs the model over %d random columns: median error %d, 90th %d, worst %d blocks%n", n, errors[n / 2], errors[n * 9 / 10], errors[n - 1]);
		assertTrue(errors[n / 2] <= 2, "median error " + errors[n / 2]);
		assertTrue(errors[n * 9 / 10] <= 12, "90th percentile error " + errors[n * 9 / 10]);
	}

	@Test
	void groundOnTheCellCornersIsTheModelsExceptWhereACaveOpensTheSurface() {
		double[] window = world.window();
		Random random = new Random(11);
		double[] out = new double[3];
		int checked = 0;
		int different = 0;
		for (int i = 0; i < 400; i++) {
			int x = ((int) (window[0] + random.nextDouble() * (window[1] - window[0]))) & ~3;
			int z = ((int) (window[2] + random.nextDouble() * (window[3] - window[2]))) & ~3;
			world.sampleBlock(x, z, out);
			if (!Double.isNaN(out[1])) {
				continue;
			}
			checked++;
			int expected = (int) Math.ceil(63 + Math.max(out[0], 0.0));
			if (generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state) != expected) {
				different++;
			}
		}
		System.out.printf("dry ground at cell corners: %d checked, %d differ from the model (cave entrances and noodle caves that open the surface)%n", checked, different);
		assertTrue(checked > 100);
		assertTrue(different * 10 <= checked, different + " of " + checked + " columns do not have the modelled ground");
	}
	@Test
	void oceanFloorBeyondTheWindowIsDeepAndFilledWithSea() {
		double[] window = world.window();
		int x = (int) (window[1] + 9_000);
		int z = 0;
		int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		assertEquals(63, surface, "the sea fills to sea level (the surface heightmap counts water)");
		NoiseColumn column = generator.getBaseColumn(x, z, WORLD, state);
		int floor = 62;
		while (column.getBlock(floor).is(Blocks.WATER)) {
			floor--;
		}
		assertTrue(floor < 20, "the open ocean floor is deep, got y=" + floor);
		assertEquals(Blocks.STONE.defaultBlockState(), column.getBlock(floor), "stone under the water");
		assertEquals(Blocks.AIR.defaultBlockState(), column.getBlock(63), "air above the sea");
	}

	@Test
	void riversAndLakesStandAtTheirOwnLevelAboveTheSea() {
		double[] window = world.window();
		double[] out = new double[3];
		int river = 0;
		int checked = 0;
		int wrong = 0;
		for (int wx = ((int) window[0]) & ~3; wx < window[1] && checked < 80; wx += 96) {
			for (int wz = ((int) window[2]) & ~3; wz < window[3] && checked < 80; wz += 92) {
				world.sampleBlock(wx, wz, out);
				if (Double.isNaN(out[1]) || out[0] < 0) {
					continue;
				}
				NoiseColumn column = generator.getBaseColumn(wx, wz, WORLD, state);
				int solidTop = (int) Math.ceil(63 + out[0]) - 1; // highest block with density > 0
				int level = 63 + (int) Math.round(out[1]);        // water fills the open blocks below this y
				if (level - 1 <= solidTop) {
					continue; // too shallow to hold a block of water
				}
				checked++;
				river += out[2] == EarthWorld.RIVER ? 1 : 0;
				boolean ok = column.getBlock(solidTop + 1).is(Blocks.WATER) && column.getBlock(level - 1).is(Blocks.WATER) && !column.getBlock(level).is(Blocks.WATER);
				if (!ok) {
					wrong++;
					System.out.printf("water mismatch at %d,%d: ground top %d, level %d%n", wx, wz, solidTop, level);
				}
			}
		}
		System.out.printf("water above the sea: %d columns checked, %d river, %d wrong%n", checked, river, wrong);
		assertTrue(checked >= 5, "the small window has rivers or lakes above the sea, found " + checked);
		assertEquals(0, wrong, "columns of river and lake water that are not as modelled");
	}

	@Test
	void surfaceBiomesFollowTheModel() {
		Climate.Sampler sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		BiomeResolver resolver = generator.getBiomeSource().createResolver(sampler);
		double[] window = world.window();
		// the open ocean beyond the window is ocean
		Holder<Biome> far = biomeAt(resolver, (int) window[1] + 12_000, 100);
		assertTrue(far.is(BiomeTags.IS_OCEAN), "open ocean, got " + far.getRegisteredName());
		double[] out = new double[3];
		int land = 0;
		int oceanOnLand = 0;
		int unknown = 0;
		Set<Holder<Biome>> possible = generator.getBiomeSource().possibleBiomes();
		for (double wx = window[0]; wx < window[1]; wx += 311) {
			for (double wz = window[2]; wz < window[3]; wz += 307) {
				world.sampleBlock((int) wx, (int) wz, out);
				Holder<Biome> biome = biomeAt(resolver, (int) wx, (int) wz);
				if (!possible.contains(biome)) {
					unknown++;
				}
				if (out[0] > 8 && Double.isNaN(out[1])) {
					land++;
					oceanOnLand += biome.is(BiomeTags.IS_OCEAN) ? 1 : 0;
				}
			}
		}
		System.out.printf("surface biomes: %d dry land columns, %d with an ocean biome, %d not in the source's possible biomes%n", land, oceanOnLand, unknown);
		assertTrue(land > 20, "dry land in the window, got " + land);
		assertEquals(0, oceanOnLand, "ocean biomes on dry land");
		assertEquals(0, unknown, "every biome is one the source says it can answer with");
	}
	@Test
	void theBiomeSourceNamesFeaturesOfTheIslandCatalogue() {
		Climate.Sampler sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		BiomeResolver resolver = generator.getBiomeSource().createResolver(sampler);
		TagKey<Biome> mushroom = TagKey.create(Registries.BIOME, Identifier.parse("epicterrain:earth_mushroom_island"));
		TagKey<Biome> volcano = TagKey.create(Registries.BIOME, Identifier.parse("epicterrain:earth_volcano"));
		TagKey<Biome> atoll = TagKey.create(Registries.BIOME, Identifier.parse("epicterrain:earth_atoll"));
		int found = 0;
		int foundBiome = 0;
		int wrong = 0;
		for (int x = -60_000; x <= 60_000; x += 160) {
			for (int z = -60_000; z <= 60_000; z += 160) {
				Islands.Highlight h = world.highlight(x, z);
				boolean m = world.mushroom(x, z);
				if (h == null && !m) {
					continue;
				}
				found++;
				Holder<Biome> biome = biomeAt(resolver, x, z);
				boolean ok = (h == Islands.Highlight.VOLCANO && biome.is(volcano)) || (h == Islands.Highlight.ATOLL && biome.is(atoll))
					|| (h == Islands.Highlight.CRATER_LAKE && biome.getRegisteredName().equals("epicterrain:crater_lake")) || (m && biome.is(mushroom));
				// a column that is open sea beside an island is still on its island's footprint but needs not name it
				double[] out = new double[3];
				world.sampleBlock(x, z, out);
				if (out[0] > 1.0) {
					foundBiome++;
					if (!ok) {
						wrong++;
					}
				}
			}
		}
		System.out.printf("island features in 120 km: %d columns, %d on land, %d with the wrong biome%n", found, foundBiome, wrong);
		assertTrue(foundBiome == 0 || wrong * 5 <= foundBiome, wrong + " of " + foundBiome + " island columns have the wrong biome");
	}

	/** A structure manager over a world with no structures anywhere: every chunk it is asked about is an empty one. */
	private static StructureManager noStructures(ProtoChunk empty) {
		LevelAccessor level = (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[] { LevelAccessor.class }, (proxy, method, args) -> {
			Class<?> type = method.getReturnType();
			if (method.getName().equals("getChunk") && type.isAssignableFrom(ProtoChunk.class)) {
				return empty;
			}
			return type == boolean.class ? (Object) false : type == int.class ? (Object) 0 : type == long.class ? (Object) 0L : type == float.class ? (Object) 0f
				: type == double.class ? (Object) 0.0 : null;
		});
		return new StructureManager(level, new WorldOptions(SEED, false, false), null);
	}

	private ProtoChunk build(int chunkX, int chunkZ) {
		PalettedContainerFactory factory = PalettedContainerFactory.create(harness.registries);
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		StructureManager structures = noStructures(new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, WORLD, factory, null));
		ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, WORLD, factory, null);
		Climate.Sampler sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		generator.createBiomes(state, Blender.empty(), structures, chunk).join();
		BiomeManager biomes = new BiomeManager(generator.getBiomeSource().createResolver(sampler), 0L);
		generator.buildTerrain(chunk, Blender.empty(), state, structures, biomes, null, Set.of()).join();
		return chunk;
	}

	@Test
	void realChunksAreBuiltWithTheModelsGroundWaterAndSurface() {
		double[] window = world.window();
		double[] out = new double[3];
		// a chunk with a river in it, the chunk at the origin and a chunk of the open sea
		int[][] chunks = new int[3][];
		search:
		for (int wx = ((int) window[0]) & ~15; wx < window[1]; wx += 16 * 7) {
			for (int wz = ((int) window[2]) & ~15; wz < window[3]; wz += 16 * 7) {
				world.sampleBlock(wx + 8, wz + 8, out);
				if (out[2] == EarthWorld.RIVER && out[1] > 8 && out[0] > 0) {
					chunks[0] = new int[] { wx >> 4, wz >> 4 };
					break search;
				}
			}
		}
		chunks[1] = new int[] { 0, 0 };
		chunks[2] = new int[] { ((int) window[1] + 6_000) >> 4, 0 };
		int index = 0;
		for (int[] c : chunks) {
			index++;
			if (c == null) {
				continue;
			}
			ProtoChunk chunk = build(c[0], c[1]);
			Map<String, Integer> tops = new TreeMap<>();
			int wrongGround = 0;
			int wrongWater = 0;
			int columns = 0;
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					int x = c[0] * 16 + dx;
					int z = c[1] * 16 + dz;
					world.sampleBlock(x, z, out);
					int top = 511;
					while (top > -64 && chunk.getBlockState(new BlockPos(x, top, z)).isAir()) {
						top--;
					}
					BlockState topState = chunk.getBlockState(new BlockPos(x, top, z));
					int solid = top;
					while (solid > -64 && (chunk.getBlockState(new BlockPos(x, solid, z)).isAir() || chunk.getBlockState(new BlockPos(x, solid, z)).is(Blocks.WATER))) {
						solid--;
					}
					tops.merge(chunk.getBlockState(new BlockPos(x, solid, z)).getBlock().toString().replace("Block{minecraft:", "").replace("}", ""), 1, Integer::sum);
					columns++;
					int expectedSolid = (int) Math.ceil(63 + out[0]) - 1;
					if (Math.abs(solid - expectedSolid) > 0) {
						wrongGround++;
					}
					int level = Double.isNaN(out[1]) ? 63 : 63 + (int) Math.round(out[1]);
					if (out[0] < 0 || !Double.isNaN(out[1])) {
						boolean water = level - 1 > solid ? topState.is(Blocks.WATER) && top == level - 1 : true;
						if (!water) {
							wrongWater++;
						}
					}
				}
			}
			System.out.printf("chunk %d (%d,%d): %d columns, %d with the wrong ground, %d with the wrong water; surface blocks %s%n", index, c[0], c[1], columns, wrongGround, wrongWater, tops);
			assertTrue(wrongGround * 4 <= columns, "chunk " + index + ": " + wrongGround + " columns whose highest solid block is not the modelled ground (a cave opening the surface is the only reason)");
			assertTrue(wrongWater * 4 <= columns, "chunk " + index + ": " + wrongWater + " columns whose water is not at the modelled level");
		}
	}

	@Test
	void manyThreadsAskingAColdWorldGetTheSameAnswersAsOne() {
		// worldgen threads ask for columns in any order at the same time, and the catalogue of islands fills its caches as they go
		EarthConfig config = world.config;
		EarthWorld fresh = new EarthWorld(config, line -> { });
		int n = 24_000;
		Random random = new Random(3);
		int[] xs = new int[n];
		int[] zs = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = (int) ((random.nextDouble() - 0.5) * 140_000);
			zs[i] = (int) ((random.nextDouble() - 0.5) * 140_000);
		}
		double[][] expected = new double[n][3];
		for (int i = 0; i < n; i++) {
			world.sample(xs[i], zs[i], expected[i]);
		}
		double[][] actual = new double[n][3];
		IntStream.range(0, n).parallel().forEach(i -> fresh.sampleBlock(xs[i], zs[i], actual[i]));
		int different = 0;
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 3; k++) {
				if (Double.compare(expected[i][k], actual[i][k]) != 0) {
					different++;
					break;
				}
			}
		}
		assertEquals(0, different, "columns that differ when asked by many threads at once, of " + n);
	}

	@Test
	void chunkSpeed() {
		long start = System.nanoTime();
		int chunks = 0;
		for (int cx = -4; cx < 4; cx++) {
			for (int cz = -4; cz < 4; cz++) {
				build(cx * 3, cz * 3);
				chunks++;
			}
		}
		double perChunk = (System.nanoTime() - start) / 1e6 / chunks;
		System.out.printf("building %d chunks (biomes, fill, surface) on one thread: %.1f ms each%n", chunks, perChunk);
		assertTrue(perChunk < 500, "a chunk takes " + perChunk + " ms");
	}

	@Test
	void theBiomesAroundAPlaceAreTheSameAsVanillasCubeOfThemWouldGive() {
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		double[] window = world.window();
		Random random = new Random(21);
		int checked = 0;
		for (int i = 0; i < 45; i++) {
			int x = (int) ((i % 2 == 0 ? window[0] : window[1]) * random.nextDouble() * 1.3);
			int z = (int) (window[2] + random.nextDouble() * (window[3] - window[2]));
			int cy = new int[] { 63, 0, -40 }[i % 3];
			Set<Holder<Biome>> expected = new HashSet<>();
			for (int qy = QuartPos.fromBlock(cy - 29); qy <= QuartPos.fromBlock(cy + 29); qy++) {
				for (int qz = QuartPos.fromBlock(z - 29); qz <= QuartPos.fromBlock(z + 29); qz++) {
					for (int qx = QuartPos.fromBlock(x - 29); qx <= QuartPos.fromBlock(x + 29); qx++) {
						expected.add(resolver.getNoiseBiome(qx, qy, qz));
					}
				}
			}
			assertEquals(expected, resolver.getBiomesWithin(x, cy, z, 29), "the biomes within 29 blocks of " + x + "," + cy + "," + z);
			checked++;
		}
		assertEquals(45, checked);
	}

	private static LevelReader tallLevel() {
		return (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[] { LevelReader.class }, (proxy, method, args) -> {
			return switch (method.getName()) {
				case "getMinY" -> -64;
				case "getMaxY" -> 511;
				case "getHeight" -> 576;
				default -> null;
			};
		});
	}

	@Test
	void theNearestBiomeIsFoundLikeVanillaWouldFindItAndAFutileSearchGivesUpInTime() {
		var source = generator.getBiomeSource();
		BiomeResolver resolver = source.createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		Holder<Biome> here = biomeAt(resolver, 0, 0);
		// a biome that is not the one at the origin, and how far away it is along the east axis
		Holder<Biome> other = null;
		int distance = 0;
		for (int x = 32; x < 6_000 && other == null; x += 32) {
			Holder<Biome> there = biomeAt(resolver, x, 0);
			if (!there.equals(here)) {
				other = there;
				distance = x;
			}
		}
		assertNotNull(other, "a different biome within 6 km along the east axis");
		final Holder<Biome> wanted = other;
		double[] origin = new double[3];
		world.sampleBlock(0, 0, origin);
		Pair<BlockPos, Holder<Biome>> found = source.findClosestBiome3d(BlockPos.ZERO.atY((int) Math.ceil(63 + origin[0]) + 4), 6_400, 32, 64, biome -> biome.equals(wanted), state, tallLevel());
		assertNotNull(found, "the search finds " + wanted.getRegisteredName());
		assertEquals(wanted, found.getSecond());
		assertEquals(wanted, resolver.getNoiseBiome(QuartPos.fromBlock(found.getFirst().getX()), QuartPos.fromBlock(found.getFirst().getY()), QuartPos.fromBlock(found.getFirst().getZ())),
			"the biome at the position it names");
		int reach = Math.max(Math.abs(found.getFirst().getX()), Math.abs(found.getFirst().getZ()));
		assertTrue(reach <= distance, "it is at least as close (by the spiral's measure) as the one on the east axis: " + reach + " against " + distance);

		// nothing like that anywhere near: an atoll, from the middle of the continent
		TagKey<Biome> atoll = TagKey.create(Registries.BIOME, Identifier.parse("epicterrain:earth_atoll"));
		String old = System.getProperty("epicterrain.earth.locateSeconds");
		try {
			System.setProperty("epicterrain.earth.locateSeconds", "60");
			long t0 = System.nanoTime();
			assertNull(source.findClosestBiome3d(BlockPos.ZERO.atY(70), 6_400, 32, 64, biome -> biome.is(atoll), state, tallLevel()), "no atoll within 6.4 km of the origin");
			double full = (System.nanoTime() - t0) / 1e9;
			System.out.printf("a futile biome search of the whole 6.4 km radius: %.2f s%n", full);

			System.setProperty("epicterrain.earth.locateSeconds", "0.2");
			t0 = System.nanoTime();
			assertNull(source.findClosestBiome3d(BlockPos.ZERO.atY(70), 6_400, 32, 64, biome -> biome.is(atoll), state, tallLevel()));
			double limited = (System.nanoTime() - t0) / 1e9;
			System.out.printf("the same with a limit of 0.2 s: %.2f s%n", limited);
			assertTrue(limited < 1.5, "the limited search took " + limited + " s");
			assertTrue(full > limited, "the unlimited search took " + full + " s, the limited one " + limited);
		} finally {
			if (old != null) {
				System.setProperty("epicterrain.earth.locateSeconds", old);
			} else {
				System.clearProperty("epicterrain.earth.locateSeconds");
			}
		}
	}

	private static String blockName(BlockState state) {
		return state.getBlock().toString().replace("Block{minecraft:", "").replace("}", "");
	}

	@Test
	void theUndergroundHasCavesLavaOresAndDeepslate() {
		double[] window = world.window();
		double[] out = new double[3];
		Map<String, Integer> counts = new TreeMap<>();
		long voids = 0;
		long volume = 0;
		int built = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Random random = new Random(5);
		for (int tries = 0; tries < 400 && built < 24; tries++) {
			int cx = (int) ((window[0] + random.nextDouble() * (window[1] - window[0])) / 16);
			int cz = (int) ((window[2] + random.nextDouble() * (window[3] - window[2])) / 16);
			if (!world.isLand(cx * 16 + 8, cz * 16 + 8)) {
				continue;
			}
			ProtoChunk chunk = build(cx, cz);
			built++;
			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					int x = cx * 16 + dx;
					int z = cz * 16 + dz;
					world.sampleBlock(x, z, out);
					int groundTop = (int) Math.ceil(63 + out[0]) - 1;
					for (int y = -64; y < groundTop - 10; y++) {
						BlockState block = chunk.getBlockState(pos.set(x, y, z));
						volume++;
						if (block.isAir()) {
							voids++;
						}
						counts.merge(blockName(block), 1, Integer::sum);
					}
				}
			}
		}
		int vein = counts.getOrDefault("raw_iron_block", 0) + counts.getOrDefault("raw_copper_block", 0) + counts.getOrDefault("copper_ore", 0) + counts.getOrDefault("iron_ore", 0)
			+ counts.getOrDefault("deepslate_iron_ore", 0) + counts.getOrDefault("granite", 0) + counts.getOrDefault("tuff", 0);
		System.out.printf("underground of %d land chunks: %,d blocks below the ground, %.1f%% empty; lava %d, water %d, deepslate %d, bedrock %d, vein blocks %d (granite %d, tuff %d)%n", built, volume,
			100.0 * voids / volume, counts.getOrDefault("lava", 0), counts.getOrDefault("water", 0), counts.getOrDefault("deepslate", 0), counts.getOrDefault("bedrock", 0), vein,
			counts.getOrDefault("granite", 0), counts.getOrDefault("tuff", 0));
		assertTrue(built >= 8, "land chunks to look at: " + built);
		assertTrue(voids * 1000 > volume, "caves under the ground: " + voids + " empty of " + volume);
		assertTrue(voids * 100 < volume * 30, "not hollow: " + voids + " empty of " + volume);
		assertTrue(counts.getOrDefault("deepslate", 0) > 0, "deepslate at depth");
		assertTrue(counts.getOrDefault("bedrock", 0) > 0, "bedrock at the bottom");
		assertTrue(vein > 0, "ore veins (and the granite and tuff of their fillers)");
	}

	@Test
	void theCavesHaveTheirOwnBiomesAndTheDeepDarkStaysAtTheBottom() {
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		double[] window = world.window();
		double[] out = new double[3];
		Random random = new Random(31);
		Map<String, Integer> underground = new TreeMap<>();
		Map<Integer, Map<String, Integer>> byDepth = new TreeMap<>();
		int land = 0;
		int deepAtTheBottom = 0;
		int deepHigh = 0;
		int tall = 0;
		for (int i = 0; i < 8_000; i++) {
			int x = (int) (window[0] + random.nextDouble() * (window[1] - window[0]));
			int z = (int) (window[2] + random.nextDouble() * (window[3] - window[2]));
			world.sampleBlock(x, z, out);
			if (out[0] < 6 || !Double.isNaN(out[1])) {
				continue;
			}
			land++;
			double surface = 63 + out[0];
			underground.merge(resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv((int) (surface - 60), 4), Math.floorDiv(z, 4)).getRegisteredName(), 1, Integer::sum);
			for (int below : new int[] { 20, 30, 45, 60, 80, 110 }) {
				String name = resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv((int) (surface - below), 4), Math.floorDiv(z, 4)).getRegisteredName().replace("minecraft:", "");
				byDepth.computeIfAbsent(below, k -> new TreeMap<>()).merge(name.endsWith("_caves") ? name : "(surface biome)", 1, Integer::sum);
			}
			if (surface >= 190 && resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(-40, 4), Math.floorDiv(z, 4)).getRegisteredName().equals("minecraft:deep_dark")) {
				deepAtTheBottom++;
			}
			if (surface >= 330) {
				tall++;
				if (resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(100, 4), Math.floorDiv(z, 4)).getRegisteredName().equals("minecraft:deep_dark")) {
					deepHigh++;
				}
			}
		}
		System.out.printf("biomes 60 blocks below the surface of %d land columns: %s; deep dark at y -40 under high ground: %d; under %d columns of 330+ at y 100: %d%n", land, underground, deepAtTheBottom, tall,
			deepHigh);
		byDepth.forEach((below, shares) -> System.out.println("  " + below + " blocks below the surface: " + shares));
		assertTrue(land > 500);
		int caves = underground.getOrDefault("minecraft:lush_caves", 0) + underground.getOrDefault("minecraft:dripstone_caves", 0);
		assertTrue(caves * 10 > land, "lush and dripstone caves at that depth, as vanilla's table gives them: " + caves + " of " + land);
		assertTrue(underground.keySet().stream().anyMatch(name -> !name.endsWith("_caves")), "where the table has no cave for the climate, the biome of the surface goes on");
		assertEquals(0, deepHigh, "no deep dark high inside a mountain");
	}

	@Test
	void aCaveBiomeCanBeLocatedUnderground() {
		var source = generator.getBiomeSource();
		double[] origin = new double[3];
		world.sampleBlock(0, 0, origin);
		Pair<BlockPos, Holder<Biome>> found = source.findClosestBiome3d(BlockPos.ZERO.atY((int) Math.ceil(63 + origin[0]) + 4), 6_400, 32, 64, biome -> biome.getRegisteredName().endsWith("_caves"), state,
			tallLevel());
		assertNotNull(found, "some cave biome within 6.4 km");
		double[] at = new double[3];
		world.sampleBlock(found.getFirst().getX(), found.getFirst().getZ(), at);
		assertTrue(found.getFirst().getY() < 63 + at[0] - 20, "it is underground: y " + found.getFirst().getY() + " under a surface at " + (63 + at[0]));
		BiomeResolver resolver = source.createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		assertEquals(found.getSecond(), resolver.getNoiseBiome(QuartPos.fromBlock(found.getFirst().getX()), QuartPos.fromBlock(found.getFirst().getY()), QuartPos.fromBlock(found.getFirst().getZ())));
	}

	@Test
	void theSpawnTargetPrefersDryLand() {
		var targets = generator.generatorSettings().value().spawnTarget();
		assertEquals(1, targets.size(), "one spawn target");
		DensitySamplerSet samplers = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED);
		double[] window = world.window();
		double[] out = new double[3];
		int land = 0;
		int sea = 0;
		for (int x = (int) window[0]; x < window[1]; x += 701) {
			for (int z = (int) window[2]; z < window[3]; z += 709) {
				world.sampleBlock(x, z, out);
				long fitness = targets.get(0).sampleFitness(samplers, x, 0, z);
				if (world.isLand(x, z)) {
					land++;
					assertEquals(0L, fitness, "dry land is a perfect spawn at " + x + "," + z);
				} else if (out[0] < -20) {
					sea++;
					assertTrue(fitness > 0L, "deep sea is not a spawn at " + x + "," + z);
				}
			}
		}
		System.out.printf("spawn target: %d dry-land and %d deep-sea columns checked%n", land, sea);
		assertTrue(land > 10 && sea > 10, "both kinds of column were found");
	}
}
