package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.river.RiverTestWorld;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/** The range geometry the river planner reads must be the geometry the terrain really has. */
class RangeCatalogTest {
	private static final long SEED = 4242L;

	private static List<RangeShape> shapes(RangeCatalog catalog, int radius) {
		List<RangeShape> all = new ArrayList<>();
		for (int cx = -radius; cx <= radius; cx++) {
			for (int cz = -radius; cz <= radius; cz++) {
				all.addAll(catalog.shapesInCell(cx, cz));
			}
		}
		return all;
	}

	@Test
	void shapesLieWhereTheTerrainHasRanges() {
		WorldgenHarness h = WorldgenHarness.shared();
		RiverTestWorld world = RiverTestWorld.create(SEED, 100);
		DensityFunction kind = world.registeredHere(h, "epicterrain:range/kind");
		DensityFunction distance = world.registeredHere(h, "epicterrain:range/distance");

		List<RangeShape> all = shapes(world.catalog, 4);
		assertTrue(all.size() >= 30, "expected plenty of ranges at 100%: " + all.size());

		int onSpine = 0;
		int raised = 0;
		int kindMatches = 0;
		int checked = 0;
		for (RangeShape shape : all) {
			double[] spine = shape.spine(0.5);
			double[] at = world.catalog.toWorld(spine[0], spine[1]);
			int x = (int) Math.round(at[0]);
			int z = (int) Math.round(at[1]);
			float d = world.state.sampleBlockValueUncached(distance, x, 0, z);
			checked++;
			// another range can be nearer or taller at this point, so require "near some spine", not "near this one"
			if (d >= 0 && d <= 80) {
				onSpine++;
			}
			if (world.terrain.uplift(x, z) > 20) {
				raised++;
			}
			if (d >= 0 && d <= 80 && (world.state.sampleBlockValueUncached(kind, x, 0, z) > 0.5f) == shape.wall()) {
				kindMatches++;
			}
		}
		System.out.printf("[catalog] %d ranges: %d within 80 blocks of a real spine at their midpoint, %d raised more than 20 blocks, %d agree on wall/foothill%n",
			checked, onSpine, raised, kindMatches);
		assertTrue(onSpine >= checked * 0.9, "most shape midpoints should sit on a spine of the real field: " + onSpine + " of " + checked);
		assertTrue(raised >= checked * 0.85, "most shape midpoints should be high ground: " + raised + " of " + checked);
		assertTrue(kindMatches >= onSpine * 0.9, "wall/foothill should agree with the real field: " + kindMatches + " of " + onSpine);
	}

	@Test
	void everyRangeIsOnLandAndInsideItsCell() {
		RiverTestWorld world = RiverTestWorld.create(SEED, 100);
		int cell = world.catalog.cellSize();
		for (RangeShape shape : shapes(world.catalog, 3)) {
			assertTrue(shape.centerX() >= (double) shape.cellX() * cell && shape.centerX() < (double) (shape.cellX() + 1) * cell, shape.id() + " centre x");
			assertTrue(shape.centerZ() >= (double) shape.cellZ() * cell && shape.centerZ() < (double) (shape.cellZ() + 1) * cell, shape.id() + " centre z");
			assertTrue(world.terrain.land(shape.centerX(), shape.centerZ()) >= -0.12, shape.id() + " must have its centre on land");
			assertTrue(shape.spineLength() > 2000, shape.id() + " spine length " + shape.spineLength());
		}
	}

	@Test
	void sliderChangesWhichSlotsAreFilledNotWhereTheyAre() {
		RiverTestWorld low = RiverTestWorld.create(SEED, 20);
		RiverTestWorld high = RiverTestWorld.create(SEED, 100);
		List<RangeShape> few = shapes(low.catalog, 3);
		List<RangeShape> many = shapes(high.catalog, 3);
		assertTrue(many.size() > few.size(), "more at 100% than at 20%: " + many.size() + " vs " + few.size());
		// every range at 20% is also a range at 100% with exactly the same geometry
		for (RangeShape shape : few) {
			assertTrue(many.contains(shape), shape.id() + " must be identical at every slider value");
		}
	}

	@Test
	void sameSeedGivesTheSameRanges() {
		assertEquals(shapes(RiverTestWorld.create(SEED, 60).catalog, 2), shapes(RiverTestWorld.create(SEED, 60).catalog, 2));
		assertTrue(!shapes(RiverTestWorld.create(SEED, 60).catalog, 2).equals(shapes(RiverTestWorld.create(SEED + 1, 60).catalog, 2)),
			"a different seed must give different ranges");
	}
}
