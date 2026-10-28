package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import com.epicterrain.earth.Rivers.River;

import org.junit.jupiter.api.Test;

/** The rivers worked into the ground: the water profile, the cross-section, the water as a level, and that nothing reaches beyond a river. */
class EarthRiverValleyTest {
	private static final Continent C = Seed4242.CONTINENT;
	private static final Detail D = Seed4242.DETAIL;
	private static final List<River> RIVERS = Seed4242.RIVERS;
	private static final RiverValley V = Seed4242.VALLEY;

	/** A unit normal to the river at a node. */
	private static double[] normal(River r, int k) {
		int a = Math.max(0, k - 1);
		int b = Math.min(r.line.size() - 1, k + 1);
		double tx = r.line.get(b)[0] - r.line.get(a)[0];
		double tz = r.line.get(b)[1] - r.line.get(a)[1];
		double t = Math.hypot(tx, tz);
		return new double[] { -tz / t, tx / t };
	}

	@Test
	void theWaterOnlyEverFallsAndStaysAboveTheSea() {
		for (River r : RIVERS) {
			for (int k = 0; k < r.line.size(); k++) {
				double level = V.waterAt(r.index, k);
				assertTrue(level >= 0.0, "river " + r.index + " node " + k + " is under the sea: " + level);
				if (k > 0) {
					assertTrue(level <= V.waterAt(r.index, k - 1) + 1.0e-9, "river " + r.index + " rises downstream at node " + k);
				}
			}
		}
	}

	@Test
	void aTributaryEndsAtTheLevelOfTheRiverItJoinsAndNoRiverFallsLikeAWaterfallBetweenNodes() {
		double steepest = 0.0;
		for (River r : RIVERS) {
			int end = r.line.size() - 1;
			if (r.joins >= 0) {
				RiverValleyHost host = new RiverValleyHost(r, RIVERS.get(r.joins));
				assertEquals(host.levelAtJoin(), V.waterAt(r.index, end), 1.0e-6, "river " + r.index + " ends at the level of river " + r.joins);
			}
			for (int k = 1; k < r.line.size(); k++) {
				steepest = Math.max(steepest, V.waterAt(r.index, k - 1) - V.waterAt(r.index, k));
			}
		}
		System.out.printf("[valley-test] the steepest drop of the water between two nodes (16 blocks) is %.2f blocks%n", steepest);
		// a river that crosses the edge of a hard bed falls in a ledge: up to the fall limit and the gorge cap, shaped as a waterfall in a later stage
		assertTrue(steepest < 12.0, "no drop between neighbouring nodes over 12 blocks: " + steepest);
	}

	/** Where a tributary meets its river. */
	private record RiverValleyHost(River river, River host) {
		double levelAtJoin() {
			double[] end = this.river.line.get(this.river.line.size() - 1);
			double nearest = Double.MAX_VALUE;
			double level = 0.0;
			for (int k = 0; k < this.host.line.size(); k++) {
				double d = Math.hypot(this.host.line.get(k)[0] - end[0], this.host.line.get(k)[1] - end[1]);
				if (d < nearest) {
					nearest = d;
					level = V.waterAt(this.host.index, k);
				}
			}
			return level;
		}
	}

	@Test
	void theBedIsUnderTheWaterAndWaterFillsTheChannel() {
		int checked = 0;
		for (River r : RIVERS) {
			double travelled = 0.0;
			for (int k = 1; k < r.line.size() - 1; k++) {
				travelled += Math.hypot(r.line.get(k)[0] - r.line.get(k - 1)[0], r.line.get(k)[1] - r.line.get(k - 1)[1]);
				if (travelled < 4.0 * r.width[k] + 80.0 || r.width[k] < 4.0) {
					continue; // the head of a river is a shallow trickle
				}
				double[] out = new double[2];
				V.column(r.line.get(k)[0], r.line.get(k)[1], out);
				double level = V.waterAt(r.index, k);
				assertTrue(out[0] <= level - 1.0, "river " + r.index + " node " + k + ": the bed is " + (level - out[0]) + " blocks under the water");
				assertTrue(out[1] >= level - 1.0e-4, "water over the middle of the river, at the river's level or a lake's higher one: " + out[1] + " against " + level);
				double[] n = normal(r, k);
				for (int side = -1; side <= 1; side += 2) {
					double offset = 0.6 * r.width[k] / 2.0;
					V.column(r.line.get(k)[0] + side * n[0] * offset, r.line.get(k)[1] + side * n[1] * offset, out);
					assertTrue(!Double.isNaN(out[1]), "river " + r.index + " node " + k + ": water 60% of the way to the bank");
				}
				checked++;
			}
		}
		assertTrue(checked > 10_000, "plenty of river checked: " + checked);
	}

	@Test
	void theBanksStandAboveTheWaterAndAreNotBuiltUpOrCutLikeATrench() {
		Random random = new Random(9);
		int checked = 0;
		int perched = 0;
		double highestBank = 0.0;
		for (int attempt = 0; attempt < 40_000 && checked < 3_000; attempt++) {
			River r = RIVERS.get(random.nextInt(RIVERS.size()));
			if (r.mouthWidth() < 5.0 || r.line.size() < 10) {
				continue;
			}
			int k = 2 + random.nextInt(r.line.size() - 4);
			double[] n = normal(r, k);
			double half = r.width[k] / 2.0;
			double level = V.waterAt(r.index, k);
			for (int side = -1; side <= 1; side += 2) {
				double offset = half + Math.max(1.5, 0.18 * r.width[k]) * 2.0 + 2.0;
				double wx = r.line.get(k)[0] + side * n[0] * offset;
				double wz = r.line.get(k)[1] + side * n[1] * offset;
				double[] out = new double[2];
				V.column(wx, wz, out);
				if (!Double.isNaN(out[1])) {
					continue; // another river's channel or a lake
				}
				checked++;
				perched += out[0] < level - 1.0e-9 ? 1 : 0;
				highestBank = Math.max(highestBank, out[0] - level);
			}
		}
		System.out.printf("[valley-test] ground beside the banks: %d of %d samples lower than the water (a perched river); highest bank above the water %.1f blocks%n", perched, checked, highestBank);
		assertTrue(checked > 1_000);
		// along the edge of a hard bed or a ledge the ground beside a bank can fall away more than the floodplain can build it up (3 blocks)
		assertTrue(perched < 0.10 * checked, "a river is perched above the ground beside it at under 10% of places: " + perched + " of " + checked);
	}

	@Test
	void theValleyNeverBuildsTheGroundUpByMoreThanAFewBlocksOrReachesFarFromARiver() {
		Random random = new Random(3);
		double raised = 0.0;
		int far = 0;
		int tested = 0;
		for (int attempt = 0; attempt < 6_000; attempt++) {
			double wx = C.x0 + random.nextDouble() * (C.nx - 1) * C.cell;
			double wz = C.z0 + random.nextDouble() * (C.nz - 1) * C.cell;
			double[] out = new double[2];
			V.column(wx, wz, out);
			double before = D.blocks(wx, wz);
			raised = Math.max(raised, out[0] - before);
			// the ground a long way from every river is exactly the ground without them
			double nearest = Double.MAX_VALUE;
			for (River r : RIVERS) {
				for (double[] node : r.line) {
					nearest = Math.min(nearest, Math.hypot(node[0] - wx, node[1] - wz));
				}
			}
			if (nearest > 500.0) {
				far++;
				assertEquals(before, out[0], 0.0, "far from every river the ground is untouched");
			}
			tested++;
		}
		assertTrue(raised <= RiverValley.MAX_RAISE + 1.0e-9, "the ground is built up by at most " + RiverValley.MAX_RAISE + " blocks: " + raised);
		assertTrue(far > 500, "enough points far from the rivers: " + far + " of " + tested);
	}

	@Test
	void theValleyCutsWithinItsLimits() {
		// Usually the cut is the floodplain's pull (at most 14) plus a bed of at most 8 blocks, deeper (1.6 times) on the outside of a bend. Where a
		// hard bed makes a cliff the line of a wide river can run along the top of it, 60 blocks above the water: a gorge, for a stretch.
		double deepest = 0.0;
		int nodes = 0;
		int deep = 0;
		for (River r : RIVERS) {
			for (int k = 0; k < r.line.size(); k += 3) {
				double[] out = new double[2];
				V.column(r.line.get(k)[0], r.line.get(k)[1], out);
				double cut = D.blocks(r.line.get(k)[0], r.line.get(k)[1]) - out[0];
				deepest = Math.max(deepest, cut);
				deep += cut > RiverValley.MAX_PULL_CUT + 1.6 * 8.0 + 1.0 ? 1 : 0;
				nodes++;
			}
		}
		System.out.printf("[valley-test] the deepest the ground is cut along a river's line: %.1f blocks; %d of %d nodes are cut more than %.0f%n", deepest, deep, nodes, RiverValley.MAX_PULL_CUT + 1.6 * 8.0 + 1.0);
		assertTrue(deep < 0.01 * nodes, "under 1% of the nodes are cut deeper than the floodplain's pull plus a bed: " + deep + " of " + nodes);
		assertTrue(deepest < 100.0, "no cut deeper than 100 blocks: " + deepest);
	}

	@Test
	void whereTheWaterIsAColumnIsUnderTheLevelAndEveryColumnUnderALevelNextToTheRiverHasWater() {
		Random random = new Random(21);
		int water = 0;
		int dry = 0;
		for (int attempt = 0; attempt < 30_000 && water < 2_000; attempt++) {
			River r = RIVERS.get(random.nextInt(RIVERS.size()));
			int k = random.nextInt(r.line.size());
			double[] n = normal(r, Math.min(r.line.size() - 1, Math.max(0, k)));
			double lateral = (random.nextDouble() * 2.0 - 1.0) * r.width[k] * 0.9;
			double wx = r.line.get(k)[0] + n[0] * lateral;
			double wz = r.line.get(k)[1] + n[1] * lateral;
			double[] out = new double[2];
			V.column(wx, wz, out);
			if (!Double.isNaN(out[1])) {
				water++;
				assertTrue(out[0] < out[1] + 1.0e-9, "water stands over ground that is under its level");
			} else {
				dry++;
			}
		}
		assertTrue(water > 500, "points in the channels have water: " + water + " against " + dry + " without");
	}

	@Test
	void lakesAreWaterAtTheirOwnLevel() {
		int lakeCells = 0;
		int withWater = 0;
		double[] out = new double[2];
		for (int i = 0; i < C.nx * C.nz; i++) {
			if (C.lakeDepth[i] <= 0.0f) {
				continue;
			}
			// the middle of a lake: all nine cells around are lake
			int x = i % C.nx;
			int z = i / C.nx;
			boolean inside = x > 0 && z > 0 && x < C.nx - 1 && z < C.nz - 1;
			for (int dz = -1; dz <= 1 && inside; dz++) {
				for (int dx = -1; dx <= 1 && inside; dx++) {
					inside = C.lakeDepth[(z + dz) * C.nx + x + dx] > 0.0f;
				}
			}
			if (!inside) {
				continue;
			}
			lakeCells++;
			V.column(C.x0 + x * C.cell, C.z0 + z * C.cell, out);
			if (!Double.isNaN(out[1])) {
				withWater++;
				assertEquals(Continent.blocksAbove(C.height[i] + C.lakeDepth[i]), out[1], 0.6, "a lake's water is at the lake's surface");
			}
		}
		System.out.printf("[valley-test] %d lake-interior cells, %d with water at their centre%n", lakeCells, withWater);
		assertTrue(lakeCells > 20 && withWater > 0.95 * lakeCells, "lakes are water: " + withWater + " of " + lakeCells);
	}

	@Test
	void aRiverThatLeavesALakeStartsAtTheLakesLevel() {
		int outlets = 0;
		for (River r : RIVERS) {
			int first = r.cells[0];
			double lake = Double.NaN;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int x = first % C.nx + dx;
					int z = first / C.nx + dz;
					if (x < 0 || z < 0 || x >= C.nx || z >= C.nz) {
						continue;
					}
					int nb = z * C.nx + x;
					if (C.lakeDepth[nb] > 0.0f && C.receiver[nb] == first) {
						lake = Math.max(Double.isNaN(lake) ? -1.0e9 : lake, Continent.blocksAbove(C.height[nb] + C.lakeDepth[nb]));
					}
				}
			}
			if (!Double.isNaN(lake)) {
				outlets++;
				assertTrue(V.waterAt(r.index, 0) >= lake - 1.0e-3, "river " + r.index + " leaves a lake at " + lake + " but starts at " + V.waterAt(r.index, 0));
			}
		}
		System.out.printf("[valley-test] %d rivers leave a lake%n", outlets);
		assertTrue(outlets > 3, "the window has lake outlets: " + outlets);
	}

	@Test
	void theWaterLevelStepsRarelyOnBigRivers() {
		for (River r : RIVERS) {
			if (r.mouthWidth() < 20.0 || r.length < 3_000.0) {
				continue;
			}
			int steps = 0;
			long previous = Math.round(V.waterAt(r.index, 0));
			for (int k = 1; k < r.line.size(); k++) {
				long now = Math.round(V.waterAt(r.index, k));
				steps += now != previous ? 1 : 0;
				previous = now;
			}
			// over hard-bed ledges a big river steps more often than over the plain: not more than once every 40 blocks
			assertTrue(r.length / Math.max(1, steps) > 40.0, "river " + r.index + " steps once every " + r.length / Math.max(1, steps) + " blocks");
		}
	}

	@Test
	void buildingTwiceAndAskingInAnyOrderGivesTheSameGround() {
		RiverValley again = new RiverValley(C, RIVERS, D, RiverValley.Dials.defaults());
		Random random = new Random(5);
		double[][] points = new double[300][];
		double[] first = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			River r = RIVERS.get(random.nextInt(RIVERS.size()));
			int k = random.nextInt(r.line.size());
			points[i] = new double[] { r.line.get(k)[0] + random.nextGaussian() * 30.0, r.line.get(k)[1] + random.nextGaussian() * 30.0 };
			first[i] = V.ground(points[i][0], points[i][1]);
		}
		for (int i = points.length - 1; i >= 0; i--) {
			assertEquals(first[i], again.ground(points[i][0], points[i][1]), 0.0);
			assertEquals(V.water(points[i][0], points[i][1]), again.water(points[i][0], points[i][1]), 0.0);
		}
	}
}
