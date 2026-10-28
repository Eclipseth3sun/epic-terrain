package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The fine relief laid over the simulated ground: where it appears, how it is shaped, and what it must never do. */
class EarthDetailTest {
	private static final EarthConfig CONFIG = EarthConfig.defaults(7L).withPlate(25_000.0, 0.5).withCell(64).withSteps(30);
	private static final Continent CONTINENT = ContinentSimulator.run(CONFIG);
	private static final float[] RELIEF = BiomeClimate.reliefAboveBase(CONTINENT);

	private static double[] pointIn(int i, Random random) {
		return new double[] { CONTINENT.x0 + (i % CONTINENT.nx + random.nextDouble() - 0.5) * CONTINENT.cell,
			CONTINENT.z0 + (i / CONTINENT.nx + random.nextDouble() - 0.5) * CONTINENT.cell };
	}

	/** Random points on land with at least the given relief, up to a count. */
	private static double[][] mountainPoints(double minRelief, int count) {
		Random random = new Random(3);
		double[][] points = new double[count][];
		int taken = 0;
		for (int attempts = 0; attempts < 2_000_000 && taken < count; attempts++) {
			int i = random.nextInt(CONTINENT.nx * CONTINENT.nz);
			if (CONTINENT.height[i] > 60.0f && RELIEF[i] >= minRelief) {
				points[taken++] = pointIn(i, random);
			}
		}
		assertTrue(taken == count, "the test continent has enough ground with " + minRelief + " m of relief: found " + taken);
		return points;
	}

	@Test
	void itIsAFunctionOfPositionOnly() {
		Detail a = new Detail(CONTINENT, Detail.Dials.defaults());
		Detail b = new Detail(CONTINENT, Detail.Dials.defaults());
		// the second pass over the same points reads patches the first pass cached; neighbours must see the same ground in either order
		double[][] points = mountainPoints(600.0, 300);
		double[] first = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			first[i] = a.blocks(points[i][0], points[i][1]);
		}
		for (int i = points.length - 1; i >= 0; i--) {
			assertEquals(first[i], b.blocks(points[i][0], points[i][1]), 0.0, "same position, same ground, whatever was asked before");
			assertEquals(first[i], a.blocks(points[i][0], points[i][1]), 0.0);
		}
	}

	@Test
	void noCragLeavesTheGroundSmooth() {
		Detail d = new Detail(CONTINENT, new Detail.Dials(0.0));
		for (double[] p : mountainPoints(600.0, 200)) {
			assertEquals(d.smoothBlocks(p[0], p[1]), d.blocks(p[0], p[1]), 0.0);
		}
	}

	@Test
	void plainsShoresAndTheSeaFloorAreLeftAlone() {
		Detail d = new Detail(CONTINENT, Detail.Dials.defaults());
		Random random = new Random(11);
		int plains = 0;
		int sea = 0;
		for (int attempts = 0; attempts < 200_000 && (plains < 100 || sea < 100); attempts++) {
			int i = random.nextInt(CONTINENT.nx * CONTINENT.nz);
			double[] p = pointIn(i, random);
			if (CONTINENT.height[i] > 60.0f && RELIEF[i] < 30.0f && plains < 100) {
				plains++;
				// every cell around it is flat too
				boolean flat = true;
				for (int k = -2; k <= 2; k++) {
					for (int l = -2; l <= 2; l++) {
						int j = i + l * CONTINENT.nx + k;
						flat &= j >= 0 && j < RELIEF.length && RELIEF[j] < Detail.RELIEF_FROM * 0.9f;
					}
				}
				if (flat) {
					assertEquals(d.smoothBlocks(p[0], p[1]), d.blocks(p[0], p[1]), 1.0e-9, "a plain stays smooth");
				}
			}
			if (CONTINENT.height[i] < -5.0f && sea < 100) {
				sea++;
				assertEquals(d.smoothBlocks(p[0], p[1]), d.blocks(p[0], p[1]), 0.0, "the sea floor stays smooth");
			}
		}
		assertTrue(plains > 20 && sea > 20, "the test continent has plains and sea: " + plains + ", " + sea);
	}

	@Test
	void landStaysLandAndSeaStaysSea() {
		Detail d = new Detail(CONTINENT, new Detail.Dials(3.0));
		Random random = new Random(5);
		int checked = 0;
		for (int attempts = 0; attempts < 400_000 && checked < 20_000; attempts++) {
			int i = random.nextInt(CONTINENT.nx * CONTINENT.nz);
			double[] p = pointIn(i, random);
			double smooth = d.smoothBlocks(p[0], p[1]);
			double detailed = d.blocks(p[0], p[1]);
			if (RELIEF[i] < Detail.RELIEF_FROM) {
				continue;
			}
			checked++;
			assertTrue((smooth > 0.0) == (detailed > 0.0) || smooth == 0.0, "sea level is crossed only by the simulation: " + smooth + " became " + detailed);
		}
		assertTrue(checked > 1_000, "enough rugged ground was checked: " + checked);
	}

	@Test
	void theDetailIsBoundedAndReallyThereOnHighGround() {
		Detail d = new Detail(CONTINENT, Detail.Dials.defaults());
		double worst = 0.0;
		double meanAll = 0.0;
		for (double[] p : mountainPoints(1_400.0, 400)) {
			double change = Math.abs(d.blocks(p[0], p[1]) - d.smoothBlocks(p[0], p[1]));
			worst = Math.max(worst, change);
			meanAll += change / 400.0;
		}
		assertTrue(worst < 80.0, "no cut or crest goes beyond 80 blocks of the simulated ground: " + worst);
		assertTrue(meanAll > 2.0, "on high ground the detail is really there: " + meanAll + " blocks on average");
		System.out.printf("[detail-test] change from the smooth ground on high ground: mean %.2f, worst %.2f blocks%n", meanAll, worst);
	}

	@Test
	void aStrongerCragIsCraggier() {
		Detail one = new Detail(CONTINENT, new Detail.Dials(1.0));
		Detail two = new Detail(CONTINENT, new Detail.Dials(2.0));
		double a = 0.0;
		double b = 0.0;
		for (double[] p : mountainPoints(900.0, 300)) {
			a += Math.abs(one.blocks(p[0], p[1]) - one.smoothBlocks(p[0], p[1]));
			b += Math.abs(two.blocks(p[0], p[1]) - two.smoothBlocks(p[0], p[1]));
		}
		assertTrue(b > 1.6 * a, "twice the crag is about twice the relief: " + a + " against " + b);
	}

	@Test
	void gulliesRunDownTheSlope() {
		Detail d = new Detail(CONTINENT, Detail.Dials.defaults());
		double[][] points = mountainPoints(1_400.0, 800);
		// the coarser the scale, the straighter its ribs run down the fall line; the finest branch and wander the most
		double[] SPACING = { 120.0, 56.0, 26.0 };
		double[] needed = { 2.0, 1.2, 0.0 }; // the finest scale is more texture than gully, and 30 blocks is more than its spacing: it is only reported
		for (int scale = 0; scale < 3; scale++) {
			double along = 0.0;
			double across = 0.0;
			int n = 0;
			for (double[] p : points) {
				double[] fall = d.fall(p[0], p[1], SPACING[scale]);
				if (fall[1] < 0.9) {
					continue; // no clear fall line here
				}
				double dx = StrictMath.cos(fall[0]);
				double dz = StrictMath.sin(fall[0]);
				double step = 30.0;
				double here = d.gully(scale, p[0], p[1]);
				along += Math.abs(d.gully(scale, p[0] + dx * step, p[1] + dz * step) - here);
				across += Math.abs(d.gully(scale, p[0] - dz * step, p[1] + dx * step) - here);
				n++;
			}
			assertTrue(n > 100, "enough sloping ground was checked: " + n);
			System.out.printf("[detail-test] gully scale %d: over 30 blocks it changes by %.3f along the fall line, %.3f across it, over %d points%n", scale, along / n, across / n, n);
			assertTrue(across >= needed[scale] * along, "scale " + scale + " changes faster across a slope than down it: " + along / n + " down, " + across / n + " across");
		}
	}

	@Test
	void whatTheDetailAddsNeverJumpsFromOneColumnToTheNext() {
		// the simulated ground itself has cliffs where a hard bed overlies a soft one; the guard is on the relief the detail lays over it
		Detail d = new Detail(CONTINENT, Detail.Dials.defaults());
		double worst = 0.0;
		double sum = 0.0;
		int n = 0;
		for (double[] p : mountainPoints(1_400.0, 600)) {
			double here = d.blocks(p[0], p[1]) - d.smoothBlocks(p[0], p[1]);
			for (int k = 0; k < 2; k++) {
				double x = p[0] + (k == 0 ? 1.0 : 0.0);
				double z = p[1] + (k == 1 ? 1.0 : 0.0);
				double step = (d.blocks(x, z) - d.smoothBlocks(x, z)) - here;
				worst = Math.max(worst, Math.abs(step));
				sum += Math.abs(step);
				n++;
			}
		}
		System.out.printf("[detail-test] step of the added relief to the next column: mean %.2f, worst %.2f blocks%n", sum / n, worst);
		// on plain mountain ground the worst is about 5; with layered rock there are scarps and the steepest crags reach nearly 10
		assertTrue(worst < 10.5, "the detail does not jump more than 10 blocks between neighbouring columns: " + worst);
	}
}
