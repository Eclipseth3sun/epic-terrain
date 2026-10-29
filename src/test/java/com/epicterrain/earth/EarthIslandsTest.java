package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.Islands.Island;
import com.epicterrain.earth.Islands.Kind;
import com.epicterrain.earth.Islands.Origin;
import com.epicterrain.earth.Tectonics.Boundary;
import com.epicterrain.earth.Tectonics.Contact;
import com.epicterrain.earth.Tectonics.Plate;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** The islands: where they may stand, what drives them, how they are shaped, how often mushroom islands come, and what vanilla's table makes of them. */
class EarthIslandsTest {
	private static final double HALF = 120_000.0;
	private static final long[] SEEDS = { 4242L, 11L };

	private static Islands islands(long seed, Islands.Dials dials) {
		return new Islands(new Tectonics(EarthConfig.defaults(seed)), dials);
	}

	private static List<Island> all(Islands islands) {
		return new ArrayList<>(EarthIslandPreviewTest.islandsIn(islands, 0.0, 0.0, HALF).values());
	}

	@Test
	void rememberingTheIslandsOfAPointDoesNotChangeTheAnswer() {
		Islands islands = islands(4242L, Islands.Dials.defaults());
		java.util.Random random = new java.util.Random(5);
		int n = 6_000;
		double[] xs = new double[n];
		double[] zs = new double[n];
		for (int i = 0; i < n; i++) {
			xs[i] = (random.nextDouble() - 0.5) * 2.0 * HALF;
			zs[i] = (random.nextDouble() - 0.5) * 2.0 * HALF;
			if (i % 3 == 0) {
				xs[i] = Math.rint(xs[i]); // block columns, as the game asks
				zs[i] = Math.rint(zs[i]);
			}
		}
		java.util.function.Function<List<Island>, Set<Long>> keys = list -> {
			Set<Long> out = new HashSet<>();
			for (Island is : list) {
				out.add(is.key());
			}
			return out;
		};
		for (int pass = 0; pass < 2; pass++) { // the second pass is answered from what the first remembered, for those still remembered
			for (int i = 0; i < n; i++) {
				assertEquals(keys.apply(islands.near(xs[i], zs[i], Math.ulp(1.0))), keys.apply(islands.near(xs[i], zs[i], 0.0)), "islands near " + xs[i] + "," + zs[i]);
			}
		}
		// asked by many threads at once, a point still has one answer
		int[] bad = new int[1];
		java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
			if (!keys.apply(islands.near(xs[i], zs[i], Math.ulp(1.0))).equals(keys.apply(islands.near(xs[i], zs[i], 0.0)))) {
				synchronized (bad) {
					bad[0]++;
				}
			}
		});
		assertEquals(0, bad[0]);
	}

	@Test
	void theSameSeedGivesTheSameIslandsInAnyOrder() {
		Islands a = islands(4242L, Islands.Dials.defaults());
		Islands b = islands(4242L, Islands.Dials.defaults());
		List<Island> first = all(a);
		List<Island> second = all(b);
		assertEquals(first.size(), second.size());
		assertTrue(first.size() > 20, "the region has islands: " + first.size());
		for (int i = 0; i < first.size(); i++) {
			assertEquals(first.get(i).key(), second.get(i).key());
			assertEquals(first.get(i).x(), second.get(i).x(), 0.0);
		}
		// the ground, asked for in opposite orders by two catalogues that have cached different things, is the same
		List<double[]> points = new ArrayList<>();
		for (Island is : first) {
			points.add(new double[] { is.x() + 0.4 * is.radius(), is.z() - 0.3 * is.radius() });
		}
		double[] forward = new double[points.size()];
		for (int i = 0; i < points.size(); i++) {
			forward[i] = a.height(points.get(i)[0], points.get(i)[1], Islands.OCEAN_FLOOR);
		}
		for (int i = points.size() - 1; i >= 0; i--) {
			assertEquals(forward[i], b.height(points.get(i)[0], points.get(i)[1], Islands.OCEAN_FLOOR), 0.0);
		}
	}

	@Test
	void islandsStandInOpenSeaAndNeverOnAContinent() {
		for (long seed : SEEDS) {
			Islands islands = islands(seed, Islands.Dials.defaults());
			Tectonics tec = new Tectonics(EarthConfig.defaults(seed));
			PlateGrid.Hit hit = new PlateGrid.Hit();
			for (Island is : all(islands)) {
				assertTrue(!tec.plateAt(is.x(), is.z(), hit).continental(), seed + ": " + is.kind() + " on a continental plate");
				double margin = is.kind() == Kind.MUSHROOM ? Islands.MUSHROOM_CLEAR : Islands.VOLCANIC_CLEAR;
				assertTrue(islands.clear(is.x(), is.z(), margin), seed + ": " + is.kind() + " at " + is.x() + ", " + is.z() + " has continental crust within " + margin);
				// and the whole of its land is over oceanic crust
				double reach = 1.3 * is.radius() * is.elongation();
				for (int k = 0; k < 12; k++) {
					double a = k * Math.PI / 6.0;
					assertTrue(!tec.plateAt(is.x() + Math.cos(a) * reach, is.z() + Math.sin(a) * reach, hit).continental(), seed + ": the shore of " + is.kind() + " is over a continent");
				}
			}
		}
	}

	@Test
	void aMushroomIslandHasRoomAroundIt() {
		for (long seed : SEEDS) {
			List<Island> all = all(islands(seed, Islands.Dials.defaults()));
			int mushrooms = 0;
			for (Island m : all) {
				if (m.kind() != Kind.MUSHROOM) {
					continue;
				}
				mushrooms++;
				for (Island o : all) {
					if (o != m && o.kind() != Kind.MUSHROOM) {
						assertTrue(Math.hypot(o.x() - m.x(), o.z() - m.z()) >= m.radius() + o.radius() + 3_000.0 - 1.0, seed + ": a " + o.kind() + " is too near a mushroom island");
					}
				}
			}
			assertTrue(mushrooms > 5, "the region has mushroom islands: " + mushrooms);
		}
	}

	@Test
	void anIslandRisesOutOfTheFloorAndIsTheFloorBeyondItsFootprint() {
		Islands islands = islands(4242L, Islands.Dials.defaults());
		double steepest = 0.0;
		int checked = 0;
		for (Island is : all(islands)) {
			for (int k = 0; k < 8; k++) {
				double a = k * Math.PI / 4.0 + 0.3;
				double x = is.x() + Math.cos(a) * (is.footprint() + 5.0);
				double z = is.z() + Math.sin(a) * (is.footprint() + 5.0);
				assertEquals(Double.NEGATIVE_INFINITY, Islands.profile(is, x, z, Islands.OCEAN_FLOOR), 0.0, "an island is nothing beyond its footprint");
				// walking out from the middle, one block at a time, the ground never jumps
				double previous = Islands.profile(is, is.x(), is.z(), Islands.OCEAN_FLOOR);
				for (double r = 1.0; r < is.footprint(); r += 1.0) {
					double h = Islands.profile(is, is.x() + Math.cos(a) * r, is.z() + Math.sin(a) * r, Islands.OCEAN_FLOOR);
					if (Double.isInfinite(h)) {
						break;
					}
					steepest = Math.max(steepest, Math.abs(h - previous));
					previous = h;
				}
				// at the edge of the footprint it has come down to the floor (within a block)
				double edge = Islands.profile(is, is.x() + Math.cos(a) * (is.footprint() * 0.995), is.z() + Math.sin(a) * (is.footprint() * 0.995), Islands.OCEAN_FLOOR);
				if (!Double.isInfinite(edge)) {
					assertTrue(edge <= Islands.OCEAN_FLOOR + 3.0, "an island comes down to the floor at its edge: " + edge + " against " + Islands.OCEAN_FLOOR);
				}
			}
			checked++;
		}
		System.out.printf("[islands-test] %d islands; the steepest step between neighbouring blocks is %.2f%n", checked, steepest);
		assertTrue(steepest < 6.0, "no cliff between neighbouring blocks: " + steepest);
	}

	@Test
	void eachKindHasItsOwnHeightAndShape() {
		Islands islands = islands(4242L, Islands.Dials.defaults());
		Map<Kind, Integer> seen = new TreeMap<>();
		int atollsWithLand = 0;
		int atolls = 0;
		for (Long2Island entry : sample(islands)) {
			Island is = entry.island();
			double top = -1.0e9;
			double bottomInside = 1.0e9;
			double reach = is.radius() * is.elongation() * 1.3;
			for (double dz = -reach; dz <= reach; dz += 30.0) {
				for (double dx = -reach; dx <= reach; dx += 30.0) {
					double h = Islands.profile(is, is.x() + dx, is.z() + dz, Islands.OCEAN_FLOOR);
					if (!Double.isInfinite(h)) {
						top = Math.max(top, h);
						if (Math.hypot(dx, dz) < 0.6 * is.radius()) {
							bottomInside = Math.min(bottomInside, h);
						}
					}
				}
			}
			seen.merge(is.kind(), 1, Integer::sum);
			switch (is.kind()) {
				case MUSHROOM -> {
					assertTrue(top >= 3.0 && top <= is.crest() + 4.0 && top <= 28.0, "a mushroom island is low and gentle: crest " + is.crest() + ", top " + top);
					assertTrue(is.radius() >= 250.0 && is.radius() <= 900.0 + 1.0, "radius " + is.radius());
				}
				case VOLCANO -> {
					assertTrue(top >= 0.6 * is.crest() && top <= 1.2 * is.crest() && top >= 40.0, "a young volcano is tall: crest " + is.crest() + ", top " + top);
					assertTrue(is.crest() <= 380.0, "crest " + is.crest());
				}
				case OLD_VOLCANO -> assertTrue(top <= 1.15 * is.crest() + 5.0 && top <= 200.0, "an old volcano is worn low: crest " + is.crest() + ", top " + top);
				case ATOLL -> {
					atolls++;
					atollsWithLand += top > 0.0 ? 1 : 0;
					assertTrue(top <= 12.0, "an atoll stands only a few blocks above the water: " + top);
					assertTrue(bottomInside < -3.0, "an atoll has a lagoon: " + bottomInside);
				}
				default -> {
				}
			}
		}
		System.out.printf("[islands-test] kinds checked: %s; atolls with land %d of %d%n", seen, atollsWithLand, atolls);
		assertTrue(seen.containsKey(Kind.MUSHROOM) && seen.containsKey(Kind.VOLCANO) && seen.containsKey(Kind.OLD_VOLCANO), "the sample has the main kinds: " + seen);
		assertTrue(atolls == 0 || atollsWithLand * 2 >= atolls, "most atolls have islets above water: " + atollsWithLand + " of " + atolls);
	}

	private record Long2Island(Island island) {
	}

	/** Every kind, a few of each, from three seeds, so atolls (rare) are included. */
	private static List<Long2Island> sample(Islands first) {
		List<Long2Island> out = new ArrayList<>();
		int[] perKind = new int[Kind.values().length];
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			Islands islands = seed == 4242L ? first : islands(seed, Islands.Dials.defaults());
			for (Island is : all(islands)) {
				if (perKind[is.kind().ordinal()] < 12) {
					perKind[is.kind().ordinal()]++;
					out.add(new Long2Island(is));
				}
			}
		}
		return out;
	}

	@Test
	void aCraterLakeIsInsideTheRimAndCannotSpill() {
		int lakes = 0;
		int young = 0;
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			Islands world = islands(seed, Islands.Dials.defaults());
			for (Island is : all(world)) {
				if (is.kind() != Kind.VOLCANO || is.age() > 0.30) {
					continue;
				}
				young++;
				double level = world.craterLake(is, Islands.OCEAN_FLOOR);
				if (Double.isNaN(level)) {
					continue;
				}
				lakes++;
				// every point of the rim is above the water, and the water is only inside it
				double ring = 1.15 * (0.09 + 0.06 * Noise.unit(is.key(), 1, 0, 3)) * is.radius();
				for (int a = 0; a < 24; a++) {
					double angle = a * Math.PI / 12.0;
					double ex = Math.cos(angle) * ring * is.elongation();
					double ez = Math.sin(angle) * ring / is.elongation();
					double h = Islands.profile(is, is.x() + ex * is.dirX() - ez * is.dirZ(), is.z() + ex * is.dirZ() + ez * is.dirX(), Islands.OCEAN_FLOOR);
					assertTrue(h >= level + 1.0, "the rim of a crater lake stands above its water: rim " + h + " against " + level);
				}
				assertEquals(level, world.craterWater(is.x(), is.z(), Islands.OCEAN_FLOOR), 0.0, "the middle of the crater is water");
				assertTrue(Double.isNaN(world.craterWater(is.x() + 0.5 * is.radius(), is.z(), Islands.OCEAN_FLOOR)), "the flank of the volcano is not");
				assertTrue(level > 20.0, "a crater lake is high above the sea: " + level);
			}
		}
		System.out.printf("[islands-test] %d crater lakes in %d young volcanoes%n", lakes, young);
		assertTrue(lakes > 0 && lakes < young, "some young volcanoes have a crater lake and some do not: " + lakes + " of " + young);
	}

	@Test
	void anIslandsShoreIsABeachAndItsSummitIsColdWhateverTheSignOfVanillasNoise() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		BiomeClimate.Cell cell = new BiomeClimate.Cell();
		for (double w0 : new double[] { -0.6, -0.2, 0.2, 0.6 }) {
			for (double t0 : new double[] { -0.5, 0.0, 0.3 }) {
				Islands.climate(1.0, 40.0, t0, 0.0, w0, 100.0, 100.0, 77L, BiomeClimate.Dials.defaults(), cell);
				String shore = source.getNoiseBiome(Climate.target((float) cell.temperature, (float) cell.humidity, (float) cell.continentalness, (float) cell.erosion, 0.0f, (float) cell.weirdness))
					.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				assertTrue(shore.contains("beach") || shore.equals("stony_shore"), "a low shore is a beach (or, on a rocky stretch, a stony shore), not " + shore + " (weirdness " + w0 + ", temperature " + t0 + "): t " + cell.temperature + " h " + cell.humidity + " c " + cell.continentalness + " e " + cell.erosion + " w " + cell.weirdness);
			}
		}
		// a tall volcano is colder on top: the temperature falls with height
		BiomeClimate.Cell low = new BiomeClimate.Cell();
		BiomeClimate.Cell high = new BiomeClimate.Cell();
		Islands.climate(5.0, 400.0, 0.3, 0.0, 0.4, 100.0, 100.0, 77L, BiomeClimate.Dials.defaults(), low);
		Islands.climate(280.0, 400.0, 0.3, 0.0, 0.4, 100.0, 100.0, 77L, BiomeClimate.Dials.defaults(), high);
		assertTrue(high.temperature < low.temperature - 0.5, "the summit is colder: " + high.temperature + " against " + low.temperature);
		// a mushroom island's climate is what vanilla's table calls mushroom fields
		Islands.climate(10.0, 300.0, 0.3, 0.0, 0.4, 100.0, 100.0, 77L, BiomeClimate.Dials.defaults(), low);
		Islands.mushroomClimate(low);
		assertEquals("mushroom_fields", source.getNoiseBiome(Climate.target((float) low.temperature, (float) low.humidity, (float) low.continentalness, (float) low.erosion, 0.0f, (float) low.weirdness))
			.unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
	}

	@Test
	void theFeatureBiomesAreWhereTheirFeaturesAre() {
		int summits = 0;
		int atolls = 0;
		int lakes = 0;
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			Islands world = islands(seed, Islands.Dials.defaults());
			for (Island is : all(world)) {
				switch (is.kind()) {
					case VOLCANO -> {
						// the middle of a young volcano: the summit (or the crater lake in it); its foot and the sea around it are neither
						Islands.Highlight top = world.highlight(is.x(), is.z(), Islands.OCEAN_FLOOR);
						if (is.crest() >= 60.0) {
							assertTrue(top == Islands.Highlight.VOLCANO || top == Islands.Highlight.CRATER_LAKE, "the summit of a young volcano has its own biome: " + top);
							summits++;
							lakes += top == Islands.Highlight.CRATER_LAKE ? 1 : 0;
						}
						assertEquals(null, world.highlight(is.x() + 0.8 * is.radius() * is.elongation(), is.z(), Islands.OCEAN_FLOOR), "the lower flank is the climate's");
						assertEquals(null, world.highlight(is.x() + 2.0 * is.footprint(), is.z(), Islands.OCEAN_FLOOR), "the open sea is the climate's");
					}
					case OLD_VOLCANO, MUSHROOM, FRAGMENT -> {
						Islands.Highlight h = world.highlight(is.x(), is.z(), Islands.OCEAN_FLOOR);
						assertEquals(null, h, "a " + is.kind() + " has no feature biome of its own");
					}
					case ATOLL -> {
						// somewhere on the ring is the reef: some point of it is the atoll's biome, and the lagoon in the middle is not
						boolean onRing = false;
						for (int k = 0; k < 24 && !onRing; k++) {
							double a = k * Math.PI / 12.0;
							onRing = world.highlight(is.x() + Math.cos(a) * is.radius(), is.z() + Math.sin(a) * is.radius(), Islands.OCEAN_FLOOR) == Islands.Highlight.ATOLL;
						}
						assertTrue(onRing, "an atoll's reef has its biome");
						assertEquals(null, world.highlight(is.x(), is.z(), Islands.OCEAN_FLOOR), "the lagoon is not the reef");
						atolls++;
					}
					default -> {
					}
				}
			}
		}
		System.out.printf("[islands-test] feature biomes checked on %d volcano summits (%d with a crater lake) and %d atolls%n", summits, lakes, atolls);
		assertTrue(summits > 10 && atolls > 3 && lakes > 0);
	}

	@Test
	void hotspotChainsTrailBackAlongThePlatesMotionAndAgeAlongTheirLength() {
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			Tectonics tec = new Tectonics(EarthConfig.defaults(seed));
			Islands islands = new Islands(tec, Islands.Dials.defaults());
			Map<String, List<Island>> chains = new TreeMap<>();
			for (Island is : all(islands)) {
				if (is.origin() == Origin.HOTSPOT) {
					// members of one chain share a direction and a size
					chains.computeIfAbsent(is.dirX() + "," + is.dirZ(), k -> new ArrayList<>()).add(is);
				}
			}
			assertTrue(chains.size() >= 3, seed + ": hotspot chains exist: " + chains.size());
			PlateGrid.Hit hit = new PlateGrid.Hit();
			for (List<Island> chain : chains.values()) {
				chain.sort((a, b) -> Integer.compare(a.chain(), b.chain()));
				Island youngest = chain.get(0);
				double previousAge = -1.0;
				int previousKind = 0;
				for (Island is : chain) {
					assertTrue(is.age() >= previousAge - 1.0e-9 || is.chain() == youngest.chain(), seed + ": a chain gets older along its length");
					assertTrue(is.kind().ordinal() >= previousKind, seed + ": volcano, then old volcano, then atoll");
					previousAge = is.age();
					previousKind = is.kind().ordinal();
				}
				// the first one is the highest volcano of the chain
				for (Island is : chain) {
					if (is.kind() == Kind.OLD_VOLCANO) {
						assertTrue(is.crest() < 175.0, "an old volcano is low: " + is.crest());
					}
				}
				// the line of the chain runs against the motion of the plate it is on
				Plate plate = tec.plateAt(youngest.x(), youngest.z(), hit);
				double speed = Math.hypot(plate.vx(), plate.vz());
				double dot = youngest.dirX() * -plate.vx() / speed + youngest.dirZ() * -plate.vz() / speed;
				assertEquals(1.0, dot, 1.0e-6, seed + ": the chain trails back along the plate's motion");
				if (chain.size() >= 3) {
					Island last = chain.get(chain.size() - 1);
					double along = (last.x() - youngest.x()) * youngest.dirX() + (last.z() - youngest.z()) * youngest.dirZ();
					assertTrue(along > 2_000.0, seed + ": the oldest island lies down the trail from the youngest: " + along);
				}
			}
		}
	}

	@Test
	void arcVolcanoesStandBesideAnIslandArcBoundaryOnTheRidingPlate() {
		int arcs = 0;
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			Tectonics tec = new Tectonics(EarthConfig.defaults(seed));
			Islands islands = new Islands(tec, Islands.Dials.defaults());
			PlateGrid.Hit hit = new PlateGrid.Hit();
			for (Island is : all(islands)) {
				if (is.origin() != Origin.ARC) {
					continue;
				}
				arcs++;
				Plate a = tec.plateAt(is.x(), is.z(), hit);
				Plate b = tec.plate(hit.ni, hit.nj);
				Contact contact = tec.contact(a, b);
				assertTrue(contact.type() == Boundary.ISLAND_ARC && contact.firstOverrides() && !b.continental(), "an arc volcano is on the overriding plate of an ocean-ocean convergence");
				assertTrue(Math.abs(hit.distance - 800.0) <= 900.0, "an arc volcano is a little way back from the boundary: " + hit.distance);
			}
		}
		assertTrue(arcs > 3, "the regions have arc volcanoes: " + arcs);
	}

	@Test
	void mushroomIslandsComeAtAboutVanillasRate() {
		double qualifying = 0.0;
		int mushrooms = 0;
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			Islands islands = islands(seed, Islands.Dials.defaults());
			int hits = 0;
			int samples = 0;
			for (double z = -150_000.0; z < 150_000.0; z += 3_000.0) {
				for (double x = -150_000.0; x < 150_000.0; x += 3_000.0) {
					samples++;
					hits += islands.qualifies(x, z) ? 1 : 0;
				}
			}
			qualifying += 300.0 * 300.0 * hits / samples;
			for (Island is : EarthIslandPreviewTest.islandsIn(islands, 0.0, 0.0, 150_000.0).values()) {
				mushrooms += is.kind() == Kind.MUSHROOM ? 1 : 0;
			}
		}
		double perIsland = qualifying / mushrooms;
		System.out.printf("[islands-test] %d mushroom islands in %.0f km2 of open ocean: one per %.0f km2 (vanilla: one per 260 km2 of its ocean)%n", mushrooms, qualifying, perIsland);
		assertTrue(perIsland > 200.0 && perIsland < 340.0, "about one mushroom island per 260 km2 of open ocean: " + perIsland);
	}

	@Test
	void theDialsDoWhatTheySay() {
		Islands off = islands(4242L, new Islands.Dials(0.0, 1.0, 1.0));
		for (Island is : all(off)) {
			assertTrue(is.kind() != Kind.MUSHROOM, "no mushroom islands at 0");
		}
		Islands calm = islands(4242L, new Islands.Dials(4.1, 0.0, 1.0));
		for (Island is : all(calm)) {
			assertTrue(is.origin() == Origin.MYSTERY, "no volcanic islands at 0");
		}
		Islands big = islands(4242L, new Islands.Dials(4.1, 1.0, 2.0));
		Map<Long, Island> normal = EarthIslandPreviewTest.islandsIn(islands(4242L, Islands.Dials.defaults()), 0.0, 0.0, HALF);
		int compared = 0;
		for (Island is : all(big)) {
			Island same = normal.get(is.key());
			if (same != null && is.kind() == Kind.MUSHROOM) {
				assertEquals(2.0 * same.radius(), is.radius(), 1.0e-6);
				compared++;
			}
		}
		assertTrue(compared > 3, "some mushroom islands exist at both sizes: " + compared);
	}

	@Test
	void aMushroomIslandIsLabelledOnItsLandAndShallowsAndNowhereElse() {
		Islands islands = islands(4242L, Islands.Dials.defaults());
		int checked = 0;
		for (Island is : all(islands)) {
			if (is.kind() != Kind.MUSHROOM) {
				continue;
			}
			assertNotNull(islands.mushroomAt(is.x(), is.z(), Islands.OCEAN_FLOOR), "the middle is labelled");
			assertNull(islands.mushroomAt(is.x() + 1.5 * is.radius(), is.z(), Islands.OCEAN_FLOOR), "beyond the shallows it is not");
			checked++;
		}
		assertTrue(checked > 5);
		// and nothing else is
		for (Island is : all(islands)) {
			if (is.kind() == Kind.VOLCANO) {
				assertNull(islands.mushroomAt(is.x(), is.z(), Islands.OCEAN_FLOOR), "a volcano is not a mushroom island");
			}
		}
	}

	@Test
	void vanillasTableAnswersMushroomFieldsBelowTheThresholdAndDeepOceanAround() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		Set<String> inside = new HashSet<>();
		Set<String> ring = new HashSet<>();
		for (float temperature : new float[] { -0.8f, -0.3f, 0.1f, 0.4f, 0.8f }) {
			for (float humidity : new float[] { -0.5f, 0.0f, 0.5f }) {
				inside.add(source.getNoiseBiome(Climate.target(temperature, humidity, -1.1f, 0.25f, 0.0f, 0.35f)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
				ring.add(source.getNoiseBiome(Climate.target(temperature, humidity, -0.7f, 0.25f, 0.0f, 0.35f)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
			}
		}
		assertEquals(Set.of("mushroom_fields"), inside, "continentalness -1.1 is mushroom fields in every climate");
		for (String biome : ring) {
			// (vanilla has no deep warm ocean: warm ocean is the answer at every depth)
			assertTrue(biome.startsWith("deep_") || biome.equals("warm_ocean"), "the ring around is deep ocean: " + biome);
		}
	}

	@Test
	void fragmentsInASeaOnAPlateKeepTheirDistanceFromLandAndTheRim() {
		Continent c = Seed4242.CONTINENT;
		float[] away = Islands.distanceToLand(c);
		List<Island> fragments = Islands.inWindow(c, Islands.Dials.defaults());
		assertEquals(fragments.size(), Islands.inWindow(c, Islands.Dials.defaults()).size(), "deterministic");
		for (Island f : fragments) {
			int gx = (int) Math.round((f.x() - c.x0) / c.cell);
			int gz = (int) Math.round((f.z() - c.z0) / c.cell);
			int i = gz * c.nx + gx;
			assertTrue(c.height[i] <= 0.0f && c.rim[i] <= 0.0f, "a fragment is in a real sea, not on the window's rim");
			assertTrue(away[i] >= Islands.SEA_CLEAR + 1.2 * f.radius() - 1.0e-3, "clear water around a fragment: " + away[i]);
			for (Island o : fragments) {
				assertTrue(o == f || Math.hypot(o.x() - f.x(), o.z() - f.z()) >= 3_000.0, "fragments are 3 km apart");
			}
			assertEquals(Kind.FRAGMENT, f.kind());
			assertEquals(Origin.SEA, f.origin());
		}
	}
}
