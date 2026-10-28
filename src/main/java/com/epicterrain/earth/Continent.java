package com.epicterrain.earth;

/**
 * A simulated piece of world: the finished landscape and everything derived from it, on a grid of {@code cell}-block cells whose centre
 * (0, 0) is at world position ({@code x0}, {@code z0}). Heights are real metres above sea level; {@link #yBlocks} turns them into the
 * block heights the game would use.
 */
public final class Continent {
	public final EarthConfig config;
	public final int nx;
	public final int nz;
	public final double cell;
	public final double x0;
	public final double z0;
	/** Real metres across one cell. */
	public final double cellMetres;
	/** Metres above sea level; negative is sea. */
	public final float[] height;
	public final float[] rain;
	public final float[] temperature;
	public final byte[] biome;
	/** Depth of a lake over this cell, metres; 0 where there is none. */
	public final float[] lakeDepth;
	/** Drainage area, square metres. */
	public final float[] area;
	/** Water carried past this cell, in millimetres of rain over square metres. */
	public final float[] discharge;
	public final boolean[] river;
	/** The cell each cell's water runs to (itself where it runs nowhere: the edge and the bottom of a basin). */
	public final int[] receiver;
	/** How fast the rock at the surface wears, around 1 (below 0.4 is a hard bed or dome, above 1.8 a soft bed); 1 everywhere when the rock dial is 0. */
	public final float[] hardness;
	public final byte[] continental;
	public final byte[] kind;
	public final float[] young;
	public final float[] old;
	public final float[] distance;
	public final float[] rim;
	public final int[] plateKey;
	public final Tectonics tectonics;
	/** How much the simulated heights were scaled so the tallest young peaks reach their target. */
	public double scale = 1.0;
	public long millis;
	public String timing = "";
	public EarthMetrics metrics;

	Continent(EarthConfig config, Tectonics tectonics, Landscape land, double cellMetres, float[] height, Climate.Result climate, float[] lakeDepth,
			float[] area, float[] discharge, boolean[] river, int[] receiver, float[] hardness) {
		this.config = config;
		this.tectonics = tectonics;
		this.nx = land.nx;
		this.nz = land.nz;
		this.cell = land.cell;
		this.x0 = land.x0;
		this.z0 = land.z0;
		this.cellMetres = cellMetres;
		this.height = height;
		this.rain = climate.rain();
		this.temperature = climate.temperature();
		this.biome = climate.biome();
		this.lakeDepth = lakeDepth;
		this.area = area;
		this.discharge = discharge;
		this.river = river;
		this.receiver = receiver;
		this.hardness = hardness;
		this.continental = land.continental;
		this.kind = land.kind;
		this.young = land.young;
		this.old = land.old;
		this.distance = land.distance;
		this.rim = land.rim;
		this.plateKey = land.plateKey;
	}

	/**
	 * The game height of a cell's ground. Land is 1 block per {@link EarthConfig#METRES_PER_BLOCK_UP} metres; the sea floor is eased so
	 * that even the deepest ocean stays within the world (about 85 blocks down).
	 */
	public int yBlocks(int i) {
		return (int) Math.round(Math.min(500.0, EarthConfig.SEA_LEVEL_Y + blocksAbove(this.height[i])));
	}

	/** Blocks above sea level for a real height in metres: 1 block per 10.6 m on land, and the sea floor eased to about 85 blocks down. */
	public static double blocksAbove(double heightMetres) {
		return heightMetres >= 0.0 ? heightMetres / EarthConfig.METRES_PER_BLOCK_UP : -85.0 * (1.0 - StrictMath.exp(heightMetres / 900.0));
	}
}
