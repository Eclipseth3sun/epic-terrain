package com.epicterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import org.junit.jupiter.api.Test;

/** Exercises the real column-iteration path the game uses for terrain (interpolated cells and all). */
class ChunkGeneratorTest {
	private static final long SEED = 4242L;

	private static NoiseBasedChunkGenerator generator(WorldgenHarness h, String settingsId, String presetId) {
		NoiseGeneratorSettings settings = h.settings(settingsId).orElseThrow();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse(presetId))).value();
		BiomeSource biomes = preset.overworld().orElseThrow().generator().getBiomeSource();
		return new NoiseBasedChunkGenerator(biomes, Holder.direct(settings));
	}

	@Test
	void summitIsTallButInsideTheWorld() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("epicterrain:overworld").orElseThrow();
		RandomState state = h.randomState(SEED, settings);
		NoiseBasedChunkGenerator generator = generator(h, "epicterrain:overworld", "epicterrain:epic");
		LevelHeightAccessor world = LevelHeightAccessor.create(-64, 576);

		// Find this seed's tallest range by scanning the cheap 2D uplift field, then ask the real generator how high it is.
		DensityFunction uplift = h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("epicterrain:range/uplift"))).value();
		float bestUplift = 0;
		int bestX = 0;
		int bestZ = 0;
		for (int x = -40_000; x <= 40_000; x += 128) {
			for (int z = -40_000; z <= 40_000; z += 128) {
				float u = state.sampleBlockValueUncached(uplift, x, 0, z);
				if (u > bestUplift) {
					bestUplift = u;
					bestX = x;
					bestZ = z;
				}
			}
		}
		int best = Integer.MIN_VALUE;
		for (int dx = -192; dx <= 192; dx += 32) {
			for (int dz = -192; dz <= 192; dz += 32) {
				best = Math.max(best, generator.getBaseHeight(bestX + dx, bestZ + dz, Heightmap.Types.WORLD_SURFACE_WG, world, state));
			}
		}
		System.out.println("tallest range in 80k x 80k: uplift " + bestUplift + " at (" + bestX + "," + bestZ + "), surface y=" + best);
		assertTrue(best > 330, "expected a tall summit, got y=" + best);
		assertTrue(best <= 490, "summits should top out around y=480, got y=" + best);
	}

	@Test
	void originAreaMatchesVanillaHeight() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings epic = h.settings("epicterrain:overworld").orElseThrow();
		NoiseGeneratorSettings vanilla = h.settings("minecraft:overworld").orElseThrow();
		RandomState epicState = h.randomState(SEED, epic);
		RandomState vanillaState = h.randomState(SEED, vanilla);
		NoiseBasedChunkGenerator epicGenerator = generator(h, "epicterrain:overworld", "epicterrain:epic");
		NoiseBasedChunkGenerator vanillaGenerator = generator(h, "minecraft:overworld", "minecraft:normal");

		// (0,0) sits well outside any range for this seed (see the preview maps).
		for (int x = -128; x <= 128; x += 32) {
			for (int z = -128; z <= 128; z += 32) {
				int expected = vanillaGenerator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, LevelHeightAccessor.create(-64, 384), vanillaState);
				int actual = epicGenerator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, LevelHeightAccessor.create(-64, 576), epicState);
				assertEquals(expected, actual, "surface height differs at " + x + "," + z);
			}
		}
	}
}
