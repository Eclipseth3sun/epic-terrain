package com.epicterrain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Which vanilla biome does each mountain climate select? For inland ground, a table of temperature (across) against humidity (down) for
 * each erosion and each "peakiness" (the weirdness value), to see what the vanilla table can give a mountain at each height and how a
 * continent could steer it. Not run by default.
 */
@Tag("preview")
class MountainBiomeProbe {
	@Test
	void mountainBiomes() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();

		float[] temps = { -0.7f, -0.3f, 0.0f, 0.4f, 0.8f };
		float[] humidities = { -0.5f, -0.22f, 0.0f, 0.2f, 0.5f };
		float[] erosions = { -0.9f, -0.55f, -0.3f, -0.1f, 0.25f };
		float[] weirds = { 0.0f, 0.3f, 0.5f, 0.67f };
		if (Boolean.getBoolean("epicterrain.negative")) {
			erosions = new float[] { -0.9f, -0.55f, -0.3f };
			weirds = new float[] { -0.67f, -0.3f };
		}
		float cont = Float.parseFloat(System.getProperty("epicterrain.cont", "0.6"));
		System.out.println("[mtn] inland continentalness " + cont + ", depth 0. Columns are temperature -0.7 (frozen) -0.3 0.0 0.4 0.8 (hot); rows humidity -0.5 (dry) to 0.5 (wet)");
		for (float erosion : erosions) {
			for (float weird : weirds) {
				System.out.printf("[mtn] --- erosion %5.2f, weirdness %4.2f%n", erosion, weird);
				for (float humidity : humidities) {
					StringBuilder row = new StringBuilder(String.format("[mtn] hum %5.2f :", humidity));
					for (float temp : temps) {
						Climate.TargetPoint p = Climate.target(temp, humidity, cont, erosion, 0.0f, weird);
						String name = source.getNoiseBiome(p).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
						row.append(String.format(" %-22s", name));
					}
					System.out.println(row);
				}
			}
		}
	}
}
