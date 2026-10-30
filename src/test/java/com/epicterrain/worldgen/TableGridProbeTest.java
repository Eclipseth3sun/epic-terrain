package com.epicterrain.worldgen;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * What vanilla's overworld table answers for a grid of temperatures and humidities at given continentalness, erosion and weirdness, to see which biome families each erosion
 * band holds. Nothing happens unless {@code -Depicterrain.tableGrid=CONTINENTALNESS,WEIRDNESS,EROSION[;EROSION...]} is given.
 */
@Tag("preview")
class TableGridProbeTest {
	@Test
	void grid() {
		String spec = System.getProperty("epicterrain.tableGrid");
		if (spec == null) {
			return;
		}
		String[] parts = spec.split(";");
		String[] first = parts[0].split(",");
		float continentalness = Float.parseFloat(first[0]);
		float weirdness = Float.parseFloat(first[1]);
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		float[] temps = { -0.7f, -0.3f, 0.0f, 0.4f, 0.8f };
		float[] hums = { -0.6f, -0.2f, 0.0f, 0.2f, 0.6f };
		for (int e = 0; e < parts.length; e++) {
			float erosion = Float.parseFloat(e == 0 ? first[2] : parts[e]);
			System.out.printf("continentalness %.2f weirdness %.2f erosion %.2f (rows: humidity %s; columns: temperature %s)%n", continentalness, weirdness, erosion, java.util.Arrays.toString(hums),
				java.util.Arrays.toString(temps));
			for (float hum : hums) {
				StringBuilder row = new StringBuilder("   ");
				for (float t : temps) {
					String name = source.getNoiseBiome(Climate.target(t, hum, continentalness, erosion, 0.0f, weirdness)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
					row.append(String.format("%-27s", name));
				}
				System.out.println(row);
			}
		}
	}
}
