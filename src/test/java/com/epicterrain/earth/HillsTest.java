package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The rolling lowland: where there are hills, how much, and that they leave shores, mountains, rivers and lakes alone. */
class HillsTest {
	private static final long SEED = 4242L;

	/** The standard deviation of the hills of a character and a rain, over many places of high lowland. */
	private static double spread(double character, double rain, double dial) {
		double sum = 0.0;
		double sumSq = 0.0;
		int n = 6_000;
		for (int i = 0; i < n; i++) {
			double h = Hills.offset(SEED, 733.0 * i, -419.0 * (i % 977), 80.0, 0.0, rain, character, dial);
			sum += h;
			sumSq += h * h;
		}
		double mean = sum / n;
		return Math.sqrt(sumSq / n - mean * mean);
	}

	@Test
	void thereAreNoHillsAtTheShoreOnMountainsOrWithTheDialAtZero() {
		for (int i = 0; i < 2_000; i++) {
			double x = 911.0 * i;
			double z = -523.0 * (i % 301);
			assertEquals(0.0, Hills.offset(SEED, x, z, 1.0, 0.0, 700.0, 1.0, 1.0), 0.05, "the shore is clean");
			assertEquals(0.0, Hills.offset(SEED, x, z, 400.0, 2_000.0, 700.0, 1.0, 1.0), 0.0, "mountains have their own relief");
			assertEquals(0.0, Hills.offset(SEED, x, z, 80.0, 0.0, 700.0, 1.0, 0.0), 0.0, "hills 0 is the flat lowland of the simulation");
		}
	}

	@Test
	void hillCountryRollsMoreThanFlatCountryAndWetCountryMoreThanDry() {
		double flat = spread(0.0, 700.0, 1.0);
		double rolling = spread(0.5, 700.0, 1.0);
		double hilly = spread(1.0, 700.0, 1.0);
		assertTrue(rolling > 2.0 * flat && hilly > 1.4 * rolling, "flat, rolling and hilly country: " + flat + ", " + rolling + ", " + hilly);
		assertTrue(hilly > 15.0 && hilly < 55.0, "hill country rolls by some tens of blocks: " + hilly);
		assertTrue(spread(1.0, 1_500.0, 1.0) > 1.15 * spread(1.0, 150.0, 1.0), "wet land rolls more than dry");
		assertTrue(spread(0.5, 700.0, 2.0) > 1.4 * rolling, "the dial scales the hills (a hollow cannot take more than most of the height, so not quite twice)");
	}

	@Test
	void landStaysLand() {
		for (int i = 0; i < 20_000; i++) {
			double base = 3.0 + (i % 90);
			double h = Hills.offset(SEED, 617.0 * i, 281.0 * (i % 1301), base, 0.0, 300.0 + (i % 700), (i % 11) / 10.0, 2.0);
			assertTrue(h >= -0.55 * base - 1.0e-9, "a hollow takes at most most of the height: " + h + " at " + base);
		}
	}

	@Test
	void dryHillCountryIsTerracedIntoMesas() {
		// where it is dry and hilly the hills are steps: more of the ground lies on a level (the top of a step) than in wet hill country of the same size
		double dryLevel = levelShare(150.0);
		double wetLevel = levelShare(1_500.0);
		assertTrue(dryLevel > 1.6 * wetLevel, "dry hill country has flat tops: " + dryLevel + " against " + wetLevel);
	}

	/** The share of places whose ground is within a quarter block of a step's level in country of the given rain, hilly all through. */
	private static double levelShare(double rain) {
		double arid = Hills.aridity(rain);
		double amplitude = Hills.AMPLITUDE * (0.2 + 0.8 * 1.0) * (0.6 + 0.4 * (1.0 - arid));
		int near = 0;
		int n = 30_000;
		for (int i = 0; i < n; i++) {
			double x = 211.0 * i;
			double z = 137.0 * (i % 991);
			double h = Hills.offset(SEED, x, z, 200.0, 0.0, rain, 1.0, 1.0);
			double u = (h + amplitude) / Hills.terrace(SEED, x, z);
			double f = u - Math.floor(u);
			near += (f < 0.03 || f > 0.97) ? 1 : 0;
		}
		return near / (double) n;
	}

	@Test
	void flatRollingAndHillyCountryEachAreAThirdOrSoOfTheLand() {
		int flat = 0;
		int hilly = 0;
		int n = 80_000;
		for (int i = 0; i < n; i++) {
			double c = Hills.character(SEED, 1_777.0 * i, -997.0 * (i % 1117));
			flat += c < 0.2 ? 1 : 0;
			hilly += c > 0.8 ? 1 : 0;
		}
		assertTrue(flat > n * 0.08 && flat < n * 0.40, "flat country: " + flat / (double) n);
		assertTrue(hilly > n * 0.12 && hilly < n * 0.45, "hill country: " + hilly / (double) n);
	}

	@Test
	void theDunesAreLowAndOnlyOnDryFlatLowland() {
		assertEquals(0.0, Hills.dunes(60.0, 0.0, 1_500.0, 0.0, 1.0), 1.0e-9, "none in wet country");
		assertEquals(0.0, Hills.dunes(60.0, 0.0, 100.0, 1.0, 1.0), 1.0e-9, "none in hill country");
		assertEquals(0.0, Hills.dunes(3.0, 0.0, 100.0, 0.0, 1.0), 1.0e-9, "none at the shore");
		assertTrue(Hills.dunes(60.0, 0.0, 100.0, 0.0, 1.0) > 0.9, "dry flat lowland is dune country");
		double lo = 1.0e9;
		double hi = -1.0e9;
		for (int i = 0; i < 20_000; i++) {
			double r = Hills.duneRidge(SEED, 37.0 * i, 29.0 * (i % 503));
			lo = Math.min(lo, r);
			hi = Math.max(hi, r);
		}
		assertTrue(hi <= 1.0 + 1.0e-9 && lo > -2.0, "a dune is a ridge between -2 and 1 of a height of " + Hills.DUNE_HEIGHT + ": " + lo + " to " + hi);
	}

	/** How far the ground varies within 512 blocks, over lowland, in a world with and without the hills. */
	@Test
	void theLowlandOfAWorldRollsWithTheHillsAndNotWithout() {
		Continent c = Seed4242.CONTINENT;
		float[] relief = BiomeClimate.reliefAboveBase(c);
		Detail flat = new Detail(c, new Detail.Dials(1.0, 0.0));
		Detail rolling = new Detail(c, Detail.Dials.defaults());
		Random random = new Random(7);
		double[] flatRange = new double[400];
		double[] rollingRange = new double[400];
		int found = 0;
		for (int attempts = 0; attempts < 2_000_000 && found < 400; attempts++) {
			int i = 4 * c.nx + 4 + random.nextInt((c.nz - 8) * c.nx - 8);
			if (c.height[i] < 250.0f || relief[i] > 120.0f || c.lakeDepth[i] > 0.0f || c.river[i]) {
				continue;
			}
			double x = c.x0 + (i % c.nx) * c.cell;
			double z = c.z0 + (i / c.nx) * c.cell;
			double lo1 = 1e9, hi1 = -1e9, lo2 = 1e9, hi2 = -1e9;
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					double a = flat.blocks(x + 128.0 * dx, z + 128.0 * dz);
					double b = rolling.blocks(x + 128.0 * dx, z + 128.0 * dz);
					lo1 = Math.min(lo1, a);
					hi1 = Math.max(hi1, a);
					lo2 = Math.min(lo2, b);
					hi2 = Math.max(hi2, b);
				}
			}
			flatRange[found] = hi1 - lo1;
			rollingRange[found] = hi2 - lo2;
			found++;
		}
		assertTrue(found > 100, "the test continent has lowland: " + found);
		Arrays.sort(flatRange, 0, found);
		Arrays.sort(rollingRange, 0, found);
		double medianFlat = flatRange[found / 2];
		double medianRolling = rollingRange[found / 2];
		System.out.printf("[hills-test] height range within 512 blocks of lowland: median %.1f blocks without the hills, %.1f with them%n", medianFlat, medianRolling);
		assertTrue(medianRolling > medianFlat + 10.0 && medianRolling > 18.0, "the hills make the lowland roll: " + medianFlat + " to " + medianRolling);
	}
}
