package com.epicterrain.earth;

/**
 * Temperature, rainfall and the biome class that follows from them, for a finished landscape.
 *
 * <p>Temperature falls with height (6.5 degrees per kilometre) and with distance toward the cold side of the window. Rain comes with the
 * wind off the sea: moist air is pushed up the windward side of a range, where much of it falls, and what is left crosses to the dry
 * leeward side (the rain shadow). Everything is in real units, so a 4 km range really is cold and really does shadow what lies behind it.
 */
final class Climate {
	static final byte OCEAN = 0;
	static final byte ICE = 1;
	static final byte TUNDRA = 2;
	static final byte TAIGA = 3;
	static final byte COLD_STEPPE = 4;
	static final byte FOREST = 5;
	static final byte GRASSLAND = 6;
	static final byte DESERT = 7;
	static final byte SAVANNA = 8;
	static final byte RAINFOREST = 9;
	static final byte SEASONAL_FOREST = 10;
	static final byte SHRUBLAND = 11;
	static final byte ALPINE = 12;
	static final byte LAKE = 13;

	record Result(float[] rain, float[] temperature, byte[] biome) {
	}

	private Climate() {
	}

	/** @param height metres above sea level; negative is sea */
	static Result compute(EarthConfig cfg, int nx, int nz, double cellMetres, float[] height, boolean[] lake) {
		int n = nx * nz;
		float[] rain = new float[n];
		float[] temperature = new float[n];
		byte[] biome = new byte[n];
		double km = cellMetres / 1000.0;
		double baseLoss = 1.0 - StrictMath.exp(-0.0012 * km);
		double recycle = 1.0 - StrictMath.exp(-0.0010 * km); // moisture the land gives back to the air
		double recharge = 1.0 - StrictMath.exp(-km / 150.0);
		// the air is marched west to east across the whole window at once, and mixes a little sideways as it goes, so neighbouring rows
		// do not end up with different weather
		double[] q = new double[nz];
		double[] previous = new double[nz];
		for (int z = 0; z < nz; z++) {
			q[z] = 0.85; // the air arrives off the sea everywhere along the west side
			previous[z] = height[z * nx];
		}
		double[] mixed = new double[nz];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				int i = z * nx + x;
				double h = height[i];
				if (h < 0.0) {
					q[z] += (1.0 - q[z]) * recharge;
					rain[i] = (float) (0.4 * q[z] * 1500.0);
				} else {
					double lift = Math.max(0.0, h - previous[z]);
					double slope = lift / cellMetres;
					rain[i] = (float) (q[z] * (1500.0 + 7000.0 * slope));
					double condensed = 1.0 - StrictMath.exp(-lift / 2500.0);
					q[z] = q[z] * (1.0 - baseLoss) * (1.0 - condensed) + (1.0 - q[z]) * recycle * 0.6;
				}
				previous[z] = h;
			}
			for (int pass = 0; pass < 8; pass++) {
				for (int z = 0; z < nz; z++) {
					double up = q[Math.max(0, z - 1)];
					double down = q[Math.min(nz - 1, z + 1)];
					mixed[z] = q[z] + 0.25 * (up + down - 2.0 * q[z]);
				}
				System.arraycopy(mixed, 0, q, 0, nz);
			}
		}
		for (int z = 0; z < nz; z++) {
			double north = (z - nz / 2.0) * cfg.cellBlocks() / 10_000.0; // positive toward the south, the warm side
			for (int x = 0; x < nx; x++) {
				int i = z * nx + x;
				double h = height[i];
				double t = cfg.temperature() + 3.0 * north - 6.5 * Math.max(0.0, h) / 1000.0;
				temperature[i] = (float) t;
				biome[i] = h < 0.0 ? OCEAN : lake[i] ? LAKE : classify(t, rain[i], h);
			}
		}
		return new Result(rain, temperature, biome);
	}

	static byte classify(double t, double p, double h) {
		if (h > 3200.0 && t < 2.0) {
			return ALPINE;
		}
		if (t < -10.0) {
			return ICE;
		}
		if (t < 0.0) {
			return TUNDRA;
		}
		if (p < 250.0) {
			return DESERT;
		}
		if (t < 6.0) {
			return p > 350.0 ? TAIGA : COLD_STEPPE;
		}
		if (t < 20.0) {
			if (p >= 700.0) {
				return FOREST;
			}
			return p >= 350.0 ? GRASSLAND : SHRUBLAND;
		}
		if (p >= 1800.0) {
			return RAINFOREST;
		}
		if (p >= 1400.0) {
			return SEASONAL_FOREST;
		}
		return p >= 500.0 ? SAVANNA : SHRUBLAND;
	}
}
