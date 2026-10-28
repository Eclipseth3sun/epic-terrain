package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.epicterrain.WorldgenHarness;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

class EpicBiomeSourceTest {
	private static final long SEED = 4242L;
	private static final List<String> FOOTHILLS = List.of("foothills_cold", "foothills_temperate", "foothills_dry", "foothills_wet");

	private static EpicBiomeSource epicSource(WorldgenHarness h) {
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:epic"))).value();
		return assertInstanceOf(EpicBiomeSource.class, preset.overworld().orElseThrow().generator().getBiomeSource());
	}

	private static BiomeSource vanillaSource(WorldgenHarness h) {
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		return assertInstanceOf(MultiNoiseBiomeSource.class, preset.overworld().orElseThrow().generator().getBiomeSource());
	}

	private static Holder<Biome> biome(WorldgenHarness h, String id) {
		return h.registries.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
	}

	private static TagKey<Biome> tag(String id) {
		return TagKey.create(Registries.BIOME, Identifier.parse(id));
	}

	private static String name(Holder<Biome> biome) {
		return biome.unwrapKey().orElseThrow().identifier().toString();
	}

	private static DensityFunction uplift(WorldgenHarness h) {
		return h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("epicterrain:range/uplift"))).value();
	}

	/** The river zone (0 outside every river's corridor), for tests that must leave river corridors out of a comparison with vanilla. */
	private static DensityFunction riverZone(WorldgenHarness h) {
		return h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("epicterrain:river/zone"))).value();
	}

	@Test
	void zoneMarkerRoundTrips() {
		for (int zone = 0; zone <= 5; zone++) {
			for (float temperature : new float[] { -1.4f, -0.7f, 0.0f, 0.55f, 1.3f }) {
				float marked = temperature + EpicBiomeSource.ZONE_STEP * zone;
				assertEquals(zone, EpicBiomeSource.zoneOf(marked), "zone for temperature " + temperature);
				assertEquals(temperature, EpicBiomeSource.temperatureOf(marked), 1.0e-5f);
			}
		}
	}

	@Test
	void epicSummitBiomesAreRegisteredAndTagged() {
		WorldgenHarness h = WorldgenHarness.shared();
		EpicBiomeSource source = epicSource(h);
		Holder<Biome> range = biome(h, "epicterrain:epic_range_peaks");
		Holder<Biome> wall = biome(h, "epicterrain:epic_wall_peaks");
		for (Holder<Biome> epic : List.of(range, wall)) {
			assertTrue(source.possibleBiomes().contains(epic), name(epic) + " must be reachable so /locate biome can find it");
			assertTrue(epic.is(tag("epicterrain:epic_mountain")), name(epic) + " in #epic_mountain");
			assertTrue(epic.is(tag("epicterrain:epic_terrain")), name(epic) + " in #epic_terrain");
			assertTrue(epic.is(tag("minecraft:is_mountain")), name(epic) + " in #minecraft:is_mountain");
			assertTrue(epic.is(tag("minecraft:is_overworld")), name(epic) + " in #minecraft:is_overworld");
		}
		assertTrue(range.is(tag("epicterrain:epic_range")));
		assertFalse(range.is(tag("epicterrain:epic_wall")));
		assertTrue(wall.is(tag("epicterrain:epic_wall")));
		assertFalse(wall.is(tag("epicterrain:epic_range")));
	}

	@Test
	void foothillBiomesAreRegisteredTaggedAndReachable() {
		WorldgenHarness h = WorldgenHarness.shared();
		EpicBiomeSource source = epicSource(h);
		for (String id : FOOTHILLS) {
			Holder<Biome> foothill = biome(h, "epicterrain:" + id);
			assertTrue(source.possibleBiomes().contains(foothill), id + " must be reachable so /locate biome can find it");
			assertTrue(foothill.is(tag("epicterrain:epic_foothills")), id + " in #epic_foothills");
			assertTrue(foothill.is(tag("epicterrain:epic_terrain")), id + " in #epic_terrain");
			assertTrue(foothill.is(tag("minecraft:is_overworld")), id + " in #minecraft:is_overworld");
		}
	}

	/** Each foothill biome is built on a vanilla biome with the right climate, and must behave like it in every vanilla tag. */
	@Test
	void foothillBiomesKeepTheirBaseBiomesClimateAndTags() {
		WorldgenHarness h = WorldgenHarness.shared();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, h.registries);
		Map<String, String> bases = Map.of("foothills_cold", "grove", "foothills_temperate", "meadow",
			"foothills_dry", "savanna_plateau", "foothills_wet", "sparse_jungle");
		for (Map.Entry<String, String> pair : bases.entrySet()) {
			Holder<Biome> foothill = biome(h, "epicterrain:" + pair.getKey());
			Holder<Biome> base = biome(h, "minecraft:" + pair.getValue());

			// Everything except the feature list is identical (climate, mobs, colours, sounds).
			JsonObject a = Biome.DIRECT_CODEC.encodeStart(ops, base.value()).getOrThrow().getAsJsonObject();
			JsonObject b = Biome.DIRECT_CODEC.encodeStart(ops, foothill.value()).getOrThrow().getAsJsonObject();
			a.remove("features");
			b.remove("features");
			assertEquals(a, b, pair.getKey() + " should differ from " + pair.getValue() + " only in its features");

			Set<TagKey<Biome>> baseTags = base.tags().collect(Collectors.toSet());
			Set<TagKey<Biome>> foothillTags = foothill.tags().collect(Collectors.toSet());
			for (TagKey<Biome> tag : baseTags) {
				assertTrue(foothillTags.contains(tag), pair.getKey() + " is missing " + tag.location() + ", which " + pair.getValue() + " is in");
			}
		}
	}

	/** Minecraft refuses to start a world whose biomes list features in conflicting orders; run the same check. */
	@Test
	void everyBiomeInTheEpicWorldAgreesOnFeatureOrder() {
		WorldgenHarness h = WorldgenHarness.shared();
		List<Holder<Biome>> biomes = List.copyOf(epicSource(h).possibleBiomes());
		assertTrue(biomes.size() > 50);
		FeatureSorter.buildFeaturesPerStep(biomes, biome -> biome.value().getGenerationSettings().features(), true);
	}

	/** Same promise as the terrain parity test, for biomes: outside every range an epic world has vanilla's biomes. */
	@Test
	void biomesMatchVanillaOutsideRanges() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings vanilla = h.settings("minecraft:overworld").orElseThrow();
		NoiseGeneratorSettings epic = h.settings("epicterrain:overworld").orElseThrow();
		RandomState vanillaState = h.randomState(SEED, vanilla);
		RandomState epicState = h.randomState(SEED, epic);
		BiomeResolver vanillaBiomes = vanillaSource(h).createCachingResolver(vanillaState);
		BiomeResolver epicBiomes = epicSource(h).createCachingResolver(epicState);
		DensityFunction uplift = uplift(h);
		DensityFunction riverZone = riverZone(h);

		Random random = new Random(33);
		int compared = 0;
		while (compared < 3000) {
			int x = random.nextInt(200_000) - 100_000;
			int z = random.nextInt(200_000) - 100_000;
			if (epicState.sampleBlockValueUncached(uplift, x, 0, z) > 0.0f || epicState.sampleBlockValueUncached(uplift, x + 64, 0, z + 64) > 0.0f) {
				continue;
			}
			if (epicState.sampleBlockValueUncached(riverZone, x & ~3, 0, z & ~3) > 0.0f) {
				continue; // inside a river's corridor, which is labelled epic (a range's rivers can run far beyond the range)
			}
			int y = -64 + random.nextInt(384);
			Holder<Biome> expected = vanillaBiomes.getNoiseBiome(x >> 2, y >> 2, z >> 2);
			Holder<Biome> actual = epicBiomes.getNoiseBiome(x >> 2, y >> 2, z >> 2);
			assertEquals(name(expected), name(actual), "biome differs at " + x + "," + y + "," + z);
			compared++;
		}
	}

	/**
	 * Everything a range touches is labelled epic. The only exceptions are biomes with no foothill counterpart on purpose
	 * (oceans, rivers, beaches, underground biomes) and vanilla's own natural peaks under a range's faint outer fringe.
	 */
	@Test
	void everythingARangeTouchesIsLabelledEpic() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings epic = h.settings("epicterrain:overworld").orElseThrow();
		RandomState state = h.randomState(SEED, epic);
		EpicBiomeSource source = epicSource(h);
		Climate.Sampler climate = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		DensityFunction uplift = uplift(h);
		Set<String> allowedUnlabelled = Set.of("ocean", "river", "beach", "caves", "deep_dark", "mushroom", "stony_shore");

		Random random = new Random(78);
		Map<String, Integer> unlabelled = new TreeMap<>();
		int labelled = 0;
		int checked = 0;
		while (checked < 3000) {
			int x = (random.nextInt(160_000) - 80_000) & ~3; // biomes are sampled on a 4-block grid
			int z = (random.nextInt(160_000) - 80_000) & ~3;
			float u = state.sampleBlockValueUncached(uplift, x, 0, z);
			if (u < 3.0f) {
				continue;
			}
			int y = 40 + random.nextInt(400);
			Holder<Biome> result = source.resolve(climate.sample(x >> 2, y >> 2, z >> 2));
			checked++;
			if (result.is(tag("epicterrain:epic_terrain"))) {
				labelled++;
			} else {
				String path = result.unwrapKey().orElseThrow().identifier().getPath();
				unlabelled.merge(path, 1, Integer::sum);
				// A natural vanilla mountain under the faint outer fringe of a range stays vanilla; it is not our terrain.
				boolean naturalPeak = path.endsWith("_peaks") && u < 100.0f;
				assertTrue(naturalPeak || allowedUnlabelled.stream().anyMatch(path::contains),
					"land biome " + path + " inside a range (uplift " + u + ") was left unlabelled at " + x + "," + y + "," + z);
			}
		}
		System.out.println("labelled epic: " + labelled + " of " + checked + " sampled spots inside ranges; left as vanilla: " + unlabelled);
		assertTrue(labelled > checked * 0.8, "most of what a range touches should carry an epic label, got " + labelled + "/" + checked);
	}

	/** The foothill label may only relabel what vanilla chose; below the foothill threshold nothing changes at all. */
	@Test
	void foothillsRelabelVanillasChoiceAndNothingElse() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings epic = h.settings("epicterrain:overworld").orElseThrow();
		RandomState state = h.randomState(SEED, epic);
		EpicBiomeSource source = epicSource(h);
		Climate.Sampler climate = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		DensityFunction uplift = uplift(h);
		Set<Holder<Biome>> foothills = FOOTHILLS.stream().map(id -> biome(h, "epicterrain:" + id)).collect(Collectors.toSet());
		DensityFunction riverZone = riverZone(h);

		Random random = new Random(77);
		int relabelled = 0;
		int checked = 0;
		while (checked < 1500) {
			int x = (random.nextInt(160_000) - 80_000) & ~3; // biomes are sampled on a 4-block grid
			int z = (random.nextInt(160_000) - 80_000) & ~3;
			float u = state.sampleBlockValueUncached(uplift, x, 0, z);
			if (u <= 0.0f || u >= 100.0f) {
				continue; // outside every range, or in a summit zone
			}
			if (state.sampleBlockValueUncached(riverZone, x, 0, z) > 0.0f) {
				continue; // a river's corridor takes the label from the foothills
			}
			Climate.TargetPoint target = climate.sample(x >> 2, (-64 + random.nextInt(384)) >> 2, z >> 2);
			Holder<Biome> vanilla = source.vanillaBiome(target);
			Holder<Biome> actual = source.resolve(target);
			if (u < 3.0f) {
				assertEquals(name(vanilla), name(actual), "uplift " + u + " is below the foothill threshold, so vanilla's biome must stand");
			} else if (!actual.equals(vanilla)) {
				assertTrue(foothills.contains(actual), "relabelled to something that is not a foothill biome: " + name(actual));
				relabelled++;
			}
			checked++;
		}
		assertTrue(relabelled > 100, "expected plenty of labelled foothills, got " + relabelled);
	}

	@Test
	void foothillsMapToTheRightClimate() {
		WorldgenHarness h = WorldgenHarness.shared();
		EpicBiomeSource source = epicSource(h);
		// ask the source directly: what does the foothill zone (3) do to each of these vanilla biomes?
		Map<String, String> expected = Map.of(
			"minecraft:snowy_plains", "epicterrain:foothills_cold", "minecraft:snowy_taiga", "epicterrain:foothills_cold",
			"minecraft:forest", "epicterrain:foothills_temperate", "minecraft:plains", "epicterrain:foothills_temperate",
			"minecraft:desert", "epicterrain:foothills_dry", "minecraft:badlands", "epicterrain:foothills_dry",
			"minecraft:jungle", "epicterrain:foothills_wet", "minecraft:swamp", "epicterrain:foothills_wet");
		for (Map.Entry<String, String> pair : expected.entrySet()) {
			Holder<Biome> twin = source.foothillFor(biome(h, pair.getKey()));
			assertEquals(pair.getValue(), twin == null ? null : name(twin), "foothill for " + pair.getKey());
		}
		assertEquals(null, source.foothillFor(biome(h, "minecraft:ocean")), "oceans must keep their vanilla biome");
		assertEquals(null, source.foothillFor(biome(h, "minecraft:river")));
		assertEquals(null, source.foothillFor(biome(h, "minecraft:lush_caves")));
	}

	/** What /locate biome does, in miniature: look around for each kind of epic biome. */
	@Test
	void epicTerrainCanBeLocatedAndSummitsAreDistinguishedByKind() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings epic = h.settings("epicterrain:overworld").orElseThrow();
		RandomState state = h.randomState(SEED, epic);
		BiomeResolver resolver = epicSource(h).createCachingResolver(state);
		Holder<Biome> range = biome(h, "epicterrain:epic_range_peaks");
		Holder<Biome> wall = biome(h, "epicterrain:epic_wall_peaks");
		Set<Holder<Biome>> foothills = FOOTHILLS.stream().map(id -> biome(h, "epicterrain:" + id)).collect(Collectors.toSet());

		int rangeNearest = Integer.MAX_VALUE;
		int wallNearest = Integer.MAX_VALUE;
		int foothillNearest = Integer.MAX_VALUE;
		for (int x = -24_000; x <= 24_000; x += 64) {
			for (int z = -24_000; z <= 24_000; z += 64) {
				int distance = Math.max(Math.abs(x), Math.abs(z));
				Holder<Biome> up = resolver.getNoiseBiome(x >> 2, 380 >> 2, z >> 2);
				if (up.equals(range)) {
					rangeNearest = Math.min(rangeNearest, distance);
				} else if (up.equals(wall)) {
					wallNearest = Math.min(wallNearest, distance);
				}
				if (foothills.contains(resolver.getNoiseBiome(x >> 2, 100 >> 2, z >> 2))) {
					foothillNearest = Math.min(foothillNearest, distance);
				}
			}
		}
		System.out.println("nearest to spawn: foothill-range summit " + rangeNearest + ", wall-range summit " + wallNearest + ", foothills " + foothillNearest);
		assertTrue(rangeNearest < Integer.MAX_VALUE, "no foothill-range summit found");
		assertTrue(wallNearest < Integer.MAX_VALUE, "no wall-range summit found");
		assertTrue(foothillNearest < 6400, "#epicterrain:epic_foothills should be within /locate's default 6400-block search, got " + foothillNearest);
	}

	/** Worlds saved by the first build stored zones as bare ids; they must still load. */
	@Test
	void worldsSavedWithTheOldZoneFormatStillLoad() {
		WorldgenHarness h = WorldgenHarness.shared();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, h.registries);
		JsonElement old = JsonParser.parseString("""
			{"type": "epicterrain:epic_multi_noise", "base": {"preset": "minecraft:overworld"},
			 "replaces": "#epicterrain:replaced_by_epic_peaks",
			 "zones": ["epicterrain:epic_range_peaks", "epicterrain:epic_wall_peaks"]}""");
		EpicBiomeSource restored = assertInstanceOf(EpicBiomeSource.class, BiomeSource.CODEC.parse(ops, old).getOrThrow());
		assertTrue(restored.possibleBiomes().contains(biome(h, "epicterrain:epic_range_peaks")));
		assertTrue(restored.possibleBiomes().contains(biome(h, "epicterrain:epic_wall_peaks")));
	}

	@Test
	void presetBiomeSourceSurvivesASaveAndLoad() {
		WorldgenHarness h = WorldgenHarness.shared();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, h.registries);
		EpicBiomeSource original = epicSource(h);
		JsonElement json = BiomeSource.CODEC.encodeStart(ops, original).getOrThrow();
		EpicBiomeSource restored = assertInstanceOf(EpicBiomeSource.class, BiomeSource.CODEC.parse(ops, json).getOrThrow());
		assertEquals(original.possibleBiomes(), restored.possibleBiomes());
	}
}
