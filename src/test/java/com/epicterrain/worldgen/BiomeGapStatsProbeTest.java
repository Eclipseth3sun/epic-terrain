package com.epicterrain.worldgen;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Numbers for talking about the biomes the Earth world lacks: how much of vanilla's own surface each of them is, and how much of an Earth world each candidate rule would
 * claim. Nothing happens unless {@code -Depicterrain.gapStats=SEED[,SEED...]} is given.
 */
@Tag("preview")
class BiomeGapStatsProbeTest {
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);
	private static final String[] TARGETS = { "swamp", "mangrove_swamp", "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_savanna", "stony_shore", "stony_peaks",
		"deep_ocean", "deep_cold_ocean", "deep_lukewarm_ocean", "deep_frozen_ocean", "ocean", "cold_ocean", "lukewarm_ocean", "frozen_ocean", "warm_ocean", "beach", "plains", "forest" };

	@Test
	void stats() {
		String seeds = System.getProperty("epicterrain.gapStats");
		if (seeds == null) {
			return;
		}
		vanilla(Long.parseLong(seeds.split(",")[0].trim()));
		for (String s : seeds.split(",")) {
			earth(Long.parseLong(s.trim()));
			EarthWorlds.clear();
		}
	}

	private static void vanilla(long seed) {
		WorldgenHarness harness = WorldgenHarness.shared();
		WorldPreset preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		ChunkGenerator generator = preset.overworld().orElseThrow().generator();
		NoiseGeneratorSettings settings = harness.settings("minecraft:overworld").orElseThrow();
		RandomState state = harness.randomState(seed, settings);
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		Map<String, Integer> counts = new TreeMap<>();
		Map<String, java.util.List<Integer>> floors = new TreeMap<>();
		Map<String, java.util.List<Integer>> swampGround = new TreeMap<>();
		int total = 0;
		int half = 40_000;
		for (int x = -half; x < half; x += 200) {
			for (int z = -half; z < half; z += 200) {
				int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
				Holder<Biome> biome = resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(Math.max(y - 2, 0), 4), Math.floorDiv(z, 4));
				String id = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				counts.merge(id, 1, Integer::sum);
				total++;
				if (id.contains("ocean") && (x / 200 + z / 200) % 3 == 0) {
					net.minecraft.world.level.NoiseColumn column = generator.getBaseColumn(x, z, WORLD, state);
					int floor = 62;
					while (floor > -60 && (column.getBlock(floor).isAir() || column.getBlock(floor).is(net.minecraft.world.level.block.Blocks.WATER))) {
						floor--;
					}
					floors.computeIfAbsent(id.startsWith("deep") ? "deep_*" : "ocean_*", k -> new java.util.ArrayList<>()).add(63 - floor);
				}
				if (id.equals("swamp") || id.equals("mangrove_swamp")) {
					net.minecraft.world.level.NoiseColumn column = generator.getBaseColumn(x, z, WORLD, state);
					int top = 120;
					while (top > -60 && column.getBlock(top).isAir()) {
						top--;
					}
					int solid = top;
					while (solid > -60 && (column.getBlock(solid).isAir() || column.getBlock(solid).is(net.minecraft.world.level.block.Blocks.WATER))) {
						solid--;
					}
					swampGround.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(solid - 62);
					swampGround.computeIfAbsent(id + " water", k -> new java.util.ArrayList<>()).add(column.getBlock(63).is(net.minecraft.world.level.block.Blocks.WATER) || column.getBlock(62).is(net.minecraft.world.level.block.Blocks.WATER) ? 1 : 0);
				}
			}
		}
		for (Map.Entry<String, java.util.List<Integer>> e : floors.entrySet()) {
			java.util.List<Integer> v = e.getValue();
			java.util.Collections.sort(v);
			System.out.printf("VANILLA ocean floor depth below sea level, %s: %d samples, 10%% %d, 50%% %d, 90%% %d, max %d blocks%n", e.getKey(), v.size(), v.get(v.size() / 10), v.get(v.size() / 2), v.get(v.size() * 9 / 10), v.get(v.size() - 1));
		}
		for (Map.Entry<String, java.util.List<Integer>> e : swampGround.entrySet()) {
			java.util.List<Integer> v = e.getValue();
			java.util.Collections.sort(v);
			if (e.getKey().endsWith("water")) {
				System.out.printf("VANILLA %s: %d columns, water at the sea level surface in %.0f%%%n", e.getKey(), v.size(), 100.0 * v.stream().mapToInt(Integer::intValue).sum() / v.size());
			} else {
				System.out.printf("VANILLA %s ground relative to y 62: %d samples, 10%% %d, 50%% %d, 90%% %d blocks%n", e.getKey(), v.size(), v.get(v.size() / 10), v.get(v.size() / 2), v.get(v.size() * 9 / 10));
			}
		}
		System.out.printf("VANILLA surface biome shares, 80 km square, seed %d, %d samples (share of everything, then of land = not ocean/beach/river):%n", seed, total);
		int land = 0;
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			if (!e.getKey().contains("ocean") && !e.getKey().contains("beach") && !e.getKey().contains("river")) {
				land += e.getValue();
			}
		}
		for (String t : TARGETS) {
			int n = counts.getOrDefault(t, 0);
			boolean isLand = !t.contains("ocean") && !t.contains("beach") && !t.contains("river");
			System.out.printf("  %-26s %5.2f%% of all%s%n", t, 100.0 * n / total, isLand ? String.format(", %5.2f%% of land", 100.0 * n / land) : "");
		}
	}

	private static void earth(long seed) {
		WorldgenHarness harness = WorldgenHarness.shared();
		WorldPreset preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
		EarthChunkGenerator generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
		RandomState state = harness.randomState(seed, generator.generatorSettings().value());
		generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		EarthWorld world = generator.world();
		double[] w = world.window();
		double[] out = new double[3];
		long land = 0, sea = 0, seaDeep69 = 0, seaDeep24 = 0, lowland = 0, foothills = 0, montane = 0, subalpine = 0, peaks = 0, peaksDry900 = 0, peaksDry1200 = 0;
		long coastLand = 0, coastMountain = 0;
		long swampA = 0, swampB = 0, swampC = 0, lowFlat = 0;
		double[] rainSample = new double[200_000];
		int rains = 0;
		for (int x = (int) w[0]; x < w[1]; x += 64) {
			for (int z = (int) w[2]; z < w[3]; z += 64) {
				world.sampleBlock(x, z, out);
				if (out[0] < 0.0) {
					sea++;
					if (out[0] <= -68.9) {
						seaDeep69++;
					}
					if (out[0] <= -24.0) {
						seaDeep24++;
					}
					continue;
				}
				land++;
				double relief = world.reliefMetres(x, z);
				double rain = world.rainMm(x, z);
				double coast = world.coastBlocks(x, z);
				if (rains < rainSample.length) {
					rainSample[rains++] = rain;
				}
				boolean mountainous = relief >= 300.0;
				if (relief < 300.0) {
					lowland++;
				} else if (relief < 900.0) {
					foothills++;
				} else if (relief < 1800.0) {
					montane++;
				} else if (relief < 3000.0) {
					subalpine++;
				} else {
					peaks++;
					if (rain < 900.0) {
						peaksDry900++;
					}
					if (rain < 1200.0) {
						peaksDry1200++;
					}
				}
				if (coast <= 96.0) {
					coastLand++;
					if (mountainous) {
						coastMountain++;
					}
				}
				if (!mountainous && coast > 96.0) {
					if (out[0] <= 6.0) {
						lowFlat++;
						if (rain >= 1000.0) {
							swampA++;
						}
						if (rain >= 1300.0) {
							swampB++;
						}
						if (rain >= 1600.0) {
							swampC++;
						}
					}
				}
			}
		}
		// the biomes themselves: shares of the window's land, and the ground of the swamps against vanilla's
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		Map<String, Integer> biomeCounts = new TreeMap<>();
		java.util.List<Integer> marshGround = new java.util.ArrayList<>();
		int marshFlooded = 0;
		long cells = 0;
		long landCells = 0;
		long deep = 0, seaCells = 0;
		for (int x = (int) w[0]; x < w[1]; x += 64) {
			for (int z = (int) w[2]; z < w[3]; z += 64) {
				world.sampleBlock(x, z, out);
				int y = (int) Math.ceil(63 + Math.max(out[0], -30.0)) + 4;
				String id = resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				biomeCounts.merge(id, 1, Integer::sum);
				cells++;
				boolean isOcean = id.contains("ocean");
				if (isOcean) {
					seaCells++;
					if (id.startsWith("deep")) {
						deep++;
					}
				} else if (!id.contains("river")) {
					landCells++;
				}
				if (id.equals("swamp") || id.equals("mangrove_swamp")) {
					marshGround.add((int) Math.ceil(out[0]));
					if (Math.ceil(out[0]) <= -1.0) {
						marshFlooded++;
					}
				}
			}
		}
		System.out.printf("  MARSH: swamp %.2f%% and mangrove swamp %.2f%% of land (vanilla 1.4%% and 0.8%%)%n", 100.0 * biomeCounts.getOrDefault("swamp", 0) / landCells, 100.0 * biomeCounts.getOrDefault("mangrove_swamp", 0) / landCells);
		if (!marshGround.isEmpty()) {
			java.util.Collections.sort(marshGround);
			int n = marshGround.size();
			System.out.printf("  MARSH ground (blocks above the sea, rounded up; vanilla's swamps are 10%% -3, 50%% 0, 90%% +6 about y 62): %d samples, 10%% %d, 50%% %d, 90%% %d, flooded %.0f%% (vanilla 33-36%%)%n", n,
				marshGround.get(n / 10), marshGround.get(n / 2), marshGround.get(n * 9 / 10), 100.0 * marshFlooded / n);
		}
		String[] watch = { "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_savanna", "stony_shore", "stony_peaks", "beach", "deep_ocean", "deep_cold_ocean", "deep_lukewarm_ocean", "deep_frozen_ocean", "ocean", "cold_ocean", "lukewarm_ocean", "frozen_ocean", "warm_ocean" };
		StringBuilder shares = new StringBuilder();
		for (String b : watch) {
			boolean isSea = b.contains("ocean");
			shares.append(String.format(" %s %.2f%%", b, 100.0 * biomeCounts.getOrDefault(b, 0) / (isSea ? seaCells : landCells)));
		}
		System.out.println("  SHARES (of land, or of sea for oceans):" + shares);
		System.out.printf("  deep ocean is %.0f%% of the window's sea (vanilla about 29%%)%n", 100.0 * deep / Math.max(1, seaCells));
		java.util.Arrays.sort(rainSample, 0, rains);
		System.out.printf("EARTH seed %d: land %d cells, sea %d cells (64-block cells over the window)%n", seed, land, sea);
		System.out.printf("  relief bands, share of land: lowland %.1f%%, foothills %.1f%%, montane %.1f%%, subalpine %.1f%%, peaks %.1f%%%n", 100.0 * lowland / land, 100.0 * foothills / land,
			100.0 * montane / land, 100.0 * subalpine / land, 100.0 * peaks / land);
		System.out.printf("  rain on land (mm): 10%% %.0f, 50%% %.0f, 90%% %.0f%n", rainSample[rains / 10], rainSample[rains / 2], rainSample[rains * 9 / 10]);
		System.out.printf("  low flat inland ground (lowland, 6 blocks or less above the sea, more than 96 blocks from the coast): %.1f%% of land; of land with rain >= 1000: %.1f%%, >= 1300: %.1f%%, >= 1600: %.1f%%%n",
			100.0 * lowFlat / land, 100.0 * swampA / land, 100.0 * swampB / land, 100.0 * swampC / land);
		System.out.printf("  coast (within 96 blocks of the sea): %.1f%% of land; of it, with 300 m or more of relief (rocky coast): %.1f%%%n", 100.0 * coastLand / land, 100.0 * coastMountain / Math.max(1, coastLand));
		System.out.printf("  peaks zone: %.2f%% of land; of it dry (< 900 mm): %.0f%%, (< 1200 mm): %.0f%%%n", 100.0 * peaks / land, 100.0 * peaksDry900 / Math.max(1, peaks), 100.0 * peaksDry1200 / Math.max(1, peaks));
		System.out.printf("  sea floor in the window: %.0f%% is 24 blocks or deeper (vanilla's 'ocean' rather than the shelf), %.0f%% is 69 blocks or deeper (where 1,500 m of real depth would be)%n",
			100.0 * seaDeep24 / sea, 100.0 * seaDeep69 / sea);
	}
}
