package com.epicterrain.river;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Checks the planner's ground estimate (the noise settings' surface-level function) against columns sampled from a real generated
 * world: does it put water where the game put water, and land where it put land? Not part of the normal test run.
 *
 * <pre>python sample_columns.py "run/saves/New World" columns.csv 2
 * ./gradlew preview --tests com.epicterrain.river.GroundAccuracyProbe -Depicterrain.columns=columns.csv
 *     -Depicterrain.seed=4310335781435980812 -Depicterrain.amount=45</pre>
 */
@Tag("preview")
class GroundAccuracyProbe {
	@Test
	void groundEstimateAgainstTheRealWorld() throws Exception {
		Path csv = Path.of(System.getProperty("epicterrain.columns", "columns.csv"));
		long seed = Long.getLong("epicterrain.seed", 4310335781435980812L);
		int percent = Integer.getInteger("epicterrain.amount", 45);
		RiverTestWorld world = RiverTestWorld.create(seed, percent);

		List<String> lines = Files.readAllLines(csv);
		int n = lines.size() - 1;
		double[] estimate = new double[n];
		int[] top = new int[n];
		boolean[] water = new boolean[n];
		int[] floor = new int[n];
		double[] uplift = new double[n];
		java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
			String[] f = lines.get(i + 1).split(",");
			double x = Integer.parseInt(f[0]);
			double z = Integer.parseInt(f[1]);
			top[i] = Integer.parseInt(f[2]);
			water[i] = f[3].equals("1");
			floor[i] = Integer.parseInt(f[4]);
			estimate[i] = world.terrain.ground(x, z);
			uplift[i] = world.terrain.uplift(x, z);
		});

		// water columns: does the estimate say water?
		List<Double> waterEstimates = new ArrayList<>();
		List<Double> waterDelta = new ArrayList<>();
		List<Double> landEstimates = new ArrayList<>();
		List<Double> landDelta = new ArrayList<>();
		int waterTotal = 0;
		int waterCalled = 0;
		int landTotal = 0;
		int landCalledWater = 0;
		int landNearSea = 0;
		int landNearSeaCalledWater = 0;
		for (int i = 0; i < n; i++) {
			if (water[i] && top[i] == 62) {
				waterTotal++;
				waterEstimates.add(estimate[i]);
				waterDelta.add(estimate[i] - floor[i]);
				if (estimate[i] <= 62.5) {
					waterCalled++;
				}
			} else if (!water[i] && top[i] >= 64) {
				landTotal++;
				landEstimates.add(estimate[i]);
				landDelta.add(estimate[i] - top[i]);
				if (estimate[i] <= 62.5) {
					landCalledWater++;
				}
				if (top[i] <= 70) {
					landNearSea++;
					if (estimate[i] <= 62.5) {
						landNearSeaCalledWater++;
					}
				}
			}
		}
		System.out.printf("[ground] %d sampled columns of seed %d at slider %d%%: %d real water (top at y62), %d real land (top at y64+)%n", n, seed, percent, waterTotal, landTotal);
		System.out.printf("[ground] real WATER columns: the estimate says water (<= 62.5) for %.1f%%%n", 100.0 * waterCalled / Math.max(1, waterTotal));
		System.out.printf("[ground] real LAND columns: the estimate says water for %.1f%% (of land no higher than y70: %.1f%%, %d columns)%n",
			100.0 * landCalledWater / Math.max(1, landTotal), 100.0 * landNearSeaCalledWater / Math.max(1, landNearSea), landNearSea);
		pct("estimate over real water columns (y)", waterEstimates);
		pct("estimate minus real sea floor, water columns (blocks)", waterDelta);
		pct("estimate over real land columns (y)", landEstimates);
		pct("estimate minus real top block, land columns (blocks; trees and leaves read high)", landDelta);

		// candidate B: the height where the terrain curve alone (offset, no noise, no jaggedness) crosses zero
		com.epicterrain.WorldgenHarness h = com.epicterrain.WorldgenHarness.shared();
		net.minecraft.world.level.levelgen.densityfunction.DensityFunction offsetFn = world.registeredHere(h, "epicterrain:overworld/offset");
		net.minecraft.world.level.levelgen.densityfunction.DensityFunction slopedFn = world.registeredHere(h, "epicterrain:overworld/sloped_cheese");
		double[] base = new double[n];
		double[] scan = new double[n];
		int scanned = Math.min(n, 12000);
		java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
			String[] f = lines.get(i + 1).split(",");
			int x = Integer.parseInt(f[0]);
			int z = Integer.parseInt(f[1]);
			double offset = world.state.sampleBlockValueUncached(offsetFn, x, 0, z);
			base[i] = -64 + (1.5 + offset) * 128.0 * (4.5 / 4.5); // y_gradient: 1.5 at -64 falling 4.5 over 576 blocks => 128 blocks per unit
			scan[i] = Double.NaN;
			if (i < scanned) {
				// candidate C: the highest y where the terrain density (before caves) is solid
				int y = 330;
				while (y > -60 && world.state.sampleBlockValueUncached(slopedFn, x, y, z) <= 0) {
					y -= 4;
				}
				int lo = y;
				int hi = y + 4;
				while (hi - lo > 1) {
					int mid = (lo + hi) / 2;
					if (world.state.sampleBlockValueUncached(slopedFn, x, mid, z) > 0) {
						lo = mid;
					} else {
						hi = mid;
					}
				}
				scan[i] = lo;
			}
		});
		System.out.println("[ground] --- candidate B: the zero of the terrain curve, y = -64 + (1.5 + offset) * 128 ---");
		bandTable(n, water, top, base, 0);
		System.out.println("[ground] --- candidate C: the highest solid y of the terrain density, scanned (first " + scanned + " columns) ---");
		bandTable(scanned, water, top, scan, 0);
		System.out.println("[ground] --- the current estimate (preliminary surface) for comparison ---");
		bandTable(n, water, top, estimate, 0);

		// by height band of the real land, how often is the estimate below sea level, and how far off is it
		int[] bands = { 64, 70, 80, 100, 150, 250, 600 };
		for (int b = 0; b < bands.length - 1; b++) {
			List<Double> delta = new ArrayList<>();
			int below = 0;
			int count = 0;
			for (int i = 0; i < n; i++) {
				if (!water[i] && top[i] >= bands[b] && top[i] < bands[b + 1]) {
					count++;
					delta.add(estimate[i] - top[i]);
					if (estimate[i] <= 62.5) {
						below++;
					}
				}
			}
			if (count > 0) {
				double[] sorted = delta.stream().mapToDouble(Double::doubleValue).sorted().toArray();
				System.out.printf("[ground]   real land y%d-%d: %d columns, estimate median error %+.1f (p10 %+.1f, p90 %+.1f), estimate below sea level on %.1f%%%n",
					bands[b], bands[b + 1] - 1, count, sorted[sorted.length / 2], sorted[sorted.length / 10], sorted[sorted.length * 9 / 10], 100.0 * below / count);
			}
		}
	}

	/** Error of an estimate against the real top block, by height band of the real land, plus how it classifies water. */
	private static void bandTable(int n, boolean[] water, int[] top, double[] estimate, int unused) {
		int[] bands = { 64, 70, 80, 100, 150, 250, 600 };
		for (int b = 0; b < bands.length - 1; b++) {
			List<Double> delta = new ArrayList<>();
			int below = 0;
			for (int i = 0; i < n; i++) {
				if (!water[i] && top[i] >= bands[b] && top[i] < bands[b + 1] && !Double.isNaN(estimate[i])) {
					delta.add(estimate[i] - top[i]);
					below += estimate[i] <= 62.5 ? 1 : 0;
				}
			}
			if (!delta.isEmpty()) {
				double[] sorted = delta.stream().mapToDouble(Double::doubleValue).sorted().toArray();
				System.out.printf("[ground]   real land y%d-%d: %d columns, error median %+.1f (p10 %+.1f, p90 %+.1f), says water on %.1f%%%n",
					bands[b], bands[b + 1] - 1, sorted.length, sorted[sorted.length / 2], sorted[sorted.length / 10], sorted[sorted.length * 9 / 10], 100.0 * below / sorted.length);
			}
		}
		int waterTotal = 0;
		int called = 0;
		for (int i = 0; i < n; i++) {
			if (water[i] && top[i] == 62 && !Double.isNaN(estimate[i])) {
				waterTotal++;
				called += estimate[i] <= 62.5 ? 1 : 0;
			}
		}
		System.out.printf("[ground]   real water columns: %d, estimate says water for %.1f%%%n", waterTotal, 100.0 * called / Math.max(1, waterTotal));
	}

	private static void pct(String label, List<Double> values) {
		double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		if (sorted.length == 0) {
			return;
		}
		System.out.printf("[ground] %s: p10 %.1f, median %.1f, p90 %.1f%n", label, sorted[sorted.length / 10], sorted[sorted.length / 2], sorted[sorted.length * 9 / 10]);
	}
}
