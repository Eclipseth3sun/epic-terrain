package com.epicterrain.river;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How steep the terrain is as a player sees it (slope over a 16-block baseline), for the epic ranges and for vanilla's own hills and
 * peaks, to compare with real mountains when choosing the scale of a new terrain model. Not run by default.
 */
@Tag("preview")
class SlopeProbe {
	private static final double BASE = 8.0;

	private record Cell(double slopeDegrees, double ground) {
	}

	@Test
	void measure() {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		int percent = Integer.getInteger("epicterrain.amount", 50);
		int half = Integer.getInteger("epicterrain.half", 12_000);
		int step = Integer.getInteger("epicterrain.step", 64);
		RiverTestWorld world = RiverTestWorld.create(seed, percent);
		TerrainView t = world.terrain;

		String[] names = { "epic summits (uplift 100+)", "epic foothills (uplift 3-100)", "vanilla peaks (ground 140+)", "vanilla hills (ground 90-140)" };
		List<List<Cell>> classes = new ArrayList<>();
		for (int i = 0; i < names.length; i++) {
			classes.add(new ArrayList<>());
		}
		for (int x = -half; x <= half; x += step) {
			for (int z = -half; z <= half; z += step) {
				double uplift = t.uplift(x, z);
				int k;
				if (uplift >= 100.0) {
					k = 0;
				} else if (uplift >= 3.0) {
					k = 1;
				} else {
					double rough = t.roughGround(x, z);
					if (rough < 85.0) {
						continue; // lowland: not what is being compared
					}
					k = -1;
				}
				double centre = t.ground(x, z);
				if (k < 0) {
					k = centre >= 140.0 ? 2 : centre >= 90.0 ? 3 : -1;
					if (k < 0) {
						continue;
					}
				}
				double gx = (t.ground(x + BASE, z) - t.ground(x - BASE, z)) / (2.0 * BASE);
				double gz = (t.ground(x, z + BASE) - t.ground(x, z - BASE)) / (2.0 * BASE);
				classes.get(k).add(new Cell(Math.toDegrees(Math.atan(Math.hypot(gx, gz))), centre));
			}
		}
		System.out.printf("[slope] seed %d, %d%%, %d x %d km window, a sample every %d blocks, slope over %.0f blocks%n", seed, percent, 2 * half / 1000, 2 * half / 1000, step, 2 * BASE);
		for (int i = 0; i < names.length; i++) {
			List<Cell> cells = classes.get(i);
			if (cells.isEmpty()) {
				System.out.printf("[slope] %-32s: none%n", names[i]);
				continue;
			}
			double[] s = cells.stream().mapToDouble(Cell::slopeDegrees).sorted().toArray();
			double mean = 0;
			double elevation = 0;
			int over45 = 0;
			int over60 = 0;
			for (Cell c : cells) {
				mean += c.slopeDegrees();
				elevation += c.ground();
				over45 += c.slopeDegrees() > 45.0 ? 1 : 0;
				over60 += c.slopeDegrees() > 60.0 ? 1 : 0;
			}
			System.out.printf("[slope] %-32s: %6d cells, ground mean %3.0f, slope mean %4.1f, median %4.1f, p90 %4.1f, over 45 deg %4.1f%%, over 60 deg %4.1f%%%n",
				names[i], cells.size(), elevation / cells.size(), mean / cells.size(), s[s.length / 2], s[(int) (s.length * 0.9)], 100.0 * over45 / cells.size(), 100.0 * over60 / cells.size());
		}
	}
}
