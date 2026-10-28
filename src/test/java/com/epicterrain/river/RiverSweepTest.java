package com.epicterrain.river;

import java.util.ArrayList;
import java.util.List;

import com.epicterrain.worldgen.RangeShape;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Plans the same ranges under several settings and prints how river length and gorge depth respond, to choose defaults. Not part
 * of the normal test run.
 *
 * <pre>./gradlew preview --tests com.epicterrain.river.RiverSweepTest [-Depicterrain.seed=4242 -Depicterrain.amount=50 -Depicterrain.half=12000]</pre>
 */
@Tag("preview")
class RiverSweepTest {
	@Test
	void sweep() {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		int percent = Integer.getInteger("epicterrain.amount", 50);
		int half = Integer.getInteger("epicterrain.half", 12_000);
		RiverTestWorld world = RiverTestWorld.create(seed, percent);
		int cell = world.catalog.cellSize();
		List<RangeShape> shapes = new ArrayList<>();
		for (int i = Math.floorDiv(-half, cell); i <= Math.floorDiv(half, cell); i++) {
			for (int j = Math.floorDiv(-half, cell); j <= Math.floorDiv(half, cell); j++) {
				shapes.addAll(world.catalog.shapesInCell(i, j));
			}
		}
		System.out.printf("[sweep] seed %d, slider %d%%: %d ranges within about %d km of the origin%n", seed, percent, shapes.size(), half / 1000);

		double[][] settings = {
			// minWaterKm2, digCharge
			{ 0.0, 0.08 }, { 0.3, 0.08 }, { 1.0, 0.08 }, { 3.0, 0.08 }, { 1.0, 0.2 }, { 1.0, 0.5 }, { 3.0, 0.2 },
		};
		for (double[] s : settings) {
			RiverConfig config = RiverConfig.DEFAULT.withGrandChance(0.0).withMinWaterKm2(s[0]).withDigCharge(s[1]);
			RiverPlanner planner = new RiverPlanner(seed, config, world.terrain, world.catalog);
			long t0 = System.currentTimeMillis();
			List<RangeRivers> plans = shapes.parallelStream().map(planner::plan).toList();
			List<River> rivers = plans.stream().flatMap(p -> p.rivers().stream()).toList();
			int tried = plans.stream().mapToInt(RangeRivers::sourcesTried).sum();
			int failed = plans.stream().mapToInt(RangeRivers::sourcesFailed).sum();
			System.out.printf("[sweep] minWater %.1f km2, dig %.2f: %d rivers from %d sources (%d failed), %.0fs%n", s[0], s[1], rivers.size(), tried, failed,
				(System.currentTimeMillis() - t0) / 1000.0);
			print("length km", rivers.stream().mapToDouble(r -> r.length() / 1000).sorted().toArray());
			print("deepest cut", rivers.stream().mapToDouble(r -> r.nodes().stream().mapToDouble(River.Node::cut).max().orElse(0)).sorted().toArray());
			print("fall of the water, source to mouth", rivers.stream().mapToDouble(r -> r.source().water() - r.mouth().water()).sorted().toArray());
			print("falls per river", rivers.stream().mapToDouble(r -> r.falls().size()).sorted().toArray());
		}
	}

	private static void print(String label, double[] sorted) {
		if (sorted.length == 0) {
			return;
		}
		System.out.printf("[sweep]     %-34s p10 %6.1f  median %6.1f  p90 %6.1f  max %6.1f%n", label,
			sorted[sorted.length / 10], sorted[sorted.length / 2], sorted[sorted.length * 9 / 10], sorted[sorted.length - 1]);
	}
}
