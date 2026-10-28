package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import com.epicterrain.WorldgenHarness;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

class EpicChunkGeneratorTest {
	private static final long SEED = 4242L;

	private static Holder.Reference<NoiseGeneratorSettings> baseSettings(WorldgenHarness h) {
		return h.registries.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.parse("epicterrain:overworld")));
	}

	private static BiomeSource biomeSource(WorldgenHarness h) {
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:epic"))).value();
		return preset.overworld().orElseThrow().generator().getBiomeSource();
	}

	private static DensityFunction registered(WorldgenHarness h, String id) {
		return h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id))).value();
	}

	@Test
	void percentageIsTheChanceThatARangeSlotIsFilled() {
		float previous = 0.0f;
		for (int percent = 1; percent <= 100; percent++) {
			float occupancy = EpicAmount.occupancy(percent);
			assertTrue(occupancy > previous, "occupancy must rise with the slider: " + percent + " -> " + occupancy);
			previous = occupancy;
		}
		assertEquals(0.20f, EpicAmount.occupancy(EpicAmount.DEFAULT), 1.0e-6f);
		assertEquals(1.0f, EpicAmount.occupancy(100), 1.0e-6f);
	}

	@Test
	void amountIsSavedAndRestored() {
		WorldgenHarness h = WorldgenHarness.shared();
		EpicChunkGenerator generator = new EpicChunkGenerator(biomeSource(h), baseSettings(h), 37);

		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, h.registries);
		JsonElement json = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
		System.out.println("encoded generator: " + json);
		assertEquals("epicterrain:epic_noise", json.getAsJsonObject().get("type").getAsString());
		assertEquals(37, json.getAsJsonObject().get("amount").getAsInt());
		assertEquals("epicterrain:overworld", json.getAsJsonObject().get("settings").getAsString());

		ChunkGenerator restored = ChunkGenerator.CODEC.parse(ops, json).getOrThrow();
		assertEquals(37, assertInstanceOf(EpicChunkGenerator.class, restored).amount());
	}

	@Test
	void sliderControlsHowMuchOfTheWorldIsEpic() {
		WorldgenHarness h = WorldgenHarness.shared();
		Holder.Reference<NoiseGeneratorSettings> base = baseSettings(h);
		Holder.Reference<DensityFunction> upliftHolder = h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("epicterrain:range/uplift")));
		DensityFunction cont = registered(h, "minecraft:overworld/continents");

		double previousLand = -1;
		for (int percent : new int[] { 1, 5, 20, 50, 80, 100 }) {
			NoiseGeneratorSettings derived = EpicChunkGenerator.derive(base.value(), percent);
			RandomState state = h.randomState(SEED, derived);
			// The same substitution, applied to the uplift function itself so we can measure it directly.
			DensityFunction uplift = new ConfigSubstitution(EpicChunkGenerator.RANGE_OCCUPANCY,
				new net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction(EpicAmount.occupancy(percent)))
				.rewrite(new DensityFunctions.HolderHolder(upliftHolder));

			Random random = new Random(21);
			int land = 0;
			int touched = 0;
			int hills = 0;
			int mountains = 0;
			for (int i = 0; i < 6000; i++) {
				int x = random.nextInt(400_000) - 200_000;
				int z = random.nextInt(400_000) - 200_000;
				if (state.sampleBlockValueUncached(cont, x, 0, z) <= -0.11f) {
					continue;
				}
				land++;
				float u = state.sampleBlockValueUncached(uplift, x, 0, z);
				touched += u > 1 ? 1 : 0;
				hills += u > 40 ? 1 : 0;
				mountains += u > 120 ? 1 : 0;
			}
			double touchedShare = 100.0 * touched / land;
			System.out.printf("slider %3d%%: %5.1f%% of land touched, %5.1f%% hills (>40), %5.1f%% mountains (>120)%n", percent, touchedShare, 100.0 * hills / land, 100.0 * mountains / land);
			assertTrue(touchedShare >= previousLand, "coverage must not shrink as the slider rises");
			previousLand = touchedShare;
		}
		assertTrue(previousLand > 40, "100% should cover a large share of land (with vanilla gaps between ranges), got " + previousLand + "%");
	}
}
