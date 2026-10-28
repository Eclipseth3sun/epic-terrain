package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.river.River;
import com.epicterrain.river.RiverService;
import com.epicterrain.river.RiverTestWorld;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** The epic river biomes: that they exist, that zones pick them, and that real planned corridors carry them. */
class RiverBiomesTest {
	private static final long SEED = 4242L;
	private static final List<String> RIVERS = List.of("epic_river", "epic_frozen_river", "epic_grand_river", "epic_frozen_grand_river");

	private static Holder<Biome> biome(WorldgenHarness h, String id) {
		return h.registries.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
	}

	private static TagKey<Biome> tag(String id) {
		return TagKey.create(Registries.BIOME, Identifier.parse(id));
	}

	private static String name(Holder<Biome> biome) {
		return biome.unwrapKey().orElseThrow().identifier().toString();
	}

	private static EpicBiomeSource epicSource(WorldgenHarness h) {
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:epic"))).value();
		BiomeSource source = preset.overworld().orElseThrow().generator().getBiomeSource();
		return (EpicBiomeSource) source;
	}

	@Test
	void riverBiomesAreRegisteredTaggedAndReachable() {
		WorldgenHarness h = WorldgenHarness.shared();
		EpicBiomeSource source = epicSource(h);
		for (String id : RIVERS) {
			Holder<Biome> epic = biome(h, "epicterrain:" + id);
			assertTrue(source.possibleBiomes().contains(epic), id + " must be reachable so /locate biome can find it");
			assertTrue(epic.is(tag("epicterrain:epic_rivers")), id + " in #epic_rivers");
			assertTrue(epic.is(tag("epicterrain:epic_terrain")), id + " in #epic_terrain");
			assertTrue(epic.is(tag("minecraft:is_river")), id + " must count as a river for everything that asks");
			assertTrue(epic.is(tag("minecraft:is_overworld")), id + " in #is_overworld");
			assertTrue(epic.is(tag(id.contains("grand") ? "epicterrain:epic_grand_river" : "epicterrain:epic_river")), id + " in its own tag");
			assertFalse(epic.is(tag(id.contains("grand") ? "epicterrain:epic_river" : "epicterrain:epic_grand_river")), id + " not in the other kind's tag");
		}
	}

	/** Each river biome is its vanilla river exactly, apart from the feature list, so water, mobs and colours are as expected. */
	@Test
	void riverBiomesAreClonesOfVanillasRivers() {
		WorldgenHarness h = WorldgenHarness.shared();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, h.registries);
		for (String id : RIVERS) {
			Holder<Biome> base = biome(h, id.contains("frozen") ? "minecraft:frozen_river" : "minecraft:river");
			Holder<Biome> epic = biome(h, "epicterrain:" + id);
			JsonObject a = Biome.DIRECT_CODEC.encodeStart(ops, base.value()).getOrThrow().getAsJsonObject();
			JsonObject b = Biome.DIRECT_CODEC.encodeStart(ops, epic.value()).getOrThrow().getAsJsonObject();
			a.remove("features");
			b.remove("features");
			assertEquals(a, b, id + " should differ from its vanilla river only in its features");
			// and it runs the channel feature first, before anything else decorates the chunk
			List<Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature>> first = epic.value().getGenerationSettings().features().get(0).stream().toList();
			assertEquals(1, first.size());
			assertEquals("epicterrain:river_channel", first.get(0).unwrapKey().orElseThrow().identifier().toString());
			Set<TagKey<Biome>> baseTags = base.tags().collect(Collectors.toSet());
			Set<TagKey<Biome>> epicTags = epic.tags().collect(Collectors.toSet());
			for (TagKey<Biome> tag : baseTags) {
				assertTrue(epicTags.contains(tag), id + " is missing " + tag.location() + ", which its vanilla river is in");
			}
		}
	}

	@Test
	void riverZonesPickTheRightRiverBiome() {
		WorldgenHarness h = WorldgenHarness.shared();
		EpicBiomeSource source = epicSource(h);
		// ask the source directly with a hand-made climate point: a mid-continent point whose vanilla answer we choose by temperature
		Climate.TargetPoint cold = Climate.target(-0.8f + 8.0f * 4, 0.0f, 0.5f, 0.2f, 0.0f, 0.1f);
		Climate.TargetPoint mild = Climate.target(0.0f + 8.0f * 4, 0.0f, 0.5f, 0.2f, 0.0f, 0.1f);
		assertEquals("epicterrain:epic_frozen_river", name(source.resolve(cold)));
		assertEquals("epicterrain:epic_river", name(source.resolve(mild)));
		assertEquals("epicterrain:epic_frozen_grand_river", name(source.resolve(Climate.target(-0.8f + 8.0f * 5, 0.0f, 0.5f, 0.2f, 0.0f, 0.1f))));
		assertEquals("epicterrain:epic_grand_river", name(source.resolve(Climate.target(0.0f + 8.0f * 5, 0.0f, 0.5f, 0.2f, 0.0f, 0.1f))));
		// the ocean, whatever the zone, stays an ocean: a river corridor is only ever labelled over dry ground
		Climate.TargetPoint ocean = Climate.target(0.0f + 8.0f * 4, 0.0f, -0.7f, 0.2f, 0.0f, 0.1f);
		assertTrue(name(source.resolve(ocean)).contains("ocean"), "got " + name(source.resolve(ocean)));
	}

	/** Real corridors: the labels sit exactly where the planned rivers are, and nowhere else. */
	@Test
	void plannedCorridorsCarryTheirLabelsAndOpenCountryDoesNot() {
		WorldgenHarness h = WorldgenHarness.shared();
		RiverTestWorld world = RiverTestWorld.create(SEED, 60);
		RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
		assertNotNull(service, "the epic settings must contain a river field");
		DensityFunction zone = world.registeredHere(h, "epicterrain:river/zone");
		EpicBiomeSource source = epicSource(h);
		Climate.Sampler climate = world.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);

		// plan a patch of the world and look at what the labels say along its rivers
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -1; cz <= 1; cz++) {
				service.ensureReady(cx * world.catalog.cellSize() + 100, cz * world.catalog.cellSize() + 100, true);
			}
		}
		List<River> rivers = service.finishedPlans().stream().flatMap(p -> p.rivers().stream()).toList();
		assertTrue(rivers.size() > 10, "plenty of rivers to look at: " + rivers.size());

		int dry = 0;
		int labelledDry = 0;
		int epicBiome = 0;
		for (River river : rivers) {
			for (int i = 0; i < river.nodes().size(); i += 4) {
				River.Node node = river.nodes().get(i);
				// the label looks at the middle of a 4 x 4 biome cell
				int x = ((int) Math.floor(node.x())) & ~3;
				int z = ((int) Math.floor(node.z())) & ~3;
				float value = world.state.sampleBlockValueUncached(zone, x, 0, z);
				if (world.terrain.ground(x + 2.0, z + 2.0) <= 61) {
					assertEquals(0.0f, value, "no label over vanilla water, at " + x + "," + z);
					continue;
				}
				dry++;
				if (value > 0.0f) {
					labelledDry++;
					assertTrue(value == RiverField.TRIBUTARY_ZONE || value == RiverField.GRAND_ZONE, "a zone code, got " + value);
					Holder<Biome> label = source.resolve(climate.sample(x >> 2, ((int) node.ground() + 2) >> 2, z >> 2)); // at the surface
					if (label.is(tag("epicterrain:epic_rivers"))) {
						epicBiome++;
					}
				}
			}
		}
		System.out.printf("[rivers] %d river nodes on dry ground: %d labelled, %d resolved to an epic river biome%n", dry, labelledDry, epicBiome);
		assertTrue(dry > 100, "nodes on dry ground: " + dry);
		assertTrue(labelledDry >= dry * 0.97, "river corridors must be labelled: " + labelledDry + " of " + dry);
		assertTrue(epicBiome >= labelledDry * 0.9, "labelled corridors must resolve to epic river biomes: " + epicBiome + " of " + labelledDry);

		// far from every river there is no label at all
		int far = 0;
		java.util.Random random = new java.util.Random(5);
		int checked = 0;
		while (checked < 400) {
			int x = random.nextInt(18_000) - 9_000;
			int z = random.nextInt(18_000) - 9_000;
			service.ensureReady(x, z, true);
			RiverService.ColumnRiver near = service.at(x + 2.0, z + 2.0);
			float value = world.state.sampleBlockValueUncached(zone, x & ~3, 0, z & ~3);
			if (near == null) {
				assertEquals(0.0f, value, "no river reaches here, so there is no label at " + x + "," + z);
				far++;
			}
			checked++;
		}
		assertTrue(far > 300, "most of the world is open country: " + far);
	}
}
