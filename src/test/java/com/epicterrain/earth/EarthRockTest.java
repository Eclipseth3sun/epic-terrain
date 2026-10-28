package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The rock layers in the simulation: the dial, what the surface rock is, and what it does to the land. */
class EarthRockTest {
	/** A window a third of a plate wide, coarse cells and few steps (seeds 3 and 7 have mountains): quick, but the whole pipeline runs. */
	private static EarthConfig small(long seed) {
		return EarthConfig.defaults(seed).withPlate(25_000.0, 0.5).withCell(64).withSteps(30);
	}

	private static double steepShare(Continent c, double degrees) {
		int land = 0;
		int steep = 0;
		for (int z = 1; z < c.nz - 1; z++) {
			for (int x = 1; x < c.nx - 1; x++) {
				if (c.height[z * c.nx + x] <= 0.0f) {
					continue;
				}
				land++;
				steep += EarthRockPreviewTest.steepest(c, x, z) > degrees ? 1 : 0;
			}
		}
		return (double) steep / land;
	}

	private static double spread(Continent c) {
		double sum = 0.0;
		double squares = 0.0;
		int n = 0;
		for (int i = 0; i < c.height.length; i++) {
			if (c.height[i] > 0.0f) {
				double ln = Math.log(c.hardness[i]);
				sum += ln;
				squares += ln * ln;
				n++;
			}
		}
		double mean = sum / n;
		return Math.sqrt(squares / n - mean * mean);
	}

	@Test
	void withNoRockEveryCellErodesAtTheSameRate() {
		Continent c = ContinentSimulator.run(small(7L).withRock(0.0));
		for (float h : c.hardness) {
			assertEquals(1.0f, h, 0.0f);
		}
	}

	@Test
	void layeredRockHasHardAndSoftGroundAndMoreOfItWithTheDial() {
		Continent one = ContinentSimulator.run(small(7L));
		Continent two = ContinentSimulator.run(small(7L).withRock(2.0));
		int land = 0;
		int hard = 0;
		int soft = 0;
		for (int i = 0; i < one.height.length; i++) {
			if (one.height[i] > 0.0f) {
				land++;
				hard += one.hardness[i] < 0.4f ? 1 : 0;
				soft += one.hardness[i] > 1.8f ? 1 : 0;
				assertTrue(one.hardness[i] > 0.0f && one.hardness[i] < 3.2f, "hardness stays in range: " + one.hardness[i]);
			}
		}
		System.out.printf("[rock-test] %.1f%% of the land is hard rock and %.1f%% soft at rock 1; the spread of ln(hardness) is %.2f at rock 1 and %.2f at rock 2%n",
			100.0 * hard / land, 100.0 * soft / land, spread(one), spread(two));
		// hard beds wear slowly and stay at the surface, soft ones wear away and show only in thin bands on the slopes: far more of the land is hard
		assertTrue(hard > 0.05 * land && soft > 0.01 * land && hard > soft, "both hard and soft rock outcrop, the hard much more: " + hard + " and " + soft + " of " + land);
		assertTrue(spread(two) > 1.2 * spread(one), "twice the dial, a wider range of hardness");
	}

	@Test
	void layersMakeTheLandSteeperAndChangeIt() {
		for (long seed : new long[] { 3L, 7L }) {
			Continent flat = ContinentSimulator.run(small(seed).withRock(0.0));
			Continent layered = ContinentSimulator.run(small(seed));
			double a = steepShare(flat, 8.0);
			double b = steepShare(layered, 8.0);
			double difference = 0.0;
			int land = 0;
			for (int i = 0; i < flat.height.length; i++) {
				if (flat.height[i] > 0.0f && layered.height[i] > 0.0f) {
					difference += Math.abs(flat.height[i] - layered.height[i]);
					land++;
				}
			}
			System.out.printf("[rock-test] seed %d: %.2f%% of land steeper than 8 deg between cells with uniform rock, %.2f%% with layers; heights differ by %.1f m on average%n", seed, 100.0 * a, 100.0 * b, difference / land);
			assertTrue(b > a, "seed " + seed + ": layered rock has more scarps: " + a + " against " + b);
			assertTrue(difference / land > 1.0, "the layers change the ground");
		}
	}

	@Test
	void theSameSeedGivesTheSameRock() {
		Continent a = ContinentSimulator.run(small(7L));
		Continent b = ContinentSimulator.run(small(7L));
		for (int i = 0; i < a.height.length; i++) {
			assertEquals(a.height[i], b.height[i], 0.0f);
			assertEquals(a.hardness[i], b.hardness[i], 0.0f);
		}
	}

	@Test
	void theDialIsAFieldOfTheConfig() {
		assertEquals(1.0, EarthConfig.defaults(1L).rock(), 0.0);
		assertEquals(0.0, EarthConfig.defaults(1L).withRock(0.0).rock(), 0.0);
		assertEquals(0.0, EarthConfig.defaults(1L).withRock(0.0).withSteps(10).withCell(64).rock(), 0.0, "other withers keep the rock dial");
		assertEquals(2.0, EarthConfig.defaults(1L).withRock(2.0).withBelt(2000.0).withPlate(10_000.0, 1.0).withSteepness(1.5).rock(), 0.0);
	}
}
