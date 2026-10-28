package com.epicterrain.earth;

import java.util.Arrays;

/**
 * Builds a {@link Continent}: the tectonic skeleton gives the starting crust and the forces, the erosion simulation runs through the
 * history, sea level is chosen so that the right share of the continental plates is under water, the tallest young peaks are set to
 * their target height, and climate and drainage are worked out from the result.
 */
public final class ContinentSimulator {
	/** Rivers start where this many square blocks drain (at game scale, so it means the same at any steepness). */
	static final double RIVER_BLOCKS2 = 400_000.0;

	private ContinentSimulator() {
	}

	public static Continent run(EarthConfig cfg) {
		long start = System.nanoTime();
		Tectonics tectonics = new Tectonics(cfg);
		double[] centre = tectonics.chooseCenter();
		double cx = Double.parseDouble(System.getProperty("epicterrain.earth.cx", Double.toString(centre[0])));
		double cz = Double.parseDouble(System.getProperty("epicterrain.earth.cz", Double.toString(centre[1])));
		Landscape land = Landscape.build(cfg, tectonics, cx, cz);
		long built = System.nanoTime();

		// the share of the continental plates under water is counted away from the window's edge, where the shelf and coast are
		byte[] counted = new byte[land.continental.length];
		for (int i = 0; i < counted.length; i++) {
			counted[i] = (byte) (land.continental[i] != 0 && land.rim[i] <= 0.0f ? 1 : 0);
		}
		double provisional = quantile(land.base, counted, cfg.continentalWater());
		Erosion erosion = new Erosion(land);
		erosion.initialise(provisional);
		erosion.run();
		long eroded = System.nanoTime();

		// the sea ends up where the dial says: that share of the continental plates is under water
		double sea = quantile(erosion.h, counted, cfg.continentalWater());
		int n = erosion.n;
		for (int i = 0; i < n; i++) {
			erosion.h[i] -= (float) sea;
		}
		double peak = youngPeak(erosion.h, land.young);
		double scale = peak > 200.0 ? EarthConfig.PEAK_METRES / peak : 1.0;
		for (int i = 0; i < n; i++) {
			if (erosion.h[i] > 0.0f) {
				erosion.h[i] *= (float) scale;
			}
		}
		erosion.flood();
		erosion.accumulate();

		// A lake is deep water the game can show: at least one block (10.6 m) deep. Shallower flooding is wet ground at the lake's level.
		float[] lakeDepth = new float[n];
		boolean[] wet = new boolean[n];
		float[] rawDepth = new float[n];
		int rawCells = 0;
		int dry = 0;
		for (int i = 0; i < n; i++) {
			dry += erosion.h[i] >= 0.0f ? 1 : 0;
			if (erosion.lake[i]) {
				rawDepth[i] = (float) (erosion.zfill[i] - erosion.h[i]);
				rawCells += rawDepth[i] > 0.05f ? 1 : 0;
				if (rawDepth[i] >= EarthConfig.METRES_PER_BLOCK_UP) {
					lakeDepth[i] = rawDepth[i];
					wet[i] = true;
				}
			}
		}
		float[] sorted = new float[rawCells];
		int filled = 0;
		for (int i = 0; i < n; i++) {
			if (rawDepth[i] > 0.05f) {
				sorted[filled++] = rawDepth[i];
			}
		}
		Arrays.sort(sorted);

		Climate.Result climate = Climate.compute(cfg, erosion.nx, erosion.nz, erosion.dx, erosion.h, wet);
		float[] discharge = new float[n];
		double cellArea = erosion.dx * erosion.dx;
		for (int c = erosion.count - 1; c >= 0; c--) {
			int cell = erosion.order[c];
			int r = erosion.receiver[cell];
			if (r == cell) {
				continue;
			}
			discharge[cell] += (float) (climate.rain()[cell] * cellArea);
			discharge[r] += discharge[cell];
		}
		boolean[] river = new boolean[n];
		double riverArea = RIVER_BLOCKS2 * cfg.blockMetres() * cfg.blockMetres();
		for (int i = 0; i < n; i++) {
			river[i] = erosion.h[i] >= 0.0f && !wet[i] && erosion.area[i] >= riverArea;
		}
		Continent continent = new Continent(cfg, tectonics, land, erosion.dx, erosion.h, climate, lakeDepth, erosion.area, discharge, river, erosion.receiver, erosion.lith);
		continent.scale = scale;
		continent.metrics = EarthMetrics.of(continent, erosion);
		continent.metrics.lakeFloodedShare = dry == 0 ? 0.0 : (double) rawCells / dry;
		continent.metrics.floodedDepthMedian = sorted.length == 0 ? 0.0 : sorted[sorted.length / 2];
		continent.metrics.floodedDepthP90 = sorted.length == 0 ? 0.0 : sorted[(int) (sorted.length * 0.9)];
		long end = System.nanoTime();
		continent.millis = (end - start) / 1_000_000L;
		continent.timing = String.format("%d x %d cells of %d blocks; skeleton and forces %.1fs, simulation %.1fs, climate and rivers %.1fs", land.nx, land.nz,
			cfg.cellBlocks(), (built - start) / 1e9, (eroded - built) / 1e9, (end - eroded) / 1e9);
		return continent;
	}

	/** The value below which this share of the masked cells lie. */
	static double quantile(float[] values, byte[] mask, double share) {
		int count = 0;
		for (int i = 0; i < values.length; i += 3) {
			count += mask[i] != 0 ? 1 : 0;
		}
		float[] sample = new float[count];
		int k = 0;
		for (int i = 0; i < values.length; i += 3) {
			if (mask[i] != 0) {
				sample[k++] = values[i];
			}
		}
		Arrays.sort(sample);
		return sample.length == 0 ? 0.0 : sample[(int) Math.min(sample.length - 1, Math.floor(share * sample.length))];
	}

	/** The height reached by the top fraction of a percent of land where the young belts are strong; 0 if there is none. */
	private static double youngPeak(float[] height, float[] young) {
		int count = 0;
		for (int i = 0; i < height.length; i++) {
			count += young[i] >= 0.5f && height[i] > 0.0f ? 1 : 0;
		}
		if (count < 50) {
			return 0.0;
		}
		float[] sample = new float[count];
		int k = 0;
		for (int i = 0; i < height.length; i++) {
			if (young[i] >= 0.5f && height[i] > 0.0f) {
				sample[k++] = height[i];
			}
		}
		Arrays.sort(sample);
		return sample[(int) Math.min(count - 1, Math.floor(0.998 * count))];
	}
}
