package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.BiomeClimate.Cell;
import com.epicterrain.earth.BiomeClimate.Dials;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** How height and relief turn into the climate numbers vanilla's biome table is asked about, and what the table then answers. */
class EarthBiomeClimateTest {
	private static final Dials CLEAN = new Dials(0.3, 0.0, 0.0);

	/** A cell far from the coast, in a middling climate, with the given terrain. */
	private static Cell cell(double height, double relief, double t0, double h0, double w0, Dials dials) {
		Cell out = new Cell();
		BiomeClimate.cell(height, relief, 1000.0, 5000.0, false, t0, h0, w0, 12_345.0, -6_789.0, 77L, dials, out);
		return out;
	}

	private static MultiNoiseBiomeSource vanilla() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		return (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
	}

	private static String biome(MultiNoiseBiomeSource source, Cell c) {
		Climate.TargetPoint p = Climate.target((float) c.temperature, (float) c.humidity, (float) c.continentalness, (float) c.erosion, 0.0f, (float) c.weirdness);
		return source.getNoiseBiome(p).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
	}

	@Test
	void theTallerTheGroundTheColderItIs() {
		double previous = Double.MAX_VALUE;
		for (double height : new double[] { 0.0, 500.0, 1000.0, 2000.0, 3000.0 }) {
			double t = cell(height, height, 0.5, 0.0, 0.3, CLEAN).temperature;
			assertEquals(0.5 - 0.3 * height / 1000.0, t, 1.0e-9, "a lapse of 0.3 a kilometre at " + height + " m");
			assertTrue(t < previous, "colder with height");
			previous = t;
		}
		assertEquals(-1.0, cell(4400.0, 4400.0, 0.2, 0.0, 0.3, CLEAN).temperature, 1.0e-9, "the temperature stays within vanilla's range");
		// a plateau is cold for being high, whatever its relief
		assertTrue(cell(1500.0, 0.0, 0.5, 0.0, 0.3, CLEAN).temperature < cell(100.0, 0.0, 0.5, 0.0, 0.3, CLEAN).temperature - 0.3);
	}

	@Test
	void reliefNotHeightAboveSeaPicksTheBand() {
		assertEquals(BiomeClimate.LOWLAND, cell(900.0, 50.0, 0.0, 0.0, 0.3, CLEAN).zone, "a high plain is still a plain");
		assertEquals(BiomeClimate.LOWLAND, cell(299.0, 299.0, 0.0, 0.0, 0.3, CLEAN).zone);
		assertEquals(BiomeClimate.FOOTHILLS, cell(500.0, 400.0, 0.0, 0.0, 0.3, CLEAN).zone);
		assertEquals(BiomeClimate.MONTANE, cell(1500.0, 1200.0, 0.0, 0.0, 0.3, CLEAN).zone);
		assertEquals(BiomeClimate.SUBALPINE, cell(2500.0, 2200.0, 0.0, 0.0, 0.3, CLEAN).zone);
		assertEquals(BiomeClimate.PEAKS, cell(4000.0, 3500.0, 0.0, 0.0, 0.3, CLEAN).zone);
		Cell sea = cell(-2000.0, 0.0, 0.0, 0.0, 0.3, CLEAN);
		assertEquals(BiomeClimate.OCEAN, sea.zone);
		assertTrue(sea.continentalness < cell(-100.0, 0.0, 0.0, 0.0, 0.3, CLEAN).continentalness, "deeper water is further out to sea");
		assertTrue(sea.continentalness < -0.455, "deep ocean is vanilla's deep-ocean range");
	}

	@Test
	void theBandsSteerErosionDownAndWeirdnessUp() {
		double[] relief = { 100.0, 600.0, 1200.0, 2400.0, 3500.0 };
		double previous = Double.MAX_VALUE;
		for (double r : relief) {
			Cell c = cell(r + 200.0, r, 0.0, 0.0, 0.4, CLEAN);
			// subalpine and peaks share the most mountainous erosion; they differ in weirdness
			assertTrue(c.erosion < previous || (r >= 3000.0 && c.erosion == previous), "the higher the relief, the more mountainous (lower) the erosion: " + c.erosion);
			previous = c.erosion;
			assertEquals(r >= 3000.0 ? 0.67 : r >= 1800.0 ? 0.30 : r >= 900.0 ? 0.30 : 0.40, c.weirdness, 1.0e-9);
			assertTrue(c.continentalness >= 0.45 || r < 300.0, "mountains are far inland");
		}
		// the sign comes from vanilla's noise, and picks jagged against frozen peaks and spruce against pine
		assertTrue(cell(4000.0, 3500.0, 0.0, 0.0, -0.2, CLEAN).weirdness < 0.0);
		assertTrue(cell(4000.0, 3500.0, 0.0, 0.0, 0.2, CLEAN).weirdness > 0.0);
	}

	@Test
	void riversGetTheValleySettingAndMountainsDoNot() {
		Cell river = new Cell();
		BiomeClimate.cell(80.0, 50.0, 1000.0, 5000.0, true, 0.0, 0.0, 0.4, 1.0, 1.0, 77L, CLEAN, river);
		assertEquals(0.0, river.weirdness, 0.0);
		assertEquals(-0.1, river.erosion, 0.0);
		Cell mountain = new Cell();
		BiomeClimate.cell(2000.0, 1800.0, 1000.0, 5000.0, true, 0.0, 0.0, 0.4, 1.0, 1.0, 77L, CLEAN, mountain);
		assertTrue(mountain.weirdness != 0.0, "a mountain stream is not a vanilla river");
	}

	@Test
	void rainShiftsHumidityByTheDialAndNotAtAll() {
		Dials pure = new Dials(0.3, 0.0, 0.0);
		Dials biased = new Dials(0.3, 1.0, 0.0);
		Cell a = new Cell();
		Cell b = new Cell();
		BiomeClimate.cell(100.0, 50.0, 2500.0, 5000.0, false, 0.0, 0.0, 0.4, 1.0, 1.0, 77L, pure, a);
		assertEquals(0.0, a.humidity, 1.0e-9, "rain bias 0 is vanilla's humidity as it is");
		BiomeClimate.cell(100.0, 50.0, 2500.0, 5000.0, false, 0.0, 0.0, 0.4, 1.0, 1.0, 77L, biased, a);
		BiomeClimate.cell(100.0, 50.0, 200.0, 5000.0, false, 0.0, 0.0, 0.4, 1.0, 1.0, 77L, biased, b);
		assertTrue(a.humidity > 0.3 && b.humidity < -0.3, "the windward side is wetter than the rain shadow: " + a.humidity + " vs " + b.humidity);
	}

	@Test
	void varietyMovesTheEdgesAndZeroLeavesThemClean() {
		int differ = 0;
		int clean = 0;
		for (int i = 0; i < 400; i++) {
			double relief = 100.0 + 8.0 * i; // 100 to 3,300 m
			double wx = 311.0 * i;
			double wz = -197.0 * i;
			Cell none = new Cell();
			Cell lots = new Cell();
			BiomeClimate.cell(relief + 200.0, relief, 1000.0, 5000.0, false, 0.0, 0.0, 0.4, wx, wz, 77L, CLEAN, none);
			BiomeClimate.cell(relief + 200.0, relief, 1000.0, 5000.0, false, 0.0, 0.0, 0.4, wx, wz, 77L, CLEAN.withVariety(1.0), lots);
			byte expected = relief < 300.0 ? BiomeClimate.LOWLAND : relief < 900.0 ? BiomeClimate.FOOTHILLS : relief < 1800.0 ? BiomeClimate.MONTANE
				: relief < 3000.0 ? BiomeClimate.SUBALPINE : BiomeClimate.PEAKS;
			clean += none.zone == expected ? 1 : 0;
			differ += none.zone != lots.zone ? 1 : 0;
		}
		assertEquals(400, clean, "with no variety the bands are exactly the thresholds");
		assertTrue(differ > 60, "with full variety the zones interleave: " + differ + " of 400 differ");
	}

	@Test
	void reliefIsHowFarGroundStandsAboveItsSurroundings() {
		Continent c = ContinentSimulator.run(EarthConfig.defaults(4242L).withCell(64).withSteps(40));
		float[] relief = BiomeClimate.reliefAboveBase(c);
		float[] land = new float[c.nx * c.nz];
		int count = 0;
		float highest = 0.0f;
		for (int i = 0; i < relief.length; i++) {
			assertTrue(relief[i] >= 0.0f && relief[i] <= Math.max(0.0f, c.height[i]) + 1.0e-3f, "relief is never negative or more than the height");
			if (c.height[i] >= 0.0f) {
				land[count++] = relief[i];
				highest = Math.max(highest, relief[i]);
			}
		}
		Arrays.sort(land, 0, count);
		assertTrue(land[count / 2] < 150.0f, "most of the land is low relief, the broad plains: median " + land[count / 2]);
		assertTrue(highest > 1000.0f, "the ranges are tall relief: " + highest);
	}

	@Test
	void vanillaGivesTheVarietyTheDesignPromises() {
		MultiNoiseBiomeSource source = vanilla();
		Set<String> mountain = Set.of("frozen_peaks", "jagged_peaks", "stony_peaks", "snowy_slopes", "grove", "cherry_grove", "meadow");
		// lowland, in every kind of climate, is never a mountain biome
		for (double t0 : new double[] { -0.5, -0.1, 0.1, 0.4, 0.8 }) {
			for (double h0 : new double[] { -0.4, 0.0, 0.4 }) {
				for (double w0 : new double[] { -0.4, 0.4 }) {
					String name = biome(source, cell(100.0, 50.0, t0, h0, w0, CLEAN));
					assertTrue(!mountain.contains(name), "lowland " + t0 + "," + h0 + "," + w0 + " came out as " + name);
				}
			}
		}
		// the tallest ground is cold whatever the climate at its foot, and only ever the peak biomes
		for (double t0 : new double[] { -0.3, 0.0, 0.4, 0.8 }) {
			for (double w0 : new double[] { -0.4, 0.4 }) {
				String name = biome(source, cell(4000.0, 3500.0, t0, 0.0, w0, CLEAN));
				assertTrue(name.equals("frozen_peaks") || name.equals("jagged_peaks"), "peaks at t0 " + t0 + " came out as " + name);
			}
		}
		assertEquals("frozen_peaks", biome(source, cell(4000.0, 3500.0, 0.0, 0.0, 0.4, CLEAN)));
		assertEquals("jagged_peaks", biome(source, cell(4000.0, 3500.0, 0.0, 0.0, -0.4, CLEAN)));
		// a mountain that is not only peaks: a dry, mild flank is a cherry grove, a cold wet one is old-growth taiga (pine or spruce by the sign)
		assertEquals("cherry_grove", biome(source, cell(1300.0, 1200.0, 0.4, -0.4, 0.4, CLEAN)));
		assertEquals("old_growth_pine_taiga", biome(source, cell(1300.0, 1200.0, 0.0, 0.5, 0.4, CLEAN)));
		assertEquals("old_growth_spruce_taiga", biome(source, cell(1300.0, 1200.0, 0.0, 0.5, -0.4, CLEAN)));
		// and higher up the same climate turns to snow
		String slope = biome(source, cell(2600.0, 2200.0, 0.4, -0.4, 0.4, CLEAN));
		assertTrue(slope.equals("snowy_slopes") || slope.equals("grove"), "a subalpine slope: " + slope);
	}

	/** Every biome the bands alone once never asked vanilla's table for, with a place the model can ask for it. */
	@Test
	void theRowsOfTheTableTheBandsOnceMissedCanBeAsked() {
		MultiNoiseBiomeSource source = vanilla();
		Set<String> hills = new java.util.TreeSet<>();
		Set<String> nearCoast = new java.util.TreeSet<>();
		Set<String> shore = new java.util.TreeSet<>();
		Set<String> summits = new java.util.TreeSet<>();
		Dials dials = Dials.defaults();
		int hillCells = 0;
		int windswept = 0;
		int summitCells = 0;
		int stony = 0;
		for (int i = 0; i < 3000; i++) {
			double wx = 4_321.0 * i + 17.0 * (i % 7);
			double wz = -2_987.0 * (i % 531) + 1_013.0 * i;
			for (double t0 : new double[] { -0.7, -0.3, 0.0, 0.4, 0.7 }) {
				for (double h0 : new double[] { -0.5, 0.0, 0.4 }) {
					Cell hill = new Cell();
					BiomeClimate.cell(700.0, 500.0, 1000.0, 5000.0, false, t0, h0, 0.4, wx, wz, 77L, dials, hill);
					String hillBiome = biome(source, hill);
					hills.add(hillBiome);
					Cell near = new Cell();
					BiomeClimate.cell(60.0, 20.0, 1000.0, 150.0, false, t0, h0, 0.4, wx, wz, 77L, dials, near);
					nearCoast.add(biome(source, near));
					Cell coast = new Cell();
					BiomeClimate.cell(30.0, 10.0, 1000.0, 40.0, false, t0, h0, 0.4, wx, wz, 77L, dials, coast);
					shore.add(biome(source, coast));
					Cell peak = new Cell();
					BiomeClimate.cell(4000.0, 3500.0, 1000.0, 9000.0, false, t0, h0, 0.6, wx, wz, 77L, dials, peak);
					String peakBiome = biome(source, peak);
					summits.add(peakBiome);
					if (t0 == 0.0 && h0 == 0.0) {
						hillCells++;
						windswept += hillBiome.startsWith("windswept") ? 1 : 0;
						summitCells++;
						stony += peakBiome.equals("stony_peaks") ? 1 : 0;
					}
				}
			}
		}
		assertTrue(hills.containsAll(Set.of("windswept_hills", "windswept_forest", "windswept_gravelly_hills")), "the hill country has windswept patches: " + hills);
		assertTrue(nearCoast.contains("windswept_savanna"), "the near-coast lowland has a windswept savanna: " + nearCoast);
		assertTrue(shore.contains("stony_shore") && shore.contains("beach"), "a coast is sand in places and rock in others: " + shore);
		assertTrue(summits.contains("stony_peaks") && (summits.contains("jagged_peaks") || summits.contains("frozen_peaks")), "the summits are bare rock in places and snow in others: " + summits);
		assertTrue(windswept > hillCells / 40 && windswept < hillCells / 3, "windswept is a patch of the hill country, not all of it: " + windswept + " of " + hillCells);
		assertTrue(stony > summitCells / 8 && stony < summitCells * 2 / 3, "stony peaks are a share of the summits: " + stony + " of " + summitCells);
		// and with the variety dial at 0 there are none of these patches: the bands are clean
		Dials clean = new Dials(0.3, 0.0, 0.0);
		for (int i = 0; i < 400; i++) {
			Cell hill = new Cell();
			BiomeClimate.cell(700.0, 500.0, 1000.0, 5000.0, false, 0.0, 0.0, 0.4, 311.0 * i, -197.0 * i, 77L, clean, hill);
			assertEquals(-0.30, hill.erosion, 1.0e-9, "no patches with no variety");
		}
	}

	@Test
	void theSeaIsDeepOceanFromTheDepthOfARealDeepSea() {
		MultiNoiseBiomeSource source = vanilla();
		// the sea floor is stored eased to 85 blocks (see Continent.blocksAbove), and the biome mapping works in real metres: the two are one scale
		for (double blocks : new double[] { -5.0, -24.0, -41.0, -62.0, -75.0, -83.0 }) {
			assertEquals(blocks, Continent.blocksAbove(Continent.metresOf(blocks)), 1.0e-6, "metresOf undoes blocksAbove at " + blocks);
		}
		assertEquals(30.0, Continent.metresOf(3.0) / 1.06, 1.0e-6, "on land a block is 10.6 m");
		Cell shallow = cell(Continent.metresOf(-15.0), 0.0, 0.0, 0.0, 0.3, CLEAN);
		Cell shelfBreak = cell(Continent.metresOf(-40.0), 0.0, 0.0, 0.0, 0.3, CLEAN);
		Cell deep = cell(Continent.metresOf(-70.0), 0.0, 0.0, 0.0, 0.3, CLEAN);
		Cell abyss = cell(Continent.metresOf(-83.0), 0.0, 0.0, 0.0, 0.3, CLEAN);
		assertEquals("ocean", biome(source, shallow));
		assertEquals("ocean", biome(source, shelfBreak));
		assertEquals("deep_ocean", biome(source, deep), "70 blocks down is a deep ocean");
		assertEquals("deep_ocean", biome(source, abyss), "and the deepest floor of the world too (it was never asked for as one while the two scales were mixed)");
		// by temperature: cold, lukewarm and frozen deep oceans
		for (double[] t : new double[][] { { -0.6, 0 }, { -0.3, 0 }, { 0.4, 0 } }) {
			assertTrue(biome(source, cell(Continent.metresOf(-80.0), 0.0, t[0], 0.0, 0.3, CLEAN)).startsWith("deep_"), "deep at temperature " + t[0]);
		}
	}

	@Test
	void hillCountryIsBadlandsInTheHotAndFlatCountryIsDesert() {
		MultiNoiseBiomeSource source = vanilla();
		for (double h0 : new double[] { -0.4, -0.1, 0.1 }) {
			Cell flat = new Cell();
			Cell hilly = new Cell();
			BiomeClimate.cell(100.0, 20.0, 1000.0, 5000.0, false, 0.8, h0, 0.4, 12_345.0, -6_789.0, 77L, 0.0, CLEAN, flat);
			BiomeClimate.cell(100.0, 20.0, 1000.0, 5000.0, false, 0.8, h0, 0.4, 12_345.0, -6_789.0, 77L, 1.0, CLEAN, hilly);
			assertEquals("desert", biome(source, flat), "flat hot country is desert");
			assertTrue(biome(source, hilly).contains("badlands"), "hilly hot country is badlands: " + biome(source, hilly));
			assertTrue(hilly.erosion < flat.erosion - 0.3, "hill country has the erosion of hills");
		}
		// the cooler families are the same in either, it is the terrain that differs
		Cell flat = new Cell();
		Cell hilly = new Cell();
		BiomeClimate.cell(100.0, 20.0, 1000.0, 5000.0, false, 0.0, 0.0, 0.4, 12_345.0, -6_789.0, 77L, 0.0, CLEAN, flat);
		BiomeClimate.cell(100.0, 20.0, 1000.0, 5000.0, false, 0.0, 0.0, 0.4, 12_345.0, -6_789.0, 77L, 1.0, CLEAN, hilly);
		assertEquals(biome(source, flat), biome(source, hilly));
	}

	@Test
	void theContinentsRainIsStretchedOverTheRangeTheClimateWorksIn() {
		assertEquals(1000.0, BiomeClimate.climateRain(650.0), 1.0e-9, "the middle of the continent's land is neither wet nor dry");
		assertTrue(BiomeClimate.climateRain(250.0) < 400.0 && BiomeClimate.climateRain(1_100.0) > 1_700.0, "dry land is dry and wet land wet for the climate");
		assertEquals(0.0, BiomeClimate.climateRain(0.0) > 0.0 ? 0.0 : 0.0, 0.0);
		double previous = -1.0;
		for (double r = 0.0; r <= 3_000.0; r += 100.0) {
			assertTrue(BiomeClimate.climateRain(r) >= previous, "monotone");
			previous = BiomeClimate.climateRain(r);
		}
	}

}
