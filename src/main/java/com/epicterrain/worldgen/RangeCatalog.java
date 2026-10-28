package com.epicterrain.worldgen;

import java.util.List;

/**
 * Read-only access to where this world's ranges are, for code that needs the ranges themselves (rivers start on them)
 * and not just the height they add at a point.
 */
public interface RangeCatalog {
	/** The ranges whose centre lies in a cell of {@link #cellSize()} blocks (cell {@code (0, 0)} spans 0 to cellSize). */
	List<RangeShape> shapesInCell(int cellX, int cellZ);

	int cellSize();

	/** Identifies this world's range placement: two catalogs with the same key place exactly the same ranges. */
	default long worldKey() {
		return 0L;
	}

	/** How far ranges are displaced at a world position: {@code {warpX, warpZ}}. A spine point lives at {@code position + warp}. */
	double[] warp(double x, double z);

	/** Converts a position in the range's own (warped) space to the real block position it appears at. */
	default double[] toWorld(double rangeX, double rangeZ) {
		// range space = world + warp(world); the warp is smooth, so iterating "world = range - warp(world)" converges quickly
		double x = rangeX;
		double z = rangeZ;
		for (int i = 0; i < 8; i++) {
			double[] w = this.warp(x, z);
			x = rangeX - w[0];
			z = rangeZ - w[1];
		}
		return new double[] { x, z };
	}
}
