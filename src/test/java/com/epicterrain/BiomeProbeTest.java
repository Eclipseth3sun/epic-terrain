package com.epicterrain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Scratch calibration: which vanilla biome does each climate point select? Not run by default. */
@Tag("preview")
class BiomeProbeTest {
	@Test
	void biomeResponse() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();

		float[] temps = { -0.7f, -0.3f, 0.0f, 0.4f, 0.8f };
		float[] erosions = { -0.9f, -0.55f, -0.3f, -0.1f, 0.25f };
		float[] weirds = { -0.67f, -0.3f, 0.0f, 0.3f, 0.48f, 0.67f, 0.85f };
		for (float cont : new float[] { 0.5f, 0.15f }) {
			System.out.println("[biome] === continentalness " + cont + " (humidity 0, depth 0) ===");
			for (float erosion : erosions) {
				for (float weird : weirds) {
					StringBuilder row = new StringBuilder(String.format("[biome] ero=%5.2f w=%5.2f :", erosion, weird));
					for (float temp : temps) {
						Climate.TargetPoint p = Climate.target(temp, 0.0f, cont, erosion, 0.0f, weird);
						String name = source.getNoiseBiome(p).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
						row.append(String.format(" %-18s", name));
					}
					System.out.println(row);
				}
			}
		}
	}
}
