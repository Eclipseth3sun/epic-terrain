package com.epicterrain.river;

import java.util.ArrayList;
import java.util.List;

import com.epicterrain.worldgen.RangeShape;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Where does a grand river's plan spend its time: mapping the seas, or routing? Not part of the normal test run. */
@Tag("preview")
class GrandPlanTimingProbe {
	@Test
	void breakdown() {
		RiverTestWorld world = RiverTestWorld.create(4242L, 50);
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(1.0);
		RiverPlanner planner = new RiverPlanner(world.catalog.worldKey(), config, world.terrain, world.catalog);
		List<RangeShape> shapes = new ArrayList<>();
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -1; cz <= 1; cz++) {
				shapes.addAll(world.catalog.shapesInCell(cx, cz));
			}
		}
		double sea = 0;
		double all = 0;
		int n = 0;
		for (RangeShape shape : shapes.subList(0, Math.min(8, shapes.size()))) {
			double[] middle = world.catalog.toWorld(shape.spine(0.5)[0], shape.spine(0.5)[1]);
			double radius = shape.spineLength() / 2.0 + config.grandReach() + 10_000.0;
			long t0 = System.nanoTime();
			SeaMap.build(world.terrain, middle[0], middle[1], radius, config.seaMinKm2());
			long t1 = System.nanoTime();
			RangeRivers plan = planner.plan(shape);
			long t2 = System.nanoTime();
			sea += (t1 - t0) / 1e9;
			all += (t2 - t1) / 1e9;
			n++;
			System.out.printf("[grand-timing] %s: sea map %.2fs, whole plan (including its own sea map) %.2fs, grand found %s%n", shape.id(), (t1 - t0) / 1e9, (t2 - t1) / 1e9, plan.hasGrand());
		}
		System.out.printf("[grand-timing] average over %d ranges: sea map %.2fs of a %.2fs plan%n", n, sea / n, all / n);
	}
}
