package com.epicterrain.worldgen;

/**
 * Maps the world-creation slider (percent) onto the density function that decides how many ranges exist.
 *
 * <p>The world is divided into cells, each with a couple of range slots (see {@link RangeField}); the slider is the chance
 * that a slot on land holds a range. So 20% means about one land region in five has a range, and 100% means every land
 * region is crowded with them.
 */
public final class EpicAmount {
	public static final int MIN = 1;
	public static final int MAX = 100;
	public static final int DEFAULT = 20;

	private EpicAmount() {}

	public static int clamp(int percent) {
		return Math.max(MIN, Math.min(MAX, percent));
	}

	/** The value for {@code epicterrain:config/range_occupancy} at a slider position. */
	public static float occupancy(int percent) {
		return clamp(percent) / 100.0f;
	}
}
