package com.epicterrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/** Scratch calibration: how big are vanilla's terrain shaping values? Not run by default. */
@Tag("preview")
class ProbeTest {
	private static DensityFunction fn(WorldgenHarness h, String id) {
		return h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id))).value();
	}

	/**
	 * Throwaway experiment for the river design: can a river be routed downhill across plain vanilla terrain until it reaches
	 * vanilla water (everything vanilla floods sits at sea level, so "base surface at or below 64" means water)? Reports how
	 * often it works, how long the rivers get, and how deep the river has to cut to keep the water level only ever falling.
	 */
	@Test
	void riverRoutingOnVanillaTerrain() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		DensityFunction surfaceFn = settings.noiseRouter().chunkSurfaceLevel();
		DensityFunction continents = fn(h, "minecraft:overworld/continents");

		for (long seed : new long[] { 4242L, 4310335781435980812L }) {
			RandomState rs = h.randomState(seed, settings);
			java.util.function.ToDoubleBiFunction<Double, Double> height = (x, z) ->
				rs.sampleBlockValueUncached(surfaceFn, (int) Math.round(x), 0, (int) Math.round(z));

			// start points: inland ground between y85 and y140, like the foot of a range
			Random random = new Random(seed ^ 17);
			java.util.List<double[]> starts = new ArrayList<>();
			while (starts.size() < 160) {
				int x = random.nextInt(60_000) - 30_000;
				int z = random.nextInt(60_000) - 30_000;
				double y = height.applyAsDouble((double) x, (double) z);
				if (y >= 85 && y <= 140 && rs.sampleBlockValueUncached(continents, x, 0, z) > 0.1f) {
					starts.add(new double[] { x, z, y });
				}
			}

			final int step = 128;
			final int maxSteps = 240; // 30 km
			double[] lengths = new double[starts.size()];
			boolean[] reached = new boolean[starts.size()];
			double[][] cut = new double[starts.size()][];
			long t0 = System.currentTimeMillis();
			java.util.stream.IntStream.range(0, starts.size()).parallel().forEach(i -> {
				double x = starts.get(i)[0];
				double z = starts.get(i)[1];
				double h0 = starts.get(i)[2];
				double heading = Double.NaN;
				double water = h0 - 2; // the water level may only ever fall
				java.util.List<Double> cuts = new ArrayList<>();
				int stuck = 0;
				int n = 0;
				for (; n < maxSteps; n++) {
					double here = height.applyAsDouble(x, z);
					if (here <= 64.5) {
						reached[i] = true;
						break;
					}
					water = Math.min(water, here - 2);
					cuts.add(here - water);
					double bestScore = -1e9;
					double bestHeading = 0;
					boolean downhill = false;
					for (int k = 0; k < 16; k++) {
						double a = k * Math.PI / 8;
						double[] ahead = new double[4];
						int[] dist = { 128, 256, 512, 1024 };
						for (int j = 0; j < 4; j++) {
							ahead[j] = height.applyAsDouble(x + Math.cos(a) * dist[j], z + Math.sin(a) * dist[j]);
						}
						double near = (here - Math.min(ahead[0], ahead[1])) / 256.0;       // downhill within 256 blocks
						double far = (here - Math.min(ahead[2], ahead[3])) / 1024.0;         // or a lower place further out
						double barrier = Math.max(0, Math.max(ahead[0], ahead[1]) - here);   // a hill in the way
						double turn = Double.isNaN(heading) ? 0 : Math.abs(Math.atan2(Math.sin(a - heading), Math.cos(a - heading))) / Math.PI;
						double score = near + 0.6 * far - 0.004 * barrier - 0.01 * turn;
						if (score > bestScore) {
							bestScore = score;
							bestHeading = a;
							downhill = near > 0.0005;
						}
					}
					stuck = downhill ? 0 : stuck + 1;
					if (stuck > 24) {
						break;
					}
					heading = bestHeading;
					x += Math.cos(heading) * step;
					z += Math.sin(heading) * step;
				}
				lengths[i] = n * (double) step;
				cut[i] = cuts.stream().mapToDouble(Double::doubleValue).toArray();
			});

			int ok = 0;
			java.util.List<Double> okLengths = new ArrayList<>();
			java.util.List<Double> allCuts = new ArrayList<>();
			for (int i = 0; i < starts.size(); i++) {
				if (reached[i]) {
					ok++;
					okLengths.add(lengths[i]);
					for (double c : cut[i]) {
						allCuts.add(c);
					}
				}
			}
			java.util.Collections.sort(okLengths);
			java.util.Collections.sort(allCuts);
			System.out.printf("[probe] seed %d: %d of %d routed rivers reached vanilla water within 30 km (%.0fs)%n", seed, ok, starts.size(), (System.currentTimeMillis() - t0) / 1000.0);
			if (!okLengths.isEmpty()) {
				System.out.printf("[probe]   length of the ones that arrived: p10 %.1f km, median %.1f km, p90 %.1f km, max %.1f km%n",
					okLengths.get(okLengths.size() / 10) / 1000, okLengths.get(okLengths.size() / 2) / 1000, okLengths.get(okLengths.size() * 9 / 10) / 1000, okLengths.get(okLengths.size() - 1) / 1000);
				System.out.printf("[probe]   how far the river must cut below the ground to keep falling (blocks): median %.0f, p75 %.0f, p90 %.0f, p99 %.0f; deeper than 15: %.1f%%, 30: %.1f%%, 60: %.1f%% of the path%n",
					allCuts.get(allCuts.size() / 2), allCuts.get(allCuts.size() * 3 / 4), allCuts.get(allCuts.size() * 9 / 10), allCuts.get(allCuts.size() * 99 / 100),
					100.0 * allCuts.stream().filter(c -> c > 15).count() / allCuts.size(), 100.0 * allCuts.stream().filter(c -> c > 30).count() / allCuts.size(),
					100.0 * allCuts.stream().filter(c -> c > 60).count() / allCuts.size());
			}
		}
	}

	/** How close is vanilla water, and how far is the real ocean, from a point of ordinary inland ground? */
	@Test
	void distanceToVanillaWater() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		DensityFunction surfaceFn = settings.noiseRouter().chunkSurfaceLevel();
		DensityFunction continents = fn(h, "minecraft:overworld/continents");
		for (long seed : new long[] { 4242L, 4310335781435980812L }) {
			RandomState rs = h.randomState(seed, settings);
			Random random = new Random(seed ^ 5);
			java.util.List<Double> toWater = new ArrayList<>();
			java.util.List<Double> toOcean = new ArrayList<>();
			int tried = 0;
			while (toWater.size() < 300 && tried++ < 20000) {
				int x = random.nextInt(80_000) - 40_000;
				int z = random.nextInt(80_000) - 40_000;
				if (rs.sampleBlockValueUncached(surfaceFn, x, 0, z) < 85 || rs.sampleBlockValueUncached(continents, x, 0, z) < 0.1f) {
					continue;
				}
				double water = Double.NaN;
				double ocean = Double.NaN;
				for (int r = 64; r <= 40_000 && (Double.isNaN(water) || Double.isNaN(ocean)); r += 64) {
					int samples = Math.max(8, (int) (2 * Math.PI * r / 128));
					for (int s = 0; s < samples; s++) {
						double a = 2 * Math.PI * s / samples;
						int px = (int) (x + Math.cos(a) * r);
						int pz = (int) (z + Math.sin(a) * r);
						if (Double.isNaN(water) && rs.sampleBlockValueUncached(surfaceFn, px, 0, pz) <= 64.5f) {
							water = r;
						}
						if (Double.isNaN(ocean) && rs.sampleBlockValueUncached(continents, px, 0, pz) < -0.2f) {
							ocean = r;
						}
					}
				}
				toWater.add(water);
				toOcean.add(Double.isNaN(ocean) ? 40_000 : ocean);
			}
			java.util.Collections.sort(toWater);
			java.util.Collections.sort(toOcean);
			int n = toWater.size();
			System.out.printf("[probe] seed %d, %d hill/upland spots (ground y85+): straight-line distance to the nearest vanilla water (river, lake or ocean): p10 %.0f, median %.0f, p90 %.0f blocks%n",
				seed, n, toWater.get(n / 10), toWater.get(n / 2), toWater.get(n * 9 / 10));
			System.out.printf("[probe]   ...and to the open ocean: p10 %.1f km, median %.1f km, p90 %.1f km%n", toOcean.get(n / 10) / 1000, toOcean.get(n / 2) / 1000, toOcean.get(n * 9 / 10) / 1000);
		}
	}

	/** Cross-sections of the two range kinds, measured from the real field: how far from the spine each height is reached. */
	@Test
	void rangeWidths() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("epicterrain:overworld").orElseThrow();
		RandomState rs = h.randomState(4242L, settings);
		DensityFunction upliftFn = fn(h, "epicterrain:range/uplift");
		DensityFunction kindFn = fn(h, "epicterrain:range/kind");
		DensityFunction distanceFn = fn(h, "epicterrain:range/distance");

		int bins = 40;
		int binSize = 50;
		java.util.List<java.util.List<java.util.List<Float>>> samples = new ArrayList<>();
		for (int k = 0; k < 2; k++) {
			samples.add(new ArrayList<>());
			for (int b = 0; b < bins; b++) {
				samples.get(k).add(new ArrayList<>());
			}
		}
		Random random = new Random(9);
		int inRange = 0;
		for (int i = 0; i < 1_500_000; i++) {
			int x = random.nextInt(400_000) - 200_000;
			int z = random.nextInt(400_000) - 200_000;
			float d = rs.sampleBlockValueUncached(distanceFn, x, 0, z);
			if (d < 0 || d >= bins * binSize) {
				continue;
			}
			int kind = rs.sampleBlockValueUncached(kindFn, x, 0, z) > 0.5f ? 1 : 0;
			samples.get(kind).get((int) (d / binSize)).add(rs.sampleBlockValueUncached(upliftFn, x, 0, z));
			inRange++;
		}
		System.out.println("[probe] " + inRange + " samples inside some range's reach");
		for (int k = 0; k < 2; k++) {
			String kindName = k == 0 ? "FOOTHILL range" : "WALL range";
			System.out.println("[probe] " + kindName + ": distance from spine -> uplift in blocks (p50 typical / p90 at full strength)");
			float reach = 0;
			float[] halfWidth = { 0, 0, 0 }; // p90 >= 135, >= 40, >= 3
			float[] thresholds = { 135, 40, 3 };
			for (int b = 0; b < bins; b++) {
				java.util.List<Float> list = samples.get(k).get(b);
				if (list.size() < 30) {
					continue;
				}
				java.util.Collections.sort(list);
				float p50 = list.get(list.size() / 2);
				float p90 = list.get(list.size() * 9 / 10);
				if (b % 2 == 0) {
					System.out.printf("[probe]   %4d-%4d blocks: p50=%5.0f  p90=%5.0f  (n=%d)%n", b * binSize, (b + 1) * binSize, p50, p90, list.size());
				}
				for (int t = 0; t < 3; t++) {
					if (p90 >= thresholds[t]) {
						halfWidth[t] = (b + 1) * binSize;
					}
				}
				if (p90 > 0.5f) {
					reach = (b + 1) * binSize;
				}
			}
			System.out.printf("[probe]   half-width where a full-strength range still reaches: >=135 blocks (summit zone) %.0f, >=40 (real hills) %.0f, >=3 (footprint edge) %.0f%n",
				halfWidth[0], halfWidth[1], halfWidth[2]);
			System.out.printf("[probe]   => total width: summit zone %.0f, hills %.0f, whole footprint %.0f%n", 2 * halfWidth[0], 2 * halfWidth[1], 2 * halfWidth[2]);
		}
	}

	@Test
	void uplift() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("epicterrain:overworld").orElseThrow();
		RandomState rs = h.randomState(4242L, settings);
		DensityFunction uplift = fn(h, "epicterrain:range/uplift");
		DensityFunction cont = fn(h, "minecraft:overworld/continents");
		Random random = new Random(3);
		int n = 40000;
		int[] over = new int[5];
		float[] thresholds = { 1f, 10f, 40f, 120f, 240f };
		int landN = 0;
		int[] overLand = new int[5];
		for (int i = 0; i < n; i++) {
			int x = random.nextInt(400000) - 200000;
			int z = random.nextInt(400000) - 200000;
			float u = rs.sampleBlockValueUncached(uplift, x, 0, z);
			boolean land = rs.sampleBlockValueUncached(cont, x, 0, z) > -0.11f;
			if (land) {
				landN++;
			}
			for (int k = 0; k < 5; k++) {
				if (u > thresholds[k]) {
					over[k]++;
					if (land) {
						overLand[k]++;
					}
				}
			}
		}
		for (int k = 0; k < 5; k++) {
			System.out.printf("[probe] uplift > %4.0f blocks: %5.1f%% of all columns, %5.1f%% of land%n", thresholds[k],
				100.0 * over[k] / n, 100.0 * overLand[k] / Math.max(1, landN));
		}
	}

	@Test
	void probeVanillaShape() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		RandomState rs = h.randomState(4242L, settings);
		DensityFunction offset = fn(h, "minecraft:overworld/offset");
		DensityFunction factor = fn(h, "minecraft:overworld/factor");
		DensityFunction jag = fn(h, "minecraft:overworld/jaggedness");
		DensityFunction cont = fn(h, "minecraft:overworld/continents");
		DensityFunction ero = fn(h, "minecraft:overworld/erosion");
		DensityFunction ridges = fn(h, "minecraft:overworld/ridges");

		Random random = new Random(7);
		List<float[]> rows = new ArrayList<>();
		for (int i = 0; i < 60000; i++) {
			int x = random.nextInt(160000) - 80000;
			int z = random.nextInt(160000) - 80000;
			rows.add(new float[] {
				rs.sampleBlockValueUncached(offset, x, 0, z), rs.sampleBlockValueUncached(factor, x, 0, z),
				rs.sampleBlockValueUncached(jag, x, 0, z), rs.sampleBlockValueUncached(cont, x, 0, z),
				rs.sampleBlockValueUncached(ero, x, 0, z), rs.sampleBlockValueUncached(ridges, x, 0, z), x, z });
		}
		rows.sort(Comparator.comparingDouble(r -> r[0]));
		System.out.println("[probe] offset pct: p1=" + rows.get(600)[0] + " p25=" + rows.get(15000)[0] + " p50=" + rows.get(30000)[0]
			+ " p75=" + rows.get(45000)[0] + " p90=" + rows.get(54000)[0] + " p99=" + rows.get(59400)[0] + " max=" + rows.get(59999)[0]);
		System.out.println("[probe] columns: offset factor jag cont erosion ridges | surfaceY~=128+128*offset");
		for (int i = 59999; i > 59999 - 8; i--) {
			float[] r = rows.get(i);
			System.out.printf("[probe] TOP    off=%.3f fac=%.3f jag=%.3f cont=%.3f ero=%.3f ridg=%.3f%n", r[0], r[1], r[2], r[3], r[4], r[5]);
		}
		// Typical inland land (continents > 0.1) statistics.
		List<float[]> inland = rows.stream().filter(r -> r[3] > 0.1f).toList();
		System.out.println("[probe] inland samples=" + inland.size() + " offset median=" + inland.get(inland.size() / 2)[0]
			+ " p10=" + inland.get(inland.size() / 10)[0] + " p90=" + inland.get(inland.size() * 9 / 10)[0]);
		float meanFactor = 0;
		for (float[] r : inland) {
			meanFactor += r[1];
		}
		System.out.println("[probe] inland mean factor=" + meanFactor / inland.size());
		List<float[]> flat = inland.stream().filter(r -> r[0] < 0.0f).toList();
		float ff = 0;
		for (float[] r : flat) {
			ff += r[1];
		}
		System.out.println("[probe] inland lowland (offset<0) n=" + flat.size() + " mean factor=" + ff / Math.max(1, flat.size()));
		float maxJag = 0;
		for (float[] r : rows) {
			maxJag = Math.max(maxJag, r[2]);
		}
		System.out.println("[probe] max jaggedness=" + maxJag);
	}
}
