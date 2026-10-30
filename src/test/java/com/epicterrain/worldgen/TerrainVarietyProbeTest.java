package com.epicterrain.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * How much the ground varies from place to place, and with the biome, in an Earth world against vanilla's: how far one can walk before the ground has changed by 20 blocks, and
 * how rough the ground is (the height range within 512 and within 1,024 blocks) under each land biome. Nothing happens unless {@code -Depicterrain.variety=SEED[,SEED...]} is given
 * (an Earth world for each seed, and vanilla's overworld for the first).
 */
@Tag("preview")
class TerrainVarietyProbeTest {
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);
	private static final int STEP = 128;

	@Test
	void measure() {
		String seeds = System.getProperty("epicterrain.variety");
		if (seeds == null) {
			return;
		}
		vanilla(Long.parseLong(seeds.split(",")[0].trim()));
		for (String s : seeds.split(",")) {
			earth(Long.parseLong(s.trim()));
			EarthWorlds.clear();
		}
	}

	private interface Ground {
		int height(int x, int z);

		String biome(int x, int z, int y);
	}

	private static void vanilla(long seed) {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		ChunkGenerator generator = preset.overworld().orElseThrow().generator();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		RandomState state = h.randomState(seed, settings);
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		analyse("VANILLA seed " + seed, -20_000, 40_000, new Ground() {
			@Override
			public int height(int x, int z) {
				return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
			}

			@Override
			public String biome(int x, int z, int y) {
				return resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			}
		});
	}

	private static void earth(long seed) {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
		EarthChunkGenerator generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
		RandomState state = h.randomState(seed, generator.generatorSettings().value());
		generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		EarthWorld world = generator.world();
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		double[] out = new double[3];
		double[] w = world.window();
		int half = (int) Math.min(-w[0], w[1]) - 1_000;
		analyse("EARTH seed " + seed, -half, 2 * half, new Ground() {
			@Override
			public int height(int x, int z) {
				world.sampleBlock(x, z, out);
				return (int) Math.ceil(63 + Math.max(out[0], 0.0));
			}

			@Override
			public String biome(int x, int z, int y) {
				return resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			}
		});
	}

	private static void analyse(String label, int from, int span, Ground ground) {
		int n = span / STEP;
		int[] hgt = new int[n * n];
		String[] biome = new String[n * n];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int x = from + i * STEP;
				int z = from + j * STEP;
				int y = ground.height(x, z);
				hgt[j * n + i] = y;
				biome[j * n + i] = ground.biome(x, z, y + 2);
			}
		}
		// how far one walks, along a row, before the ground is 20 blocks from where one started (or the sea or a river is reached)
		List<Integer> runs = new ArrayList<>();
		for (int j = 0; j < n; j += 3) {
			for (int i = 0; i < n; i += 5) {
				int c = j * n + i;
				if (isWater(biome[c]) || hgt[c] < 64) {
					continue;
				}
				int k = i;
				while (k + 1 < n && Math.abs(hgt[j * n + k + 1] - hgt[c]) <= 20 && !isWater(biome[j * n + k + 1])) {
					k++;
				}
				runs.add((k - i) * STEP);
			}
		}
		Collections.sort(runs);
		System.out.printf("%s: %d land starting points; the ground stays within 20 blocks of where you started for (m) 10%% %d, 25%% %d, 50%% %d, 75%% %d, 90%% %d%n", label, runs.size(),
			runs.get(runs.size() / 10), runs.get(runs.size() / 4), runs.get(runs.size() / 2), runs.get(runs.size() * 3 / 4), runs.get(runs.size() * 9 / 10));
		// how rough the ground is under each land biome: the height range within 512 and 1,024 blocks
		Map<String, List<Integer>> r512 = new TreeMap<>();
		Map<String, List<Integer>> r1k = new TreeMap<>();
		for (int j = 8; j < n - 8; j++) {
			for (int i = 8; i < n - 8; i++) {
				String b = biome[j * n + i];
				if (isWater(b) || hgt[j * n + i] < 64) {
					continue;
				}
				r512.computeIfAbsent(b, k -> new ArrayList<>()).add(range(hgt, n, i, j, 2, 1));
				r1k.computeIfAbsent(b, k -> new ArrayList<>()).add(range(hgt, n, i, j, 2, 2));
			}
		}
		System.out.printf("%s: height range within 512 and 1,024 blocks, by land biome (median / 90th percentile, in blocks):%n", label);
		List<Map.Entry<String, List<Integer>>> rows = new ArrayList<>(r512.entrySet());
		rows.sort((a, b) -> b.getValue().size() - a.getValue().size());
		for (Map.Entry<String, List<Integer>> e : rows) {
			if (e.getValue().size() < 300) {
				continue;
			}
			List<Integer> a = e.getValue();
			List<Integer> b = r1k.get(e.getKey());
			Collections.sort(a);
			Collections.sort(b);
			System.out.printf("    %-26s %6d cells   512: %3d / %3d    1,024: %3d / %3d%n", e.getKey(), a.size(), a.get(a.size() / 2), a.get(a.size() * 9 / 10), b.get(b.size() / 2), b.get(b.size() * 9 / 10));
		}
		List<Integer> all = new ArrayList<>();
		List<Integer> all1k = new ArrayList<>();
		r512.values().forEach(all::addAll);
		r1k.values().forEach(all1k::addAll);
		Collections.sort(all);
		Collections.sort(all1k);
		System.out.printf("    %-26s %6d cells   512: %3d / %3d    1,024: %3d / %3d%n", "ALL LAND", all.size(), all.get(all.size() / 2), all.get(all.size() * 9 / 10), all1k.get(all1k.size() / 2),
			all1k.get(all1k.size() * 9 / 10));
	}

	private static boolean isWater(String biome) {
		return biome.contains("ocean") || biome.contains("river") || biome.contains("beach");
	}

	/** The range of heights over the (2 * reach + 1) squared samples round a cell, taken every {@code stride} cells. */
	private static int range(int[] hgt, int n, int i, int j, int reach, int stride) {
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		for (int dj = -reach; dj <= reach; dj++) {
			for (int di = -reach; di <= reach; di++) {
				int v = hgt[(j + dj * stride / 1) * n + i + di * stride / 1];
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
		}
		return hi - lo;
	}
}
