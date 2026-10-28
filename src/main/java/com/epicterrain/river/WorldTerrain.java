package com.epicterrain.river;

import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * The terrain of a real world, read from its density functions.
 *
 * <p>{@code ground} is found by searching the terrain density ({@code sloped_cheese}: the shape before caves) for the highest
 * solid block of a column. That matches the generated world to within a block or two; vanilla's own surface-level estimate is
 * deliberately conservative (it sits 12 to 25 blocks low) and would put water on most lowland. Water therefore shows where the
 * ground is 62 or less (sea level is 63, so the top water block is 62).
 *
 * <p>{@code roughGround} is where the terrain curve alone (the offset, with no noise and no jaggedness) crosses zero, which costs
 * one 2D evaluation instead of a dozen 3D ones and is what routing explores with.
 *
 * <p>{@code uplift} is the range field and {@code land} the vanilla continentalness; all of them must use the same range occupancy as
 * the terrain they describe. Sampling is thread-safe.
 */
public final class WorldTerrain implements TerrainView {
	/** Everything at or below this is solid in vanilla's terrain, everything above the world's tallest peak is air. */
	private static final int LOW = -60;
	private static final int HIGH = 500;
	/** The y gradient runs from 1.5 at y -64 down by one unit per 128 blocks, so depth is zero at y = -64 + (1.5 + offset) * 128. */
	private static final double GRADIENT_Y = -64.0;
	private static final double GRADIENT_VALUE = 1.5;
	private static final double BLOCKS_PER_UNIT = 128.0;
	/** The zero of the curve sits about 3 blocks under the generated surface on lowland (measured against a played world). */
	private static final double ROUGH_BIAS = 3.0;

	private final DensitySampler density;
	private final DensitySampler offset;
	private final DensitySampler uplift;
	private final DensitySampler land;

	/** Builds the view from samplers compiled for the world in question. */
	public WorldTerrain(DensitySampler density, DensitySampler offset, DensitySampler uplift, DensitySampler land) {
		this.density = density;
		this.offset = offset;
		this.uplift = uplift;
		this.land = land;
	}

	/** Builds the view from a {@link RandomState}, which compiles the functions itself. */
	public static WorldTerrain of(RandomState state, DensityFunction density, DensityFunction offset, DensityFunction uplift, DensityFunction land) {
		return new WorldTerrain(state.getSampler(density), state.getSampler(offset), state.getSampler(uplift), state.getSampler(land));
	}

	@Override
	public double ground(double x, double z) {
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		// Far below the surface the density is strongly positive and far above it strongly negative, so the sign changes once
		// near the surface and a binary search finds it. (The caves are not part of this function, only the terrain shape.)
		int solid = LOW;
		int air = HIGH;
		while (air - solid > 1) {
			int mid = (solid + air) >>> 1;
			if (this.density.sampleValue(SamplerContext.EMPTY_UNCACHED, bx, mid, bz) > 0) {
				solid = mid;
			} else {
				air = mid;
			}
		}
		return solid;
	}

	@Override
	public double roughGround(double x, double z) {
		double units = this.offset.sampleValue(SamplerContext.EMPTY_UNCACHED, (int) Math.floor(x), 0, (int) Math.floor(z));
		return GRADIENT_Y + (GRADIENT_VALUE + units) * BLOCKS_PER_UNIT + ROUGH_BIAS;
	}

	@Override
	public double uplift(double x, double z) {
		return this.uplift.sampleValue(SamplerContext.EMPTY_UNCACHED, (int) Math.floor(x), 0, (int) Math.floor(z));
	}

	@Override
	public double land(double x, double z) {
		return this.land.sampleValue(SamplerContext.EMPTY_UNCACHED, (int) Math.floor(x), 0, (int) Math.floor(z));
	}
}
