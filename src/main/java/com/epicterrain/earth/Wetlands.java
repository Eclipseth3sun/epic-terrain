package com.epicterrain.earth;

/**
 * Flooded swamps and marshes: the low, flat, poorly drained land of the world, where the water table is at the surface.
 *
 * <p>Vanilla's swamps are the flattest land it makes, standing at the sea level with pools in it (measured on a vanilla seed: the ground of its swamps is at y 62 in the
 * middle, from 3 blocks under to 6 above, and a third of their columns are water). The Earth model had no such land (its lowland is ground that is merely low), so vanilla's
 * table, which wants the flattest erosion for a swamp, was never asked. This is that land, as one field that both the ground and the biome read, so that a swamp is where the
 * ground is marsh and the other way round:
 * <ul>
 *   <li>{@link #weight} says how marshy a column is, from 0 to 1: low ground (a few blocks above the sea at most), not at the very shore (that is a beach), more likely where
 *       it rains, near the sea, and in the regions a wetness noise picks (a wide noise, so that a dry continent still has its marshes);</li>
 *   <li>{@link #ground} is the surface of a marsh, at the sea level to within a few blocks, with pools and hummocks of some 40 blocks, so that about a third of it is water.</li>
 * </ul>
 */
public final class Wetlands {
	/** The ground of a marsh stands this high (blocks above the sea) in the middle, and varies by about {@link #SPREAD} either side. */
	private static final double MEAN = -0.6;
	private static final double SPREAD = 11.0;
	/** Ground this many blocks above the sea or less is low enough for marsh in full; above {@link #LOW_NONE} there is none. */
	private static final double LOW_FULL = 3.0;
	private static final double LOW_NONE = 11.0;
	/** The wetness score above which marsh begins, and the width over which it comes in. */
	private static final double THRESHOLD = 0.12;
	private static final double WIDTH = 0.30;

	private Wetlands() {
	}

	private static double dial(String name, double fallback) {
		String value = System.getProperty("epicterrain.earth." + name);
		return value == null ? fallback : Double.parseDouble(value);
	}

	/**
	 * How marshy a column is, 0 to 1 (see {@link #blend} for how much of the ground it takes over).
	 *
	 * @param smoothBlocks the simulated ground without its fine relief, blocks above the sea
	 * @param coastBlocks  distance to the sea
	 * @param rainMm       simulated rainfall
	 */
	public static double weight(long seed, double mx, double mz, double smoothBlocks, double coastBlocks, double rainMm) {
		// the edge of low ground wanders a few blocks, so that the edge of a marsh is not a contour line
		double ground = smoothBlocks + 2.5 * Noise.fbm(seed + 0x3A7C2L, mx / 600.0, mz / 600.0, 2);
		double low = 1.0 - Noise.smooth(LOW_FULL, LOW_NONE, ground);
		if (low <= 0.0) {
			return 0.0;
		}
		double inland = Noise.smooth(40.0, 200.0, coastBlocks);
		if (inland <= 0.0) {
			return 0.0;
		}
		double amount = dial("wetland", 1.0);
		if (amount <= 0.0) {
			return 0.0;
		}
		double wet = Noise.fbm(seed + 0x3A7C1L, mx / 4200.0, mz / 4200.0, 3);
		double rain = Noise.clamp((rainMm - 500.0) / 700.0, -1.0, 1.0);
		double nearSea = 1.0 - Noise.smooth(400.0, 4000.0, coastBlocks);
		double score = wet + 0.30 * rain + 0.25 * nearSea + 0.5 * (amount - 1.0);
		return low * inland * Noise.smooth(THRESHOLD, THRESHOLD + WIDTH, score);
	}

	/** How much of the ground a weight takes over: a marsh is all marsh from half way, so the edge is a short slope and not a long fade. */
	public static double blend(double weight) {
		return Noise.smooth(0.05, 0.6, weight);
	}

	/** Whether a column is marsh enough to be called a swamp. */
	public static boolean isMarsh(double weight) {
		return blend(weight) >= 0.5;
	}

	/** The surface of a marsh, in blocks above the sea: pools (under 0, flooded by the sea level) and hummocks. */
	public static double ground(long seed, double mx, double mz) {
		double pools = Noise.fbm(seed + 0x6B1D3L, mx / 36.0, mz / 36.0, 3);
		double hummocks = Noise.fbm(seed + 0x6B1D4L, mx / 13.0, mz / 13.0, 2);
		return MEAN + SPREAD * pools + 1.2 * hummocks;
	}
}
