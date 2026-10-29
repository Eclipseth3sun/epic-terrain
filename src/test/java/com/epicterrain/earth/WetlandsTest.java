package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** The marsh: where it is, how marshy its ground is, and that a swamp is where the ground is marsh. */
class WetlandsTest {
	private static final long SEED = 4242L;

	@Test
	void onlyLowInlandGroundIsMarsh() {
		int low = 0;
		int high = 0;
		int shore = 0;
		for (int i = 0; i < 20_000; i++) {
			double mx = 977.0 * i;
			double mz = -613.0 * (i % 1013);
			low += Wetlands.weight(SEED, mx, mz, 1.0, 1500.0, 1200.0) > 0.0 ? 1 : 0;
			high += Wetlands.weight(SEED, mx, mz, 20.0, 1500.0, 1200.0) > 0.0 ? 1 : 0;
			shore += Wetlands.weight(SEED, mx, mz, 1.0, 20.0, 1200.0) > 0.0 ? 1 : 0;
		}
		assertTrue(low > 1000, "there is marsh on low ground: " + low + " of 20000");
		assertEquals(0, high, "no marsh on ground more than a few blocks above the sea");
		assertEquals(0, shore, "no marsh on the shore itself (that is a beach)");
	}

	@Test
	void aDryContinentStillHasMarshAndAWetOneHasMore() {
		int dry = 0;
		int wet = 0;
		for (int i = 0; i < 20_000; i++) {
			double mx = 977.0 * i;
			double mz = -613.0 * (i % 1013);
			dry += Wetlands.isMarsh(Wetlands.weight(SEED, mx, mz, 1.0, 800.0, 300.0)) ? 1 : 0;
			wet += Wetlands.isMarsh(Wetlands.weight(SEED, mx, mz, 1.0, 800.0, 1500.0)) ? 1 : 0;
		}
		assertTrue(dry > 100, "even rain-shadow lowland has its marshes: " + dry);
		assertTrue(wet > dry * 3 / 2, "rain makes more: " + wet + " against " + dry);
	}

	@Test
	void theGroundOfAMarshIsAtTheSeaLevelWithAThirdOfItWaterLikeVanillasSwamps() {
		// measured on vanilla's own swamps: the top solid block is, against y 62, 3 under at the 10th percentile, level in the middle and 6 over at the 90th; a third of the
		// columns are water. A column is water where its ground is a block or more under the sea.
		int n = 60_000;
		int[] up = new int[n];
		int flooded = 0;
		for (int i = 0; i < n; i++) {
			double g = Wetlands.ground(SEED, 31.0 * (i % 300) + 0.37 * i, 29.0 * (i / 300) + 0.11 * i);
			up[i] = (int) Math.ceil(g);
			flooded += g <= -1.0 ? 1 : 0;
		}
		Arrays.sort(up);
		assertTrue(up[n / 10] >= -5 && up[n / 10] <= -2, "10th percentile " + up[n / 10]);
		assertTrue(up[n / 2] >= -1 && up[n / 2] <= 1, "median " + up[n / 2]);
		assertTrue(up[n * 9 / 10] >= 3 && up[n * 9 / 10] <= 7, "90th percentile " + up[n * 9 / 10]);
		double share = flooded / (double) n;
		assertTrue(share > 0.28 && share < 0.46, "flooded share " + share);
	}

	@Test
	void theEdgeOfAMarshIsAShortSlopeAndItsMiddleIsAllMarsh() {
		assertEquals(0.0, Wetlands.blend(0.0), 0.0);
		assertEquals(1.0, Wetlands.blend(0.6), 1.0e-12, "from a weight of 0.6 the ground is all marsh");
		assertTrue(!Wetlands.isMarsh(0.2) && Wetlands.isMarsh(0.4));
	}
}
