package com.epicterrain.earth;

/**
 * The six climate numbers that vanilla's biome table is asked about, worked out for every cell of a simulated continent, so that
 * vanilla's own table picks the biomes.
 *
 * <p>Vanilla chooses a biome from temperature, humidity, continentalness (how far inland), erosion (0 is mountainous, 6 flat and
 * swampy), depth (0 at the surface) and weirdness (how peak-like, and its sign picks between paired biomes: frozen against jagged peaks,
 * old-growth pine against spruce). The world is infinite, so none of this can follow latitude. Instead:
 * <ul>
 *   <li><b>temperature</b>, <b>humidity</b> and the <b>sign of weirdness</b> are vanilla's own noise, so regions vary the way vanilla's do;</li>
 *   <li><b>height</b> cools the temperature (a lapse rate, so the taller the mountain the colder). <b>Relief</b>, how far ground stands
 *       above the lowland around it, steers erosion and weirdness through bands: lowland, foothills, montane, subalpine, peaks. (Not
 *       height above sea: a broad plateau is still plains.) Vanilla's table gives each band its own set of biomes, and the regional
 *       temperature and humidity pick among them, so a mountain is not only ever frozen or jagged peaks;</li>
 *   <li><b>rainfall</b> from the simulation nudges humidity: windward slopes are wetter and the lee is drier;</li>
 *   <li><b>variety</b> wanders the band edges and the parameters with noise, so zones interleave instead of lying in tidy rings;</li>
 *   <li>rivers are given the valley setting so vanilla answers river, and coasts and the sea floor set continentalness.</li>
 * </ul>
 * Pure: the vanilla noise comes in through {@link VanillaNoise}.
 */
public final class BiomeClimate {
	/** Vanilla's own regional randomness at a position: temperature, humidity and weirdness. */
	public interface VanillaNoise {
		void at(double x, double z, double[] out);
	}

	/**
	 * @param lapse     temperature parameter lost per real kilometre of height (0.3 turns a temperate lowland into frozen ground by 3 km)
	 * @param rainBias  how far the simulated rainfall moves humidity, 0 (pure vanilla) to 1
	 * @param variety   how much the band edges and parameters wander, 0 (clean zones) to 1
	 */
	public record Dials(double lapse, double rainBias, double variety) {
		public static Dials defaults() {
			return new Dials(0.3, 0.8, 0.5);
		}

		public static Dials fromProperties() {
			Dials d = defaults();
			return new Dials(option("lapse", d.lapse()), option("rainbias", d.rainBias()), option("variety", d.variety()));
		}

		private static double option(String key, double fallback) {
			String value = System.getProperty("epicterrain.earth." + key);
			return value == null ? fallback : Double.parseDouble(value);
		}

		public Dials withVariety(double value) {
			return new Dials(this.lapse, this.rainBias, value);
		}

		public Dials withLapse(double value) {
			return new Dials(value, this.rainBias, this.variety);
		}

		public Dials withRainBias(double value) {
			return new Dials(this.lapse, value, this.variety);
		}
	}

	/** Height bands, from the sea up. */
	public static final byte OCEAN = 0;
	public static final byte LOWLAND = 1;
	public static final byte FOOTHILLS = 2;
	public static final byte MONTANE = 3;
	public static final byte SUBALPINE = 4;
	public static final byte PEAKS = 5;
	public static final String[] ZONE_NAMES = { "ocean", "lowland", "foothills", "montane", "subalpine", "peaks" };

	/**
	 * The real depth, in metres, from which the sea floor is asked for as deep ocean (vanilla's table wants a continentalness under -0.455). About 62 blocks down: vanilla's
	 * own deep oceans are 18 to 29 blocks deep (66 at most) and its oceans 9 to 18, so this is a deep ocean by any measure of vanilla's, and the sea here (60 to 100 blocks
	 * down in the open) is deeper still; the shallower part keeps vanilla's plain ocean, with its kelp and warm-water reefs, about as often as vanilla does. (An earlier 1,500 m was never met in the game: it was compared with the depth in blocks times 10.6 m, and the floor is eased to 85 blocks.)
	 */
	public static final double DEEP_OCEAN_M = 1200.0;
	/**
	 * Thresholds of the patches that reach the rows of vanilla's table the bands alone never ask for (each is a noise, scaled by the variety dial, so that clean zones
	 * stay clean): where the hill country is windswept, which stretches of coast are rock and not sand, and which summits are bare warm rock (stony peaks).
	 */
	private static final double SHATTER_THRESHOLD = 0.50;
	private static final double ROCKY_COAST_THRESHOLD = 0.08;
	private static final double BARE_SUMMIT_THRESHOLD = 0.15;
	/** How much lower the erosion of the lowland is in hill country. */
	private static final double HILLY_EROSION = 0.38;

	/** Real relief above the surrounding base level, in metres, where each band begins. */
	public static final double FOOTHILLS_M = 300.0;
	public static final double MONTANE_M = 900.0;
	public static final double SUBALPINE_M = 1800.0;
	public static final double PEAKS_M = 3000.0;

	/** One cell's climate. */
	public static final class Cell {
		public double temperature;
		public double humidity;
		public double continentalness;
		public double erosion;
		public double weirdness;
		public byte zone;
	}

	public final int nx;
	public final int nz;
	public final float[] temperature;
	public final float[] humidity;
	/** How far each cell stands above the lowland around it, metres. */
	public final float[] relief;
	public final float[] continentalness;
	public final float[] erosion;
	public final float[] weirdness;
	public final byte[] zone;

	private BiomeClimate(int nx, int nz) {
		this.nx = nx;
		this.nz = nz;
		int n = nx * nz;
		this.temperature = new float[n];
		this.humidity = new float[n];
		this.relief = new float[n];
		this.continentalness = new float[n];
		this.erosion = new float[n];
		this.weirdness = new float[n];
		this.zone = new byte[n];
	}

	/**
	 * The climate of one cell.
	 *
	 * @param heightM     metres above sea level; negative is sea floor. It sets the temperature
	 * @param reliefM     metres the ground stands above the lowland around it. It sets the band
	 * @param rainMm      simulated rainfall, millimetres a year
	 * @param coastBlocks distance to the sea, in blocks (0 at sea)
	 * @param river       a river runs through the cell
	 * @param t0          vanilla's temperature noise here, h0 its humidity, w0 its weirdness
	 */
	public static void cell(double heightM, double reliefM, double rainMm, double coastBlocks, boolean river, double t0, double h0, double w0, double wx, double wz,
			long seed, Dials d, Cell out) {
		cell(heightM, reliefM, rainMm, coastBlocks, river, t0, h0, w0, wx, wz, seed, 0.0, d, out);
	}

	/**
	 * The rainfall of the simulated continent as the climate wants it: the continent is dry (the middle of its land is some 500 to 650 mm a year, and a wet place 1,100), and
	 * {@link #cell} takes 1,000 mm as neither wet nor dry, so the continent's own range is stretched over the one the climate works in. Deserts then lie in the rain shadows
	 * and forests and jungles on the wet windward ground, as the simulation put the rain, and not as the vanilla noise alone had them.
	 */
	public static double climateRain(double simulatedMm) {
		return Math.max(0.0, 1000.0 + 1.85 * (simulatedMm - 650.0));
	}

	/** @param hilliness the character of the land, 0 for flat country through 1 for hill country (see {@link Hills}); the lowland of hill country has the erosion of vanilla's hills */
	public static void cell(double heightM, double reliefM, double rainMm, double coastBlocks, boolean river, double t0, double h0, double w0, double wx, double wz,
			long seed, double hilliness, Dials d, Cell out) {
		double sign = w0 < 0.0 ? -1.0 : 1.0;
		double wet = Noise.clamp((rainMm - 1000.0) / 1200.0, -1.0, 1.0);
		out.humidity = Noise.clamp(h0 + d.rainBias() * 0.6 * wet, -1.0, 1.0);
		if (heightM < 0.0) {
			double depth = -heightM;
			out.zone = OCEAN;
			out.temperature = Noise.clamp(t0, -1.0, 1.0);
			out.continentalness = depth >= DEEP_OCEAN_M ? -0.7 : depth >= 300.0 ? -0.35 : -0.25;
			out.erosion = 0.25;
			out.weirdness = 0.35 * sign;
			return;
		}
		// Value noise rarely strays far from its middle, so the wander is doubled to use the range it has. At full variety the band edges
		// wander by up to 90% of the relief, the parameters by their whole allowance, and the temperature by a tenth: neighbouring slopes,
		// and the two flanks of one range, then differ, and a cold region still has its milder patches.
		double wander = Noise.clamp(d.variety() * 2.0 * Noise.fbm(seed + 203, wx / 1800.0, wz / 1800.0, 2), -1.0, 1.0);
		double swing = Noise.clamp(d.variety() * 2.0 * Noise.fbm(seed + 205, wx / 1500.0, wz / 1500.0, 2), -1.0, 1.0);
		double mild = Noise.clamp(d.variety() * 2.0 * Noise.fbm(seed + 207, wx / 2200.0, wz / 2200.0, 2), -1.0, 1.0);
		double shatter = d.variety() * 2.0 * Noise.fbm(seed + 211, wx / 5200.0, wz / 5200.0, 2);
		double rocky = d.variety() * 2.0 * Noise.fbm(seed + 213, wx / 1800.0, wz / 1800.0, 2);
		double bare = d.variety() * 2.0 * Noise.fbm(seed + 215, wx / 6500.0, wz / 6500.0, 2);
		out.temperature = Noise.clamp(t0 - d.lapse() * heightM / 1000.0 + 0.10 * mild, -1.0, 1.0);
		double edge = reliefM * (1.0 + 0.9 * Noise.clamp(d.variety() * 2.0 * Noise.fbm(seed + 201, wx / 2800.0, wz / 2800.0, 2), -1.0, 1.0));
		byte zone;
		double erosion;
		double magnitude;
		if (edge < FOOTHILLS_M) {
			zone = LOWLAND;
			// hill country has the erosion of vanilla's hills (an erosion of -0.13 is its third band, not its fourth), which is where its table has the badlands and mesas of the hot
			// in place of the desert, and the plateau biomes of the far inland; flat country keeps the fourth band, its plains
			erosion = 0.25 + 0.08 * wander - HILLY_EROSION * Noise.smooth(0.70, 0.95, hilliness);
			magnitude = 0.40 + 0.08 * swing;
		} else if (edge < MONTANE_M) {
			zone = FOOTHILLS;
			erosion = -0.30 + 0.05 * wander;
			magnitude = 0.40 + 0.08 * swing;
		} else if (edge < SUBALPINE_M) {
			zone = MONTANE;
			erosion = -0.55 + 0.12 * wander;
			magnitude = 0.30 + 0.05 * swing;
		} else if (edge < PEAKS_M) {
			zone = SUBALPINE;
			erosion = -0.90 + 0.06 * wander;
			magnitude = 0.30 + 0.05 * swing;
		} else {
			zone = PEAKS;
			erosion = -0.90 + 0.06 * wander;
			magnitude = 0.67 + 0.05 * swing;
		}
		// the shattered band of vanilla's table is where its windswept hills, forests, gravelly hills and savannas are: patches of the hill country take it
		if ((zone == FOOTHILLS || zone == MONTANE) && shatter > SHATTER_THRESHOLD) {
			erosion = 0.50 + 0.03 * wander;
		}
		// vanilla's stony peaks are the peaks of a warm climate (a temperature of 0.2 to 0.55), which the lapse rate never leaves a summit: patches of the peaks are bare warm rock
		if (zone == PEAKS && bare > BARE_SUMMIT_THRESHOLD) {
			out.temperature = 0.32 + 0.12 * mild;
		}
		// and the same on the near-coast lowland, where the shattered band is vanilla's windswept savanna
		if (zone == LOWLAND && coastBlocks > 96.0 && coastBlocks <= 700.0 && shatter > SHATTER_THRESHOLD) {
			erosion = 0.50 + 0.03 * wander;
		}
		out.zone = zone;
		out.erosion = erosion;
		out.weirdness = sign * magnitude;
		// vanilla's table only gives a beach where weirdness is small but not a valley's (between about 0.05 and 0.27, either sign), and the
		// lowland's usual 0.4 is outside that, so a coast of lowland asks for 0.2
		boolean shore = coastBlocks <= 96.0;
		if (shore) {
			out.weirdness = sign * 0.20;
			// a coast of rock and not sand (vanilla's stony shore) has the erosion of hills; a coast of high ground is rock whatever
			if (zone == LOWLAND && rocky > ROCKY_COAST_THRESHOLD) {
				out.erosion = -0.30;
			}
		}
		// how far inland: a thin coast, then near, mid and far inland; mountains are always far inland, where the mountain biomes are
		double cont;
		if (coastBlocks <= 96.0) {
			cont = -0.15;
		} else if (coastBlocks <= 250.0) {
			cont = -0.05;
		} else if (coastBlocks <= 700.0) {
			cont = 0.15;
		} else {
			cont = 0.5;
		}
		out.continentalness = zone == LOWLAND || shore ? cont : Math.max(cont, 0.45);
		if (river && zone <= FOOTHILLS) {
			out.erosion = -0.1; // the valley setting: vanilla answers river or frozen river
			out.weirdness = 0.0;
		}
	}

	/**
	 * The continentalness that vanilla's table is asked for below the surface, which is what chooses between its lush and dripstone caves (dripstone wants 0.8 or
	 * more, far inland). At the surface every column far from the sea is the same 0.5, which is right for the surface biomes but would make every cave a dripstone
	 * cave, so below it the number is the distance from the sea made regional: low at a coast, rising to about 0.75 three kilometres inland, with slow swings of
	 * half that on either side (a region of dripstone caves, a region without).
	 */
	public static double undergroundContinentalness(long seed, double wx, double wz, double coastBlocks) {
		double inland = Noise.smooth(300.0, 3000.0, coastBlocks);
		return Noise.clamp(0.2 + 0.55 * inland + 0.5 * Noise.fbm(seed + 0x5DEECE66DL, wx / 2400.0, wz / 2400.0, 2), -1.0, 1.0);
	}

	/** Works out the climate of every cell of a continent. */
	public static BiomeClimate compute(Continent c, VanillaNoise vanilla, Dials dials) {
		BiomeClimate result = new BiomeClimate(c.nx, c.nz);
		float[] coast = coastDistance(c);
		float[] relief = reliefAboveBase(c);
		// vanilla's noise varies over kilometres, so it is sampled on every second cell
		int cnx = (c.nx + 1) / 2;
		int cnz = (c.nz + 1) / 2;
		float[] t = new float[cnx * cnz];
		float[] h = new float[cnx * cnz];
		float[] w = new float[cnx * cnz];
		double[] sample = new double[3];
		for (int z = 0; z < cnz; z++) {
			for (int x = 0; x < cnx; x++) {
				vanilla.at(c.x0 + 2 * x * c.cell, c.z0 + 2 * z * c.cell, sample);
				int k = z * cnx + x;
				t[k] = (float) sample[0];
				h[k] = (float) sample[1];
				w[k] = (float) sample[2];
			}
		}
		Cell cell = new Cell();
		long seed = c.config.seed();
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				int i = z * c.nx + x;
				int k = (z >> 1) * cnx + (x >> 1);
				cell(c.height[i], relief[i], climateRain(c.rain[i]), coast[i], c.river[i], t[k], h[k], w[k], c.x0 + x * c.cell, c.z0 + z * c.cell, seed, dials, cell);
				result.temperature[i] = (float) cell.temperature;
				result.humidity[i] = (float) cell.humidity;
				result.relief[i] = relief[i];
				result.continentalness[i] = (float) cell.continentalness;
				result.erosion[i] = (float) cell.erosion;
				result.weirdness[i] = (float) cell.weirdness;
				result.zone[i] = cell.zone;
			}
		}
		return result;
	}

	/**
	 * How far each land cell stands above the lowland around it, in metres: the height minus a base level found by closing the land
	 * (which fills valleys, lakes and river gorges) and then opening it (which removes ridges and ranges narrower than about 7 km), so a
	 * broad plateau is its own base and a range is all relief.
	 */
	static float[] reliefAboveBase(Continent c) {
		int n = c.nx * c.nz;
		float[] land = new float[n];
		for (int i = 0; i < n; i++) {
			land[i] = Math.max(0.0f, c.height[i]);
		}
		float[] base = land.clone();
		int fill = Math.max(2, (int) Math.round(500.0 / c.cell));
		int strip = Math.max(4, (int) Math.round(3600.0 / c.cell));
		slide(base, c.nx, c.nz, fill, true);
		slide(base, c.nx, c.nz, fill, false);
		slide(base, c.nx, c.nz, strip, false);
		slide(base, c.nx, c.nz, strip, true);
		float[] relief = new float[n];
		for (int i = 0; i < n; i++) {
			relief[i] = Math.max(0.0f, land[i] - base[i]);
		}
		return relief;
	}

	/** Replaces every value by the largest (or smallest) within {@code radius} cells in both directions. */
	private static void slide(float[] a, int nx, int nz, int radius, boolean max) {
		float[] line = new float[Math.max(nx, nz)];
		int[] queue = new int[Math.max(nx, nz)];
		for (int z = 0; z < nz; z++) {
			filter(a, z * nx, 1, nx, radius, max, line, queue);
		}
		for (int x = 0; x < nx; x++) {
			filter(a, x, nx, nz, radius, max, line, queue);
		}
	}

	private static void filter(float[] a, int offset, int stride, int length, int radius, boolean max, float[] out, int[] queue) {
		int head = 0;
		int tail = 0;
		int next = 0;
		for (int i = 0; i < length; i++) {
			int hi = Math.min(length - 1, i + radius);
			while (next <= hi) {
				float v = a[offset + next * stride];
				while (tail > head && (max ? a[offset + queue[tail - 1] * stride] <= v : a[offset + queue[tail - 1] * stride] >= v)) {
					tail--;
				}
				queue[tail++] = next++;
			}
			while (queue[head] < i - radius) {
				head++;
			}
			out[i] = a[offset + queue[head] * stride];
		}
		for (int i = 0; i < length; i++) {
			a[offset + i * stride] = out[i];
		}
	}

	/** Distance from each land cell to the nearest sea cell, in blocks (a two-pass chamfer transform). */
	static float[] coastDistance(Continent c) {
		int nx = c.nx;
		int nz = c.nz;
		float[] d = new float[nx * nz];
		float far = 1.0e9f;
		for (int i = 0; i < d.length; i++) {
			d[i] = c.height[i] < 0.0f ? 0.0f : far;
		}
		float diagonal = 1.4142f;
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				int i = z * nx + x;
				float best = d[i];
				if (x > 0) {
					best = Math.min(best, d[i - 1] + 1.0f);
				}
				if (z > 0) {
					best = Math.min(best, d[i - nx] + 1.0f);
					if (x > 0) {
						best = Math.min(best, d[i - nx - 1] + diagonal);
					}
					if (x < nx - 1) {
						best = Math.min(best, d[i - nx + 1] + diagonal);
					}
				}
				d[i] = best;
			}
		}
		for (int z = nz - 1; z >= 0; z--) {
			for (int x = nx - 1; x >= 0; x--) {
				int i = z * nx + x;
				float best = d[i];
				if (x < nx - 1) {
					best = Math.min(best, d[i + 1] + 1.0f);
				}
				if (z < nz - 1) {
					best = Math.min(best, d[i + nx] + 1.0f);
					if (x < nx - 1) {
						best = Math.min(best, d[i + nx + 1] + diagonal);
					}
					if (x > 0) {
						best = Math.min(best, d[i + nx - 1] + diagonal);
					}
				}
				d[i] = best;
			}
		}
		for (int i = 0; i < d.length; i++) {
			d[i] = Math.min(d[i] * (float) c.cell, 1.0e6f);
		}
		return d;
	}
}
