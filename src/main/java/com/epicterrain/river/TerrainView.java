package com.epicterrain.river;

/**
 * What the river planner needs to know about the world. Implementations must be thread-safe and deterministic.
 *
 * <p>All of it is the terrain <em>before</em> any river exists: rivers are routed over this ground and then carved into it.
 */
public interface TerrainView {
	/** Height of the ground at a block position, ranges included. Water shows where it is below sea level (62 or less). */
	double ground(double x, double z);

	/**
	 * A cheap estimate of {@link #ground}, good to a few blocks on lowland and rougher on peaks. Routing explores thousands of cells and
	 * only needs to know roughly where it is high and low; the final river is then measured with the accurate ground.
	 */
	default double roughGround(double x, double z) {
		return this.ground(x, z);
	}

	/** How much the ranges raise the ground there, in blocks; 0 outside every range. */
	double uplift(double x, double z);

	/** Vanilla continentalness, which decides the ocean biomes; unaffected by ranges. Below about -0.19 is ocean. */
	double land(double x, double z);
}
