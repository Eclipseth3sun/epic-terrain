package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.epicterrain.WorldgenHarness;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * The biome tags for the highlights of the Earth world type, which are what {@code /locate biome #epicterrain:earth_...} searches for: vanilla's own
 * search, nothing of ours. They are made of vanilla biomes, which is what the Earth biome mapping answers with.
 */
class EarthTagsTest {
	private static final Path TAGS = Paths.get(System.getProperty("epicterrain.resourcesDir", "src/main/resources"), "data", "epicterrain", "tags", "worldgen", "biome");

	private static TagKey<Biome> tag(String id) {
		return TagKey.create(Registries.BIOME, Identifier.parse(id));
	}

	private static Holder.Reference<Biome> biome(String id) {
		HolderLookup.RegistryLookup<Biome> biomes = WorldgenHarness.shared().registries.lookupOrThrow(Registries.BIOME);
		return biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
	}

	private static List<Path> earthTags() throws IOException {
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(TAGS, "earth_*.json")) {
			files.forEach(out::add);
		}
		return out;
	}

	@Test
	void everyEarthTagIsMadeOfRealBiomesAndTagsAndIsNotEmpty() throws IOException {
		HolderLookup.RegistryLookup<Biome> biomes = WorldgenHarness.shared().registries.lookupOrThrow(Registries.BIOME);
		List<Path> tags = earthTags();
		assertTrue(tags.size() >= 10, "the highlight tags exist: " + tags);
		for (Path file : tags) {
			JsonArray values = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("values");
			assertTrue(values.size() > 0, file.getFileName() + " has members");
			for (JsonElement value : values) {
				String entry = value.getAsString();
				if (entry.startsWith("#")) {
					assertTrue(biomes.get(tag(entry.substring(1))).isPresent(), file.getFileName() + " refers to a tag that does not exist: " + entry);
				} else {
					assertTrue(biomes.get(ResourceKey.create(Registries.BIOME, Identifier.parse(entry))).isPresent(), file.getFileName() + " refers to a biome that does not exist: " + entry);
				}
			}
			String name = file.getFileName().toString().replace(".json", "");
			long members = biomes.listElements().filter(h -> h.is(tag("epicterrain:" + name))).count();
			assertTrue(members > 0, "#epicterrain:" + name + " resolves to at least one biome");
		}
	}

	@Test
	void theHighlightsHoldTheBiomesTheyShould() {
		assertTrue(biome("minecraft:mushroom_fields").is(tag("epicterrain:earth_mushroom_island")));
		assertEquals(1, WorldgenHarness.shared().registries.lookupOrThrow(Registries.BIOME).listElements().filter(h -> h.is(tag("epicterrain:earth_mushroom_island"))).count(), "only mushroom fields");

		for (String peak : new String[] { "jagged_peaks", "frozen_peaks", "stony_peaks" }) {
			assertTrue(biome("minecraft:" + peak).is(tag("epicterrain:earth_peaks")), peak + " is a peak");
			assertTrue(biome("minecraft:" + peak).is(tag("epicterrain:earth_mountains")), peak + " is a mountain");
		}
		assertTrue(biome("minecraft:snowy_slopes").is(tag("epicterrain:earth_mountains")));
		assertTrue(biome("minecraft:grove").is(tag("epicterrain:earth_mountains")));
		assertFalse(biome("minecraft:snowy_slopes").is(tag("epicterrain:earth_peaks")), "slopes are not peaks");

		// the highlands are vanilla's whole mountain band: the lower meadows and cherry groves too, which the mountains tag leaves out
		assertTrue(biome("minecraft:meadow").is(tag("epicterrain:earth_highlands")));
		assertTrue(biome("minecraft:cherry_grove").is(tag("epicterrain:earth_highlands")));
		assertFalse(biome("minecraft:meadow").is(tag("epicterrain:earth_mountains")));
		assertTrue(biome("minecraft:jagged_peaks").is(tag("epicterrain:earth_highlands")));

		assertTrue(biome("minecraft:deep_ocean").is(tag("epicterrain:earth_open_ocean")));
		assertTrue(biome("minecraft:deep_frozen_ocean").is(tag("epicterrain:earth_open_ocean")));
		assertFalse(biome("minecraft:ocean").is(tag("epicterrain:earth_open_ocean")), "a shelf sea is not the open ocean");
		assertTrue(biome("minecraft:badlands").is(tag("epicterrain:earth_badlands")));
		assertTrue(biome("minecraft:eroded_badlands").is(tag("epicterrain:earth_badlands")));

		// the island features have biomes of their own (clones of a vanilla biome each), in their tags and in the vanilla tags of what they stand in for
		for (String feature : new String[] { "volcano", "atoll", "crater_lake" }) {
			assertTrue(biome("epicterrain:" + feature).is(tag("epicterrain:earth_" + feature)), feature + " is in its own tag");
			assertTrue(biome("epicterrain:" + feature).is(tag("epicterrain:earth_islands")), feature + " is an island feature");
			assertTrue(biome("epicterrain:" + feature).is(tag("minecraft:is_overworld")), feature + " is an overworld biome");
		}
		assertTrue(biome("minecraft:mushroom_fields").is(tag("epicterrain:earth_islands")));
		assertTrue(biome("epicterrain:volcano").is(tag("minecraft:is_mountain")), "a volcano is a mountain biome, as the stony peaks it is cloned from");
		assertTrue(biome("epicterrain:volcano").is(tag("epicterrain:earth_mountains")));
		assertTrue(biome("epicterrain:volcano").is(tag("epicterrain:earth_highlands")));
		assertTrue(biome("epicterrain:volcano").is(tag("epicterrain:earth_peaks")), "a volcano is a copy of the stony peaks, which are in the peaks, so it is a peak too");
		assertTrue(biome("epicterrain:atoll").is(tag("minecraft:is_beach")), "an atoll is a beach for everything that asks");
		assertTrue(biome("epicterrain:crater_lake").is(tag("minecraft:is_river")), "a crater lake is fresh running water for everything that asks");
		assertFalse(biome("minecraft:beach").is(tag("epicterrain:earth_islands")));
		assertFalse(biome("minecraft:river").is(tag("epicterrain:earth_islands")));

		for (String ordinary : new String[] { "plains", "forest", "beach", "river", "desert" }) {
			for (String highlight : new String[] { "earth_mushroom_island", "earth_peaks", "earth_mountains", "earth_open_ocean", "earth_badlands", "earth_islands" }) {
				assertFalse(biome("minecraft:" + ordinary).is(tag("epicterrain:" + highlight)), ordinary + " is not in " + highlight);
			}
		}
	}
}
