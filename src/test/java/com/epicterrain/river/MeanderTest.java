package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

import org.junit.jupiter.api.Test;

/** How a routed line is given its bends. */
class MeanderTest {
	/** Ground given by a function of position; everything else the planner could ask is irrelevant here. */
	private record Ground(DoubleBinaryOperator height) implements TerrainView {
		@Override
		public double ground(double x, double z) {
			return this.height.applyAsDouble(x, z);
		}

		@Override
		public double uplift(double x, double z) {
			return 0;
		}

		@Override
		public double land(double x, double z) {
			return 0.3;
		}
	}

	private static List<double[]> straight(double length, double spacing) {
		List<double[]> line = new ArrayList<>();
		for (double s = 0; s <= length + 1e-9; s += spacing) {
			line.add(new double[] { s, 0.0 });
		}
		return line;
	}

	private static double arc(List<double[]> line) {
		double arc = 0;
		for (int i = 1; i < line.size(); i++) {
			arc += Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
		}
		return arc;
	}

	private static double sinuosity(List<double[]> line) {
		double[] a = line.get(0);
		double[] b = line.get(line.size() - 1);
		return arc(line) / Math.hypot(b[0] - a[0], b[1] - a[1]);
	}

	/** The sharpest turn between two consecutive segments, in degrees. */
	private static double sharpestTurn(List<double[]> line) {
		double worst = 0;
		for (int i = 1; i < line.size() - 1; i++) {
			double a = Math.atan2(line.get(i)[1] - line.get(i - 1)[1], line.get(i)[0] - line.get(i - 1)[0]);
			double b = Math.atan2(line.get(i + 1)[1] - line.get(i)[1], line.get(i + 1)[0] - line.get(i)[0]);
			double turn = Math.abs(Math.toDegrees(b - a));
			worst = Math.max(worst, turn > 180 ? 360 - turn : turn);
		}
		return worst;
	}

	@Test
	void aWideRiverOnFlatGroundWindsAndStaysSmooth() {
		TerrainView flat = new Ground((x, z) -> 80.0);
		List<double[]> line = straight(8000, 16);
		List<double[]> bent = Meander.apply(line, t -> 60.0, flat, 7L);
		assertEquals(line.size(), bent.size());
		assertTrue(sinuosity(bent) > 1.1, "a wide river on flat ground winds: " + sinuosity(bent));
		assertTrue(sinuosity(bent) < 1.8, "but not into loops: " + sinuosity(bent));
		assertTrue(sharpestTurn(bent) < 30.0, "the bends are smooth at the node spacing: " + sharpestTurn(bent));
	}

	/** The smallest radius, in blocks, of the circle through a point and the ones {@code span} nodes either side of it. */
	private static double tightestRadius(List<double[]> line, int span) {
		double tightest = Double.MAX_VALUE;
		for (int i = span; i + span < line.size(); i++) {
			double[] p = line.get(i - span);
			double[] q = line.get(i);
			double[] r = line.get(i + span);
			double area2 = Math.abs((q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]));
			if (area2 > 1.0e-6) {
				tightest = Math.min(tightest, Math.hypot(q[0] - p[0], q[1] - p[1]) * Math.hypot(r[0] - q[0], r[1] - q[1]) * Math.hypot(r[0] - p[0], r[1] - p[1]) / (2.0 * area2));
			}
		}
		return tightest;
	}

	@Test
	void aBendIsNeverTighterThanTheRiverIsWide() {
		TerrainView flat = new Ground((x, z) -> 80.0);
		for (double width : new double[] { 12.0, 30.0, 60.0, 140.0 }) {
			for (long seed = 1; seed <= 6; seed++) {
				List<double[]> bent = Meander.apply(straight(12000, 16), t -> width, flat, seed);
				// measured over about a width of line either side, which is what the channel has to turn through
				int span = (int) Math.max(2, Math.round(width / 16.0));
				assertTrue(tightestRadius(bent, span) >= width, "width " + width + " seed " + seed + ": a bend of radius " + tightestRadius(bent, span));
			}
		}
	}

	@Test
	void theTwoEndsStayWhereTheyAre() {
		TerrainView flat = new Ground((x, z) -> 80.0);
		List<double[]> line = straight(5000, 16);
		List<double[]> bent = Meander.apply(line, t -> 40.0, flat, 11L);
		assertArrayEquals(line.get(0), bent.get(0), 1e-9, "the source stays put");
		assertArrayEquals(line.get(line.size() - 1), bent.get(bent.size() - 1), 1e-9, "the mouth stays put: it has to meet its sea or river");
		// and the line is eased in and out, not thrown sideways
		double[] near = bent.get(3);
		assertTrue(Math.abs(near[1]) < 8.0, "next to the source it has hardly moved: " + near[1]);
	}

	@Test
	void aSteepRiverKeepsTheLineItWasRouted() {
		// a grade of one block in five
		TerrainView steep = new Ground((x, z) -> 400.0 - 0.2 * x);
		List<double[]> line = straight(3000, 16);
		List<double[]> bent = Meander.apply(line, t -> 40.0, steep, 3L);
		for (int i = 0; i < line.size(); i++) {
			assertArrayEquals(line.get(i), bent.get(i), 0.5, "node " + i + " must not move on steep ground");
		}
	}

	@Test
	void aBendNeverClimbsOutOfTheValley() {
		// a valley whose floor is the x axis and whose sides rise steeply: the river may wind within the floor, not up the sides
		TerrainView valley = new Ground((x, z) -> 80.0 + Math.max(0.0, Math.abs(z) - 12.0) * 2.0);
		List<double[]> line = straight(6000, 16);
		List<double[]> bent = Meander.apply(line, t -> 60.0, valley, 5L);
		for (double[] p : bent) {
			double rise = valley.ground(p[0], p[1]) - 80.0;
			assertTrue(rise <= Meander.CLIMB + 0.25 * 60.0 + 1.0, "a bend climbed " + rise + " blocks at " + p[0] + "," + p[1]);
		}
		assertTrue(bent.stream().anyMatch(p -> Math.abs(p[1]) > 2.0), "it should still wind a little inside the floor");
	}

	@Test
	void theSameInputsGiveTheSameBendsAndADifferentSeedGivesDifferentOnes() {
		TerrainView flat = new Ground((x, z) -> 80.0);
		List<double[]> line = straight(4000, 16);
		List<double[]> a = Meander.apply(line, t -> 50.0, flat, 21L);
		List<double[]> b = Meander.apply(line, t -> 50.0, flat, 21L);
		List<double[]> c = Meander.apply(line, t -> 50.0, flat, 22L);
		boolean same = true;
		boolean differs = false;
		for (int i = 0; i < a.size(); i++) {
			same &= a.get(i)[0] == b.get(i)[0] && a.get(i)[1] == b.get(i)[1];
			differs |= Math.abs(a.get(i)[1] - c.get(i)[1]) > 1.0;
		}
		assertTrue(same, "deterministic");
		assertTrue(differs, "the seed changes the bends");
	}

	@Test
	void wideRiversHaveLongBendsAndNarrowOnesShort() {
		TerrainView flat = new Ground((x, z) -> 80.0);
		List<double[]> line = straight(8000, 16);
		int narrow = zeroCrossings(Meander.apply(line, t -> 6.0, flat, 9L));
		int wide = zeroCrossings(Meander.apply(line, t -> 90.0, flat, 9L));
		assertTrue(narrow > wide * 2, "a narrow river bends more often than a wide one: " + narrow + " vs " + wide);
		assertFalse(wide == 0, "a wide river still bends");
	}

	private static int zeroCrossings(List<double[]> line) {
		int crossings = 0;
		for (int i = 1; i < line.size(); i++) {
			if (Math.signum(line.get(i)[1]) * Math.signum(line.get(i - 1)[1]) < 0) {
				crossings++;
			}
		}
		return crossings;
	}
}
