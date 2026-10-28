package com.epicterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

class WorldgenLoadTest {
	@Test
	void vanillaOverworldSamplesAtSeaLevelNearOrigin() {
		WorldgenHarness harness = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = harness.settings("minecraft:overworld").orElseThrow();
		RandomState state = harness.randomState(1234L, settings);
		float surface = state.sampleBlockValueUncached(settings.noiseRouter().chunkSurfaceLevel(), 0, 0, 0);
		System.out.println("vanilla chunk_surface_level at origin = " + surface);
		assertTrue(surface > -64 && surface < 320, "surface estimate out of range: " + surface);
		assertEquals(63, settings.seaLevel());
	}

	@Test
	void epicWorldPresetLoadsWithTallDimension() {
		WorldgenHarness harness = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = harness.settings("epicterrain:overworld").orElseThrow();
		assertEquals(576, settings.noiseSettings().height());
		assertEquals(-64, settings.noiseSettings().minY());
		RandomState state = harness.randomState(1234L, settings);
		float surface = state.sampleBlockValueUncached(settings.noiseRouter().chunkSurfaceLevel(), 0, 0, 0);
		assertTrue(surface > -64 && surface < 512, "surface estimate out of range: " + surface);
	}
}
