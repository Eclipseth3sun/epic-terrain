package com.epicterrain.earth;

/**
 * Every dial of the Earth-like terrain model. The model is simulated in real units (metres, years) and compressed into blocks at the end:
 * one block is {@link #METRES_PER_BLOCK_UP} metres tall and {@code steepness} times that wide, so {@code steepness} says how many times
 * steeper than reality the world looks.
 *
 * @param plateBlocks      how far across a plate is, in blocks; a continent is about plate-sized
 * @param continentalShare share of plates that carry continents (the rest are ocean floor)
 * @param continentalWater share of the continental plates that lies under water (shelf seas, gulfs, inland seas)
 * @param boundaryShare    share of the major relief that sits at plate boundaries; the rest is mid-plate
 * @param beltBlocks       width of a medium mountain belt, blocks (full width at half height)
 * @param steepness        how many times steeper than real the terrain is
 * @param cellBlocks       blocks per cell of the simulation grid
 * @param windowPlates     side of the simulated window, in plates
 * @param temperature      mean annual temperature at sea level at the middle of the window, degrees C
 * @param steps            time steps of the simulation
 * @param rock             how much the rock layers matter: 0 is uniform rock, 1 the default (hard and soft beds, tilted and folded, and
 *                         hard granite domes), 2 is twice the contrast in how fast the beds wear
 */
public record EarthConfig(long seed, double plateBlocks, double continentalShare, double continentalWater, double boundaryShare,
		double beltBlocks, double steepness, int cellBlocks, double windowPlates, double temperature, int steps, double rock) {
	/** Real metres of height that one block of height stands for. */
	public static final double METRES_PER_BLOCK_UP = 10.6;
	/** Real height of the tallest young peaks, which comes out near y 480. */
	public static final double PEAK_METRES = 4400.0;
	public static final int SEA_LEVEL_Y = 63;

	public static EarthConfig defaults(long seed) {
		return new EarthConfig(seed, 25_000.0, 0.80, 0.15, 0.75, 3_000.0, 2.0, 32, 1.5, 12.0, 140, 1.0);
	}

	/** The defaults, with any {@code -Depicterrain.earth.*} options applied (plate and belt in km). */
	public static EarthConfig fromProperties() {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		EarthConfig d = defaults(seed);
		return new EarthConfig(seed,
			option("plate", d.plateBlocks() / 1000.0) * 1000.0,
			option("continental", d.continentalShare()),
			option("water", d.continentalWater()),
			option("boundary", d.boundaryShare()),
			option("belt", d.beltBlocks() / 1000.0) * 1000.0,
			option("steepness", d.steepness()),
			(int) option("cell", d.cellBlocks()),
			option("window", d.windowPlates()),
			option("temperature", d.temperature()),
			(int) option("steps", d.steps()),
			option("rock", d.rock()));
	}

	private static double option(String key, double fallback) {
		String value = System.getProperty("epicterrain.earth." + key);
		return value == null ? fallback : Double.parseDouble(value);
	}

	/** Real metres that one block of width stands for. */
	public double blockMetres() {
		return this.steepness * METRES_PER_BLOCK_UP;
	}

	public EarthConfig withSteepness(double value) {
		return new EarthConfig(this.seed, this.plateBlocks, this.continentalShare, this.continentalWater, this.boundaryShare, this.beltBlocks, value,
			this.cellBlocks, this.windowPlates, this.temperature, this.steps, this.rock);
	}

	public EarthConfig withBelt(double blocks) {
		return new EarthConfig(this.seed, this.plateBlocks, this.continentalShare, this.continentalWater, this.boundaryShare, blocks, this.steepness,
			this.cellBlocks, this.windowPlates, this.temperature, this.steps, this.rock);
	}

	public EarthConfig withCell(int blocks) {
		return new EarthConfig(this.seed, this.plateBlocks, this.continentalShare, this.continentalWater, this.boundaryShare, this.beltBlocks, this.steepness,
			blocks, this.windowPlates, this.temperature, this.steps, this.rock);
	}

	public EarthConfig withSteps(int value) {
		return new EarthConfig(this.seed, this.plateBlocks, this.continentalShare, this.continentalWater, this.boundaryShare, this.beltBlocks, this.steepness,
			this.cellBlocks, this.windowPlates, this.temperature, value, this.rock);
	}

	/** The same dials for another world. */
	public EarthConfig withSeed(long value) {
		return new EarthConfig(value, this.plateBlocks, this.continentalShare, this.continentalWater, this.boundaryShare, this.beltBlocks, this.steepness,
			this.cellBlocks, this.windowPlates, this.temperature, this.steps, this.rock);
	}

	public EarthConfig withRock(double value) {
		return new EarthConfig(this.seed, this.plateBlocks, this.continentalShare, this.continentalWater, this.boundaryShare, this.beltBlocks, this.steepness,
			this.cellBlocks, this.windowPlates, this.temperature, this.steps, value);
	}

	public EarthConfig withPlate(double blocks, double window) {
		return new EarthConfig(this.seed, blocks, this.continentalShare, this.continentalWater, this.boundaryShare, this.beltBlocks, this.steepness,
			this.cellBlocks, window, this.temperature, this.steps, this.rock);
	}
}
