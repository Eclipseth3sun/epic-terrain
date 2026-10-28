package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.epicterrain.worldgen.RangeCatalog;
import com.epicterrain.worldgen.RangeShape;

import org.junit.jupiter.api.Test;

/** River planning on a small invented world, so every rule can be checked exactly and quickly. */
class RiverPlannerTest {
	/**
	 * A mountain wall along the z axis with plain on either side. A vanilla river (ground 55) runs along x = +-2450 and the open
	 * sea (ground 40, ocean biome) starts at x = +-6200 / +-6500.
	 */
	static class Synthetic implements TerrainView, RangeCatalog {
		static final RangeShape WALL = new RangeShape(0, 0, 0, RangeShape.Type.LINE, true, 0, 0, Math.PI / 2, 6000, 0, 0, 0.5, 0.14);

		@Override
		public double ground(double x, double z) {
			double ax = Math.abs(x);
			if (ax >= 6200) {
				return 40;
			}
			if (ax >= 2400 && ax <= 2500) {
				return 55;
			}
			return 70 + this.uplift(x, z);
		}

		@Override
		public double uplift(double x, double z) {
			return 330 * Math.exp(-(x / 800) * (x / 800));
		}

		@Override
		public double land(double x, double z) {
			return Math.abs(x) >= 6500 ? -0.6 : 0.3;
		}

		@Override
		public List<RangeShape> shapesInCell(int cellX, int cellZ) {
			return cellX == 0 && cellZ == 0 ? List.of(WALL) : List.of();
		}

		@Override
		public int cellSize() {
			return 6144;
		}

		@Override
		public double[] warp(double x, double z) {
			return new double[] { 0, 0 };
		}
	}

	private static RiverPlanner planner(double grandChance) {
		Synthetic world = new Synthetic();
		return new RiverPlanner(99L, RiverConfig.DEFAULT.withGrandChance(grandChance).withMinWaterKm2(0.0), world, world);
	}

	@Test
	void tributariesRiseOnTheFlanksAndEndInWater() {
		RangeRivers plan = planner(0.0).plan(Synthetic.WALL);
		assertFalse(plan.grandRolled());
		assertTrue(plan.rivers().size() >= 2, "a long range gets several tributaries: " + plan.rivers().size());
		Synthetic world = new Synthetic();
		for (River river : plan.rivers()) {
			System.out.printf("[rivers] %s: %s, %.0f m, source (%.0f, %.0f) y%.0f, ends %s at (%.0f, %.0f), %d falls%n", river.id(), river.kind(), river.length(),
				river.source().x(), river.source().z(), river.source().ground(), river.ending(), river.mouth().x(), river.mouth().z(), river.falls().size());
			assertEquals(River.Kind.TRIBUTARY, river.kind());
			RiverAsserts.assertValid(river, RiverConfig.DEFAULT, plan.rivers());
			assertTrue(river.source().ground() > 150, river.id() + " rises high on the wall: " + river.source().ground());
			assertTrue(Math.abs(river.source().x()) < 1500, river.id() + " rises on the wall, not out on the plain");
			if (river.ending() == River.Ending.VANILLA_WATER) {
				assertTrue(Math.abs(Math.abs(river.mouth().x()) - 2450) < 150, river.id() + " ends in the vanilla river: " + river.mouth().x());
			}
			if (river.ending() == River.Ending.VANILLA_WATER) {
				assertFalse(river.falls().isEmpty(), river.id() + " comes all the way down the wall, so it has a waterfall");
			}
		}
		assertTrue(plan.rivers().stream().anyMatch(r -> !r.falls().isEmpty()), "the wall is steep: somewhere there is a waterfall");
		// both flanks are used
		assertTrue(plan.rivers().stream().anyMatch(r -> r.source().x() < 0) && plan.rivers().stream().anyMatch(r -> r.source().x() > 0), "both sides of the wall");
	}

	@Test
	void theWaterNeverStandsAboveItsBanks() {
		// a river is cut into the ground, so where the ground beside it is lower than the river's own line the water is set lower
		RangeRivers plan = planner(1.0).plan(Synthetic.WALL);
		Synthetic world = new Synthetic();
		int checked = 0;
		for (River river : plan.rivers()) {
			List<River.Node> nodes = river.nodes();
			for (int i = 1; i < nodes.size() - 1; i++) {
				River.Node n = nodes.get(i);
				if (n.water() <= River.SEA_LEVEL + 1e-9) {
					continue;
				}
				River.Node a = nodes.get(i - 1);
				River.Node b = nodes.get(i + 1);
				double t = Math.hypot(b.x() - a.x(), b.z() - a.z());
				double reach = Channel.halfWidth(n) + 2.0;
				double nx = -(b.z() - a.z()) / t * reach;
				double nz = (b.x() - a.x()) / t * reach;
				double bank = Math.min(world.ground(n.x() + nx, n.z() + nz), world.ground(n.x() - nx, n.z() - nz));
				double allowedDip = n.ground() - 2.0 - 10.0; // the most the water is ever lowered to clear a bank
				assertTrue(n.water() <= Math.max(allowedDip, bank) + 1e-6, river.id() + " node " + i + ": water " + n.water() + " above a bank at " + bank);
				checked++;
			}
		}
		assertTrue(checked > 100, "enough nodes to mean something: " + checked);
	}

	@Test
	void aWideRiverOnTheGentlePlainWindsSmoothly() {
		RangeRivers plan = planner(1.0).plan(Synthetic.WALL);
		River grand = plan.rivers().stream().filter(r -> r.kind() == River.Kind.GRAND).findFirst().orElseThrow();
		List<River.Node> nodes = grand.nodes();
		// the stretch across the plain, past the foot of the wall and the vanilla river
		List<River.Node> plain = nodes.stream().filter(n -> Math.abs(n.x()) > 3500 && Math.abs(n.x()) < 6000).toList();
		assertTrue(plain.size() > 40, "the grand river runs a good way over the plain: " + plain.size());
		double arc = 0;
		for (int i = 1; i < plain.size(); i++) {
			arc += Math.hypot(plain.get(i).x() - plain.get(i - 1).x(), plain.get(i).z() - plain.get(i - 1).z());
		}
		River.Node first = plain.get(0);
		River.Node last = plain.get(plain.size() - 1);
		double sinuosity = arc / Math.hypot(last.x() - first.x(), last.z() - first.z());
		System.out.printf("[rivers] grand river over the plain: %.0f blocks of river for %.0f as the crow flies (sinuosity %.2f)%n", arc,
			Math.hypot(last.x() - first.x(), last.z() - first.z()), sinuosity);
		assertTrue(sinuosity > 1.05, "a wide river on gentle ground must not run straight: " + sinuosity);
		double worstTurn = 0;
		for (int i = 1; i < plain.size() - 1; i++) {
			double u = Math.atan2(plain.get(i).z() - plain.get(i - 1).z(), plain.get(i).x() - plain.get(i - 1).x());
			double v = Math.atan2(plain.get(i + 1).z() - plain.get(i).z(), plain.get(i + 1).x() - plain.get(i).x());
			double turn = Math.abs(Math.toDegrees(v - u));
			worstTurn = Math.max(worstTurn, turn > 180 ? 360 - turn : turn);
		}
		assertTrue(worstTurn < 45.0, "its bends are smooth: sharpest turn " + worstTurn);
	}

	@Test
	void sourcesKeepTheirDistance() {
		RangeRivers plan = planner(0.0).plan(Synthetic.WALL);
		for (int i = 0; i < plan.rivers().size(); i++) {
			for (int j = i + 1; j < plan.rivers().size(); j++) {
				River.Node a = plan.rivers().get(i).source();
				River.Node b = plan.rivers().get(j).source();
				assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) >= RiverConfig.DEFAULT.minSourceSpacing() - 1,
					plan.rivers().get(i).id() + " and " + plan.rivers().get(j).id() + " start too close together");
			}
		}
	}

	@Test
	void aGrandRiverRunsAllTheWayToTheSea() {
		RangeRivers plan = planner(1.0).plan(Synthetic.WALL);
		assertTrue(plan.grandRolled());
		assertTrue(plan.hasGrand(), "the sea is in reach, so the grand river must exist");
		River grand = plan.rivers().stream().filter(r -> r.kind() == River.Kind.GRAND).findFirst().orElseThrow();
		assertEquals(1, plan.rivers().stream().filter(r -> r.kind() == River.Kind.GRAND).count(), "at most one grand river per range");
		RiverAsserts.assertValid(grand, RiverConfig.DEFAULT, plan.rivers());
		assertEquals(River.Ending.SEA, grand.ending());
		assertTrue(Math.abs(grand.mouth().x()) >= 6150, "the mouth is at the sea: " + grand.mouth().x());
		assertTrue(grand.length() >= 5000, "it runs the whole way: " + grand.length());
		assertTrue(grand.mouth().width() >= 70, "a grand river is wide at its mouth: " + grand.mouth().width());
		assertTrue(grand.mouth().width() > grand.source().width() * 10, "and far narrower at its source");
	}

	@Test
	void noGrandRiverWithoutASeaInReach() {
		Synthetic world = new Synthetic();
		RiverConfig tight = RiverConfig.DEFAULT.withGrandChance(1.0).withMinWaterKm2(0.0).withGrandReach(2000);
		RangeRivers plan = new RiverPlanner(99L, tight, world, world).plan(Synthetic.WALL);
		assertTrue(plan.grandRolled());
		assertFalse(plan.hasGrand(), "the sea is 5 km away and the reach is 2 km");
		assertFalse(plan.rivers().isEmpty(), "the tributaries are planned all the same");
	}

	@Test
	void aSeaThatIsTooSmallIsNotATarget() {
		Synthetic world = new Synthetic();
		RiverConfig demanding = RiverConfig.DEFAULT.withGrandChance(1.0).withMinWaterKm2(0.0).withSeaMinKm2(1.0e9);
		assertFalse(new RiverPlanner(99L, demanding, world, world).plan(Synthetic.WALL).hasGrand());
	}

	@Test
	void theGrandRiverChanceIsAboutFivePercent() {
		RiverPlanner planner = planner(0.05);
		int rolled = 0;
		int total = 40_000;
		for (int i = 0; i < total; i++) {
			RangeShape shape = new RangeShape(i % 200 - 100, i / 200 - 100, i % 4, RangeShape.Type.LINE, false, 0, 0, 0, 4000, 0, 0, 0, 0.14);
			rolled += planner.rollsGrand(shape) ? 1 : 0;
		}
		double rate = (double) rolled / total;
		assertTrue(Math.abs(rate - 0.05) < 0.006, "grand chance should be about 5%, was " + rate);
		assertFalse(planner(0.0).rollsGrand(Synthetic.WALL));
		assertTrue(planner(1.0).rollsGrand(Synthetic.WALL));
	}

	@Test
	void planningIsAPureFunction() {
		RangeRivers first = planner(1.0).plan(Synthetic.WALL);
		RiverPlanner other = planner(1.0);
		// planning something else in between must not change the answer
		other.plan(new RangeShape(0, 0, 1, RangeShape.Type.LINE, false, 500, 0, Math.PI / 2, 3000, 0, 0, 0.2, 0.14));
		assertEquals(first, other.plan(Synthetic.WALL), "the same range must always get the same rivers");
		assertTrue(!first.equals(new RiverPlanner(100L, RiverConfig.DEFAULT.withGrandChance(1.0).withMinWaterKm2(0.0), new Synthetic(), new Synthetic()).plan(Synthetic.WALL)),
			"a different world seed must give different rivers");
	}

	@Test
	void widthAndDepthGrowDownstreamAndTheMouthFlares() {
		for (RiverConfig.Profile profile : List.of(RiverConfig.Profile.TRIBUTARY, RiverConfig.Profile.GRAND)) {
			double length = 4000;
			assertEquals(profile.sourceWidth(), profile.widthAt(0, length, true), 1.0e-9);
			assertEquals(profile.sourceDepth(), profile.depthAt(0, length, true), 1.0e-9);
			double previousWidth = 0;
			double previousDepth = 0;
			for (double s = 0; s <= length; s += 40) {
				assertTrue(profile.widthAt(s, length, true) >= previousWidth - 1.0e-9);
				assertTrue(profile.depthAt(s, length, true) >= previousDepth - 1.0e-9);
				previousWidth = profile.widthAt(s, length, true);
				previousDepth = profile.depthAt(s, length, true);
			}
			double plainMouth = profile.widthAt(length, length, false);
			assertEquals(plainMouth * profile.flareFactor(), profile.widthAt(length, length, true), 1.0e-9, "the mouth flares by the flare factor");
			// by halfway most of the depth is there, the lower river gets wider rather than deeper
			double depthShare = (profile.depthAt(length / 2, length, false) - profile.sourceDepth()) / (profile.finalDepth(4.0) - profile.sourceDepth());
			double widthShare = (profile.widthAt(length / 2, length, false) - profile.sourceWidth()) / (profile.mouthWidth(4.0) - profile.sourceWidth());
			assertTrue(depthShare > 0.7 && depthShare > widthShare, "depth arrives sooner than width: " + depthShare + " vs " + widthShare);
		}
		// longer rivers are wider at the mouth, and a grand river is far wider than a tributary
		assertTrue(RiverConfig.Profile.TRIBUTARY.mouthWidth(3) > RiverConfig.Profile.TRIBUTARY.mouthWidth(1));
		assertTrue(RiverConfig.Profile.GRAND.mouthWidth(20) > 3 * RiverConfig.Profile.TRIBUTARY.mouthWidth(3));
	}
}
