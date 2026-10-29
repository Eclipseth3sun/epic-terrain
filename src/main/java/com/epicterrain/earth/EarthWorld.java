package com.epicterrain.earth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.epicterrain.earth.Islands.Island;

/**
 * One world of the Earth type, ready to be asked about: the simulated continent around the origin with its detail, rivers and valleys, the
 * islands of the open ocean around it, and the sea floor between. This is what the game's generator reads; everything in it is a pure function
 * of the world's seed, so any chunk can ask for any column in any order.
 *
 * <p>The window of simulation is placed so that its middle is the world's origin (the model's own coordinates are the world's plus an offset, which
 * is how the spawn lands on the continent). Beyond the window is deep ocean over oceanic crust, with the island catalogue standing in it. Heights
 * are blocks above sea level ({@code y = 63 + height}); water levels are in the same unit.
 */
public final class EarthWorld {
	/** A column's water: none, a river's, a lake's or a crater lake's. */
	public static final int NO_WATER = 0;
	public static final int RIVER = 1;
	public static final int LAKE = 2;
	public static final int CRATER = 3;
	/** The sea floor beyond the window blends from the window's rim to the open ocean floor over this many blocks. */
	private static final double RIM_BLEND = 3_000.0;

	public final EarthConfig config;
	public final Continent continent;
	public final Detail detail;
	public final List<Rivers.River> rivers;
	public final RiverValley valley;
	public final Islands islands;
	/** Wall-clock summary of how long each part took, for the log. */
	public final String summary;
	private final List<Island> fragments;
	private final double[] fragmentFloor;
	private final float[] relief;
	private final float[] coast;
	private final double offsetX;
	private final double offsetZ;
	private final double minX;
	private final double maxX;
	private final double minZ;
	private final double maxZ;
	private final ThreadLocal<Cache> cache = ThreadLocal.withInitial(Cache::new);

	/** A thread's recent columns: terrain generation asks for the same column once per block of its height. */
	private static final class Cache {
		static final int SIZE = 8192;
		final long[] keys = new long[SIZE];
		final double[] ground = new double[SIZE];
		final double[] water = new double[SIZE];
		final byte[] kind = new byte[SIZE];
		final double[] scratch = new double[3];

		Cache() {
			java.util.Arrays.fill(this.keys, Long.MIN_VALUE);
		}
	}

	/** Simulates the window and lays everything on it. Takes about a minute at the default 32-block cells. */
	public EarthWorld(EarthConfig config, Consumer<String> log) {
		long start = System.nanoTime();
		this.config = config;
		log.accept(String.format("Simulating the Earth window for seed %d: %.1f plates across, cells of %d blocks (this takes a while)", config.seed(), config.windowPlates(), config.cellBlocks()));
		this.continent = ContinentSimulator.run(config);
		long simulated = System.nanoTime();
		this.detail = new Detail(this.continent, Detail.Dials.fromProperties());
		this.rivers = Rivers.build(this.continent, this.detail::smoothBlocks, Rivers.Dials.fromProperties());
		this.valley = new RiverValley(this.continent, this.rivers, this.detail, RiverValley.Dials.fromProperties());
		this.islands = new Islands(this.continent.tectonics, Islands.Dials.fromProperties());
		this.fragments = Islands.inWindow(this.continent, Islands.Dials.fromProperties());
		this.fragmentFloor = new double[this.fragments.size()];
		for (int i = 0; i < this.fragments.size(); i++) {
			Island f = this.fragments.get(i);
			int gx = Math.max(0, Math.min(this.continent.nx - 1, (int) Math.round((f.x() - this.continent.x0) / this.continent.cell)));
			int gz = Math.max(0, Math.min(this.continent.nz - 1, (int) Math.round((f.z() - this.continent.z0) / this.continent.cell)));
			this.fragmentFloor[i] = Continent.blocksAbove(this.continent.height[gz * this.continent.nx + gx]);
		}
		this.relief = BiomeClimate.reliefAboveBase(this.continent);
		this.coast = BiomeClimate.coastDistance(this.continent);
		this.minX = this.continent.x0;
		this.maxX = this.continent.x0 + (this.continent.nx - 1) * this.continent.cell;
		this.minZ = this.continent.z0;
		this.maxZ = this.continent.z0 + (this.continent.nz - 1) * this.continent.cell;
		this.offsetX = 0.5 * (this.minX + this.maxX);
		this.offsetZ = 0.5 * (this.minZ + this.maxZ);
		long done = System.nanoTime();
		this.summary = String.format("simulation %.1f s, then detail, %d rivers, valleys, islands and climate %.1f s; window %.1f km square around the origin, %d island fragments in its seas",
			(simulated - start) / 1e9, this.rivers.size(), (done - simulated) / 1e9, (this.maxX - this.minX) / 1000.0, this.fragments.size());
		log.accept("The Earth window is ready: " + this.summary);
	}

	/** The model position of a world position. */
	private double modelX(double wx) {
		return wx + this.offsetX;
	}

	private double modelZ(double wz) {
		return wz + this.offsetZ;
	}

	/** The window of simulation in world coordinates: minimum x, maximum x, minimum z, maximum z. */
	public double[] window() {
		return new double[] { this.minX - this.offsetX, this.maxX - this.offsetX, this.minZ - this.offsetZ, this.maxZ - this.offsetZ };
	}

	private static double smooth01(double t) {
		double c = Math.max(0.0, Math.min(1.0, t));
		return c * c * (3.0 - 2.0 * c);
	}

	/** The ground of the open sea beyond the window: its rim's floor, easing down to the floor of the open ocean. */
	private double beyond(double mx, double mz) {
		double ex = Math.max(this.minX, Math.min(this.maxX, mx));
		double ez = Math.max(this.minZ, Math.min(this.maxZ, mz));
		double rim = this.detail.smoothBlocks(ex, ez);
		double t = smooth01(Math.hypot(mx - ex, mz - ez) / RIM_BLEND);
		return rim + (Islands.OCEAN_FLOOR - rim) * t;
	}

	/**
	 * The ground and the water at a column, in world coordinates: {@code out[0]} the ground in blocks above sea level (negative under the sea),
	 * {@code out[1]} the surface of the water standing over it in the same unit (NaN if there is none above the sea), {@code out[2]} whose water
	 * ({@link #NO_WATER}, {@link #RIVER}, {@link #LAKE}, {@link #CRATER}).
	 */
	public void sample(double wx, double wz, double[] out) {
		double mx = this.modelX(wx);
		double mz = this.modelZ(wz);
		double ground;
		double water = Double.NaN;
		int kind = NO_WATER;
		if (mx >= this.minX && mx <= this.maxX && mz >= this.minZ && mz <= this.maxZ) {
			this.valley.column(mx, mz, out);
			ground = out[0];
			water = out[1];
			kind = (int) out[2];
			if (kind == NO_WATER && Double.isNaN(water)) {
				double marsh = this.wetlandWeight(mx, mz);
				if (marsh > 0.0) {
					ground += (Wetlands.ground(this.config.seed(), mx, mz) - ground) * Wetlands.blend(marsh);
				}
			}
		} else {
			ground = this.beyond(mx, mz);
		}
		for (int i = 0; i < this.fragments.size(); i++) {
			Island f = this.fragments.get(i);
			if (Math.hypot(mx - f.x(), mz - f.z()) <= f.footprint()) {
				ground = Math.max(ground, Islands.profile(f, mx, mz, this.fragmentFloor[i]));
			}
		}
		if (ground < 2.0) {
			ground = this.islands.height(mx, mz, ground);
		}
		if (ground > 15.0) {
			double crater = this.islands.craterWater(mx, mz, Islands.OCEAN_FLOOR);
			if (!Double.isNaN(crater) && ground < crater) {
				water = crater;
				kind = CRATER;
			}
		}
		if (!Double.isNaN(water) && water <= 0.0) {
			water = Double.NaN; // at or under the sea: the sea is the sea
			kind = NO_WATER;
		}
		out[0] = ground;
		out[1] = water;
		out[2] = kind;
	}

	/** As {@link #sample}, for a block column, remembering the last few thousand columns this thread asked about. */
	public void sampleBlock(int x, int z, double[] out) {
		Cache c = this.cache.get();
		long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		int slot = (int) ((x * 73856093L ^ z * 19349663L) & (Cache.SIZE - 1));
		if (c.keys[slot] == key) {
			out[0] = c.ground[slot];
			out[1] = c.water[slot];
			out[2] = c.kind[slot];
			return;
		}
		this.sample(x, z, out);
		c.keys[slot] = key;
		c.ground[slot] = out[0];
		c.water[slot] = out[1];
		c.kind[slot] = (byte) out[2];
	}

	/** Dry land at least a few blocks above the water, where the player may be put. */
	public boolean isLand(int x, int z) {
		double[] out = this.cache.get().scratch;
		this.sampleBlock(x, z, out);
		return out[0] >= 6.0 && Double.isNaN(out[1]);
	}

	// ---- what the biome mapping needs -----------------------------------------------------------------------------------

	private double grid(float[] field, double mx, double mz) {
		double gx = (mx - this.continent.x0) / this.continent.cell;
		double gz = (mz - this.continent.z0) / this.continent.cell;
		int x = (int) Math.floor(gx);
		int z = (int) Math.floor(gz);
		double tx = gx - x;
		double tz = gz - z;
		double sum = 0.0;
		for (int dz = 0; dz <= 1; dz++) {
			for (int dx = 0; dx <= 1; dx++) {
				int cx = Math.max(0, Math.min(this.continent.nx - 1, x + dx));
				int cz = Math.max(0, Math.min(this.continent.nz - 1, z + dz));
				sum += (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz) * field[cz * this.continent.nx + cx];
			}
		}
		return sum;
	}

	private double wetlandWeight(double mx, double mz) {
		return Wetlands.weight(this.config.seed(), mx, mz, this.detail.smoothBlocks(mx, mz), this.grid(this.coast, mx, mz), this.grid(this.continent.rain, mx, mz));
	}

	/** How marshy a column is, 0 to 1 (see {@link Wetlands}); 0 beyond the window. */
	public double wetland(double wx, double wz) {
		double mx = this.modelX(wx);
		double mz = this.modelZ(wz);
		if (mx < this.minX || mx > this.maxX || mz < this.minZ || mz > this.maxZ) {
			return 0.0;
		}
		return this.wetlandWeight(mx, mz);
	}

	/** Metres the ground stands above the lowland around it (0 beyond the window). */
	public double reliefMetres(double wx, double wz) {
		return this.grid(this.relief, this.modelX(wx), this.modelZ(wz));
	}

	/** Rainfall in millimetres a year (a dry sea beyond the window). */
	public double rainMm(double wx, double wz) {
		return this.grid(this.continent.rain, this.modelX(wx), this.modelZ(wz));
	}

	/** Distance to the sea in blocks, from the window's grid. */
	public double coastBlocks(double wx, double wz) {
		return this.grid(this.coast, this.modelX(wx), this.modelZ(wz));
	}

	/** The island of the open ocean a column of land belongs to, or null. */
	public Island islandAt(double wx, double wz) {
		return this.islands.islandAt(this.modelX(wx), this.modelZ(wz), Islands.OCEAN_FLOOR);
	}

	/** The feature biome (volcano, atoll) of a column, or null. */
	public Islands.Highlight highlight(double wx, double wz) {
		return this.islands.highlight(this.modelX(wx), this.modelZ(wz), Islands.OCEAN_FLOOR);
	}

	/** Whether a column is on a mushroom island. */
	public boolean mushroom(double wx, double wz) {
		return this.islands.mushroomAt(this.modelX(wx), this.modelZ(wz), Islands.OCEAN_FLOOR) != null;
	}

	/** A coast distance for a column of an island, in blocks. */
	public double islandCoast(Island is, double wx, double wz) {
		return Islands.coastBlocks(is, this.modelX(wx), this.modelZ(wz));
	}

	/** Position in the model's own coordinates, for tests that want to look something up in the catalogue. */
	public double[] toModel(double wx, double wz) {
		return new double[] { this.modelX(wx), this.modelZ(wz) };
	}

	/** Position in the world of a point of the model. */
	public double[] toWorld(double mx, double mz) {
		return new double[] { mx - this.offsetX, mz - this.offsetZ };
	}

	/** Every fragment island in the window's seas, for tests. */
	public List<Island> fragments() {
		return new ArrayList<>(this.fragments);
	}
}
