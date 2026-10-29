package com.epicterrain.worldgen;

import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/**
 * Prints the places in vanilla's overworld biome table where given biomes are chosen, as ranges of each of the six numbers the table is asked with. Nothing happens unless
 * {@code -Depicterrain.tableDump=biome,biome...} is given (names without the {@code minecraft:}).
 */
@Tag("preview")
class BiomeTableDumpProbeTest {
	private static String range(Climate.Parameter p) {
		return String.format("%+.3f..%+.3f", Climate.unquantizeCoord(p.min()), Climate.unquantizeCoord(p.max()));
	}

	@Test
	void dump() {
		String wanted = System.getProperty("epicterrain.tableDump");
		if (wanted == null) {
			return;
		}
		Set<String> names = Set.of(wanted.split(","));
		WorldgenHarness harness = WorldgenHarness.shared();
		MultiNoiseBiomeSourceParameterList list = harness.registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
			.getOrThrow(ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, Identifier.parse("minecraft:overworld"))).value();
		System.out.println("table entries: " + list.parameters().values().size());
		for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : list.parameters().values()) {
			String id = entry.getSecond().unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			if (!names.contains(id)) {
				continue;
			}
			Climate.ParameterPoint p = entry.getFirst();
			System.out.printf("%-24s temp %s hum %s cont %s eros %s depth %s weird %s%n", id, range(p.temperature()), range(p.humidity()), range(p.continentalness()), range(p.erosion()),
				range(p.depth()), range(p.weirdness()));
		}
	}
}
