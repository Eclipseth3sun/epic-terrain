package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.epicterrain.worldgen.RangeShape;

import org.junit.jupiter.api.Test;

/** River planning on the real terrain of an Epic Terrain world: every rule must hold there too, and the plan must not depend on order. */
class RiverPlannerWorldTest {
	private static final long SEED = 4242L;
	private static final int PERCENT = 60;

	private static List<RangeShape> someRanges(RiverTestWorld world, int count) {
		List<RangeShape> all = new ArrayList<>();
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -1; cz <= 1; cz++) {
				all.addAll(world.catalog.shapesInCell(cx, cz));
			}
		}
		all.sort(Comparator.comparing(RangeShape::id));
		return all.subList(0, Math.min(count, all.size()));
	}

	@Test
	void everyRiverOnRealTerrainObeysTheRules() {
		RiverTestWorld world = RiverTestWorld.create(SEED, PERCENT);
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(0.0);
		RiverPlanner planner = new RiverPlanner(SEED, config, world.terrain, world.catalog);
		List<RangeShape> ranges = someRanges(world, 8);
		assertTrue(ranges.size() >= 6, "need ranges to plan: " + ranges.size());

		int rivers = 0;
		int falls = 0;
		double longest = 0;
		for (RangeShape range : ranges) {
			RangeRivers plan = planner.plan(range);
			assertTrue(plan.sourcesFailed() <= plan.sourcesTried(), range.id());
			for (River river : plan.rivers()) {
				RiverAsserts.assertValid(river, config, plan.rivers());
				assertEquals(River.Kind.TRIBUTARY, river.kind());
				assertTrue(river.nodes().stream().allMatch(n -> n.cut() <= config.maxCut() + 1.0e-9), river.id() + " must not be dug into a canyon");
				rivers++;
				falls += river.falls().size();
				longest = Math.max(longest, river.length());
			}
		}
		System.out.printf("[rivers] %d ranges: %d tributaries, %d falls, longest %.0f m%n", ranges.size(), rivers, falls, longest);
		assertTrue(rivers >= ranges.size(), "ranges should get rivers: " + rivers);
		assertTrue(falls > 0, "mountain rivers have waterfalls");
	}

	@Test
	void theSamePlanComesBackInAnyOrder() {
		RiverTestWorld world = RiverTestWorld.create(SEED, PERCENT);
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(0.0);
		List<RangeShape> ranges = someRanges(world, 4);

		RiverPlanner forward = new RiverPlanner(SEED, config, world.terrain, world.catalog);
		List<RangeRivers> first = ranges.stream().map(forward::plan).toList();

		RiverPlanner backward = new RiverPlanner(SEED, config, world.terrain, world.catalog);
		List<RangeShape> reversed = new ArrayList<>(ranges);
		java.util.Collections.reverse(reversed);
		List<RangeRivers> second = new ArrayList<>(reversed.stream().map(backward::plan).toList());
		java.util.Collections.reverse(second);
		assertEquals(first, second, "rivers must not depend on which range was asked about first");

		// and a second world built from the same seed agrees
		RiverTestWorld again = RiverTestWorld.create(SEED, PERCENT);
		RiverPlanner other = new RiverPlanner(SEED, config, again.terrain, again.catalog);
		assertEquals(first, ranges.stream().map(other::plan).toList(), "rivers must depend only on the seed");
	}

	@Test
	void aGrandRiverReachesARealSea() {
		RiverTestWorld world = RiverTestWorld.create(SEED, PERCENT);
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(1.0).withGrandReach(25_000);
		RiverPlanner planner = new RiverPlanner(SEED, config, world.terrain, world.catalog);

		int found = 0;
		for (RangeShape range : someRanges(world, 3)) {
			if (found > 0) {
				break; // a range with no sea in reach can take a long time to give up on
			}
			long start = System.currentTimeMillis();
			RangeRivers plan = planner.plan(range);
			System.out.printf("[rivers] %s: grand rolled=%s found=%s in %.1fs%n", range.id(), plan.grandRolled(), plan.hasGrand(), (System.currentTimeMillis() - start) / 1000.0);
			assertTrue(plan.grandRolled());
			for (River river : plan.rivers()) {
				RiverAsserts.assertValid(river, config, plan.rivers());
			}
			for (River grand : plan.rivers().stream().filter(r -> r.kind() == River.Kind.GRAND).toList()) {
				found++;
				assertEquals(River.Ending.SEA, grand.ending());
				// the mouth must have an ocean biome right next to it
				River.Node mouth = grand.mouth();
				boolean oceanNear = false;
				for (int dx = -400; dx <= 400 && !oceanNear; dx += 100) {
					for (int dz = -400; dz <= 400 && !oceanNear; dz += 100) {
						oceanNear = world.terrain.land(mouth.x() + dx, mouth.z() + dz) < SeaMap.OCEAN;
					}
				}
				assertTrue(oceanNear, grand.id() + " must end beside an ocean");
				assertTrue(grand.mouth().width() > grand.source().width() * 10, grand.id() + " is far wider at the mouth");
				assertTrue(grand.length() > 100, grand.id());
			}
		}
		assertTrue(found >= 1, "a grand river should be found for at least one of the ranges");
	}

	@Test
	void riversStayInsideTheirOwnCorridor() {
		// river nodes are the plan for carving later: they must be finite, ordered and not wander off to nowhere
		RiverTestWorld world = RiverTestWorld.create(SEED, PERCENT);
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(0.0);
		RiverPlanner planner = new RiverPlanner(SEED, config, world.terrain, world.catalog);
		for (RangeShape range : someRanges(world, 4)) {
			double[] centre = world.catalog.toWorld(range.spine(0.5)[0], range.spine(0.5)[1]);
			for (River river : planner.plan(range).rivers()) {
				assertFalse(river.nodes().stream().anyMatch(n -> Double.isNaN(n.x()) || Double.isNaN(n.z()) || Double.isNaN(n.water())), river.id());
				for (River.Node node : river.nodes()) {
					assertTrue(Math.hypot(node.x() - centre[0], node.z() - centre[1]) < range.spineLength() + 12_000,
						river.id() + " wandered far from its range: " + node.x() + ", " + node.z());
				}
			}
		}
	}
}
