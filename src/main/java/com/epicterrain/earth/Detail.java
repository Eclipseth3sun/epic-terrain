package com.epicterrain.earth;

/**
 * The ground at the scale a player walks it. The simulation works on cells of 32 blocks, so on its own the ground is smooth between
 * them: a range is a set of broad hummocks, nothing is steeper than a gentle hillside, and there are no crags. This puts the fine
 * relief back, as detail laid over a smooth (bicubic) reading of the simulated heights:
 * <ul>
 *   <li>how much depends on <b>relief</b>: none on the plains, a little on hills, a lot on high ground in young ranges, because crags and
 *       cliffs belong to mountains, and less in old ranges, which are worn down;</li>
 *   <li>it fades out toward <b>sea level</b>, so shores stay clean, and beside <b>rivers</b>, so the water the simulation worked out
 *       still runs where it should;</li>
 *   <li>its main part is <b>gullies</b>: on a slope, ground is cut into ribs and channels that run down it, three scales of them, the
 *       coarse ones straight down the fall line and the fine ones wandering from it. Every patch of ground lays stripes across the fall
 *       line with a phase of its own, and the patches around a point are blended in phase, so the pattern is continuous; the rest is
 *       fine roughness.</li>
 * </ul>
 * Pure, and a function of position only, so any chunk can ask for any column and neighbours agree.
 */
public final class Detail {
	/**
	 * @param crag  how strong the fine relief is: 0 leaves the ground smooth, 1 is the default, 2 is twice as craggy
	 * @param hills how much the lowland rolls (see {@link Hills}): 0 is as flat as the simulation made it, 1 is the default, 2 is twice the hills
	 */
	public record Dials(double crag, double hills) {
		public Dials(double crag) {
			this(crag, 1.0);
		}

		public static Dials defaults() {
			return new Dials(1.0, 1.0);
		}

		public static Dials fromProperties() {
			String crag = System.getProperty("epicterrain.earth.crag");
			String hills = System.getProperty("epicterrain.earth.hills");
			return new Dials(crag == null ? 1.0 : Double.parseDouble(crag), hills == null ? 1.0 : Double.parseDouble(hills));
		}
	}

	/** Relief, in metres, over which the fine detail comes in: none at the lower figure, all of it at the higher. */
	static final double RELIEF_FROM = 80.0;
	static final double RELIEF_FULL = 1400.0;
	/**
	 * What crag 1 is worth. Chosen from the slope table of {@code EarthDetailPreviewTest} (seed 4242): over 16 blocks the ground then
	 * averages 8 degrees in the foothills, 24 in the montane band, 26.5 in the subalpine and 28 on the peaks (16% of the peaks' ground is
	 * steeper than 45 degrees), a little gentler than vanilla's peaks (35.5 mean, 30% over 45) and about vanilla's hills (24).
	 */
	private static final double STRENGTH = 1.8;
	/** The most of its height above sea level that the fine detail may cut away from a point of land. */
	private static final double KEEP = 0.6;
	/** Gully scales: the spacing of the ribs (blocks) and how deep they cut (blocks) on the steepest, highest ground at crag 1. */
	private static final double[] SPACING = { 120.0, 56.0, 26.0 };
	private static final double[] DEPTH = { 28.0, 13.0, 5.5 };
	/** How far the gullies of each scale stray from straight down the fall line (radians), so they branch and wander. */
	private static final double[] STRAY = { 0.22, 0.5, 0.8 };
	/** A patch of stripes is this many spacings across. */
	private static final double PATCH = 1.6;
	/** How far a patch reaches, as a share of its width (the standard deviation of its weight). */
	private static final double SIGMA = 0.5;

	private final Continent continent;
	private final float[] relief;
	private final float[] nearRiver;
	/** Beside a river: 1 on it, falling over some 6 cells, so that the hills part round a valley. */
	private final float[] nearRiverWide;
	/** The hills of the lowland, in blocks above or below the simulated ground, at each cell; and how much of each cell is dunes; and the character of the region (0 flat, 1 hilly). */
	private final float[] hillBlocks;
	private final float[] dunes;
	private final float[] character;
	private final long seed;
	private final double crag;
	private final double hills;

	public Detail(Continent continent, Dials dials) {
		this.continent = continent;
		this.relief = BiomeClimate.reliefAboveBase(continent);
		this.seed = continent.config.seed();
		this.crag = dials.crag();
		// 1 on a river, falling to 0 two cells away
		int n = continent.nx * continent.nz;
		float[] rivers = new float[n];
		for (int i = 0; i < n; i++) {
			rivers[i] = continent.river[i] ? 1.0f : 0.0f;
		}
		float[] spread = rivers.clone();
		for (int pass = 0; pass < 2; pass++) {
			float[] next = spread.clone();
			for (int z = 1; z < continent.nz - 1; z++) {
				for (int x = 1; x < continent.nx - 1; x++) {
					int i = z * continent.nx + x;
					float around = Math.max(Math.max(spread[i - 1], spread[i + 1]), Math.max(spread[i - continent.nx], spread[i + continent.nx]));
					next[i] = Math.max(spread[i], 0.65f * around);
				}
			}
			spread = next;
		}
		this.nearRiver = spread;
		// the hills part round the water as they do round a river: the ground of a lake's basin and of a river's valley is the water's to settle
		float[] wide = rivers.clone();
		for (int i = 0; i < n; i++) {
			if (continent.lakeDepth[i] > 0.0f) {
				wide[i] = 1.0f;
			}
		}
		for (int pass = 0; pass < 6; pass++) {
			float[] next = wide.clone();
			for (int z = 1; z < continent.nz - 1; z++) {
				for (int x = 1; x < continent.nx - 1; x++) {
					int i = z * continent.nx + x;
					float around = Math.max(Math.max(wide[i - 1], wide[i + 1]), Math.max(wide[i - continent.nx], wide[i + continent.nx]));
					next[i] = Math.max(wide[i], 0.8f * around);
				}
			}
			wide = next;
		}
		this.nearRiverWide = wide;
		this.hills = dials.hills();
		this.hillBlocks = new float[n];
		this.dunes = new float[n];
		this.character = new float[n];
		for (int z = 0; z < continent.nz; z++) {
			for (int x = 0; x < continent.nx; x++) {
				int i = z * continent.nx + x;
				double wx = continent.x0 + x * continent.cell;
				double wz = continent.z0 + z * continent.cell;
				double ch = Hills.character(this.seed, wx, wz);
				this.character[i] = (float) ch;
				double metres = continent.height[i];
				if (metres <= 0.0 || this.hills <= 0.0) {
					continue;
				}
				double base = Continent.blocksAbove(metres);
				this.hillBlocks[i] = (float) Hills.offset(this.seed, wx, wz, base, this.relief[i], continent.rain[i], ch, this.hills);
				this.dunes[i] = (float) Hills.dunes(base, this.relief[i], continent.rain[i], ch, this.hills);
			}
		}
	}

	/** The character of the land at a position, 0 for flat country through 1 for hill country (see {@link Hills}); the biome mapping reads it as well as the ground. */
	public double character(double wx, double wz) {
		if (this.hills <= 0.0) {
			return 0.0; // no hills, no hill country
		}
		return Noise.clamp(this.smooth(this.character, wx, wz), 0.0, 1.0);
	}

	/** The rolling hills and dunes of the lowland at a position, in blocks. */
	private double hillsAt(double wx, double wz) {
		if (this.hills <= 0.0) {
			return 0.0;
		}
		double offset = this.smooth(this.hillBlocks, wx, wz);
		double dune = Noise.clamp(this.smooth(this.dunes, wx, wz), 0.0, 1.0);
		if (dune > 0.01) {
			offset += Hills.DUNE_HEIGHT * dune * Hills.duneRidge(this.seed, wx, wz);
		}
		double river = 1.0 - Noise.smooth(0.05, 0.75, Noise.clamp(this.smooth(this.nearRiverWide, wx, wz), 0.0, 1.0));
		return offset * river;
	}

	private static double catmull(double a, double b, double c, double d, double t) {
		return b + 0.5 * t * (c - a + t * (2.0 * a - 5.0 * b + 4.0 * c - d + t * (3.0 * (b - c) + d - a)));
	}

	private double grid(float[] field, int x, int z) {
		int cx = Math.max(0, Math.min(this.continent.nx - 1, x));
		int cz = Math.max(0, Math.min(this.continent.nz - 1, z));
		return field[cz * this.continent.nx + cx];
	}

	/** A smooth (bicubic) reading of a field at a world position. */
	private double smooth(float[] field, double wx, double wz) {
		double gx = (wx - this.continent.x0) / this.continent.cell;
		double gz = (wz - this.continent.z0) / this.continent.cell;
		int x = (int) Math.floor(gx);
		int z = (int) Math.floor(gz);
		double tx = gx - x;
		double tz = gz - z;
		double[] rows = new double[4];
		for (int j = 0; j < 4; j++) {
			rows[j] = catmull(this.grid(field, x - 1, z - 1 + j), this.grid(field, x, z - 1 + j), this.grid(field, x + 1, z - 1 + j), this.grid(field, x + 2, z - 1 + j), tx);
		}
		return catmull(rows[0], rows[1], rows[2], rows[3], tz);
	}

	/** The simulated ground, smooth between cells, in blocks above sea level (negative is the sea floor). */
	public double smoothBlocks(double wx, double wz) {
		return Continent.blocksAbove(this.smooth(this.continent.height, wx, wz));
	}

	/** The ground with its fine relief, in blocks above sea level. */
	public double blocks(double wx, double wz) {
		double metres = this.smooth(this.continent.height, wx, wz);
		double base = Continent.blocksAbove(metres);
		if (metres <= 0.0) {
			return base; // the sea floor is left smooth
		}
		base += this.hillsAt(wx, wz);
		double relief = Math.max(0.0, this.smooth(this.relief, wx, wz));
		double rugged = Noise.smooth(RELIEF_FROM, RELIEF_FULL, relief);
		double shore = Noise.smooth(2.0, 45.0, metres);
		double river = 1.0 - 0.92 * Noise.clamp(this.smooth(this.nearRiver, wx, wz), 0.0, 1.0);
		double fade = rugged * shore * river;
		if (fade <= 0.0 || this.crag <= 0.0) {
			return base;
		}
		// old ranges are worn down: rounded, rolling, a good deal less cut than the young ones
		double age = 1.0 - 0.5 * Noise.clamp(this.smooth(this.continent.old, wx, wz), 0.0, 1.0) * (1.0 - Noise.clamp(this.smooth(this.continent.young, wx, wz), 0.0, 1.0));
		double[] slope = this.gradient(wx, wz, 15.0);
		double steep = Noise.smooth(0.03, 0.40, Math.hypot(slope[0], slope[1])); // 0 on a gentle slope, 1 where it is steeper than about 22 degrees
		double[] fall = this.fall(wx, wz);
		double cut = 0.0;
		for (int k = 0; k < SPACING.length; k++) {
			cut += DEPTH[k] * this.gully(k, wx, wz);
		}
		double broad = Math.pow(rugged, 1.1) * age * fall[1] * (0.45 + 0.55 * steep) * cut;
		double swell = 3.0 * rugged * Noise.fbm(this.seed + 401, wx / 170.0, wz / 170.0, 3);
		double fine = (0.8 + 1.6 * rugged) * Noise.fbm(this.seed + 405, wx / 15.0, wz / 15.0, 2);
		// foothills get a little roughness on their own, so they are not smooth next to the crags above
		double low = 1.6 * Noise.smooth(0.0, 400.0, relief) * Noise.fbm(this.seed + 407, wx / 40.0, wz / 40.0, 2);
		double change = this.crag * STRENGTH * (broad + swell + fine + low) * shore * river;
		// land stays land: a cut can take away at most most of the height there is, never the whole of it
		return base + Math.max(change, -KEEP * base);
	}

	/** The slope of the smooth ground at a position, as (rise over run in x, in z), measured over 2 * e blocks. */
	private double[] gradient(double wx, double wz, double e) {
		return new double[] { (this.smoothBlocks(wx + e, wz) - this.smoothBlocks(wx - e, wz)) / (2.0 * e),
			(this.smoothBlocks(wx, wz + e) - this.smoothBlocks(wx, wz - e)) / (2.0 * e) };
	}

	/**
	 * Which way the water would run on the smooth ground: the direction (an angle, radians) of steepest descent over {@code 2 * e}
	 * blocks, and how clear that is, 0 where the ground is level (the top of a hump, a plateau) and there is no fall line to cut along, up
	 * to 1. The clarity fades the gullies out on level ground.
	 */
	double[] fall(double wx, double wz, double e) {
		double[] g = this.gradient(wx, wz, e);
		return new double[] { Math.atan2(-g[1], -g[0]), Noise.smooth(0.015, 0.10, Math.hypot(g[0], g[1])) };
	}

	/** The fall line over about 90 blocks. */
	double[] fall(double wx, double wz) {
		return this.fall(wx, wz, 45.0);
	}

	/** Where a patch of stripes is centred. */
	private double patchX(int scale, int i, int j) {
		return (i + 0.2 + 0.6 * Noise.unit(this.seed, i, j, 411 + 10L * scale)) * PATCH * SPACING[scale];
	}

	private double patchZ(int scale, int i, int j) {
		return (j + 0.2 + 0.6 * Noise.unit(this.seed, i, j, 412 + 10L * scale)) * PATCH * SPACING[scale];
	}

	/**
	 * Ribs and channels running down the slope at one scale, about -0.2 to 1: sharp ribs and broad channels between them. The stripes run
	 * along the fall line at the point itself (measured over about the scale's own spacing, so the finer the gully the more local the
	 * fall line it follows), turned by a slow wander that grows with the scale's stray. The ground is covered in patches on a jittered
	 * grid, each laying stripes across that direction with a phase of its own; the stripes of the patches around a point are added with
	 * their phases, so they join up, and where two patches disagree they fade, as a drainage divide does.
	 */
	double gully(int scale, double wx, double wz) {
		double spacing = SPACING[scale];
		double span = PATCH * spacing;
		double[] fall = this.fall(wx, wz, spacing);
		double across = fall[0] + STRAY[scale] * Noise.fbm(this.seed + 421 + scale, wx / (2.5 * spacing), wz / (2.5 * spacing), 1) + 0.5 * Math.PI;
		double ax = StrictMath.cos(across);
		double az = StrictMath.sin(across);
		int i0 = (int) Math.floor(wx / span);
		int j0 = (int) Math.floor(wz / span);
		double c = 0.0;
		double s = 0.0;
		double w = 0.0;
		double sigma2 = 2.0 * (SIGMA * span) * (SIGMA * span);
		// two patches out each way: the nearest one left out is more than two spans away, so it would weigh 0.1% and nothing jumps as a
		// point crosses from one grid square to the next
		for (int di = -2; di <= 2; di++) {
			for (int dj = -2; dj <= 2; dj++) {
				int i = i0 + di;
				int j = j0 + dj;
				double dx = wx - this.patchX(scale, i, j);
				double dz = wz - this.patchZ(scale, i, j);
				double weight = StrictMath.exp(-(dx * dx + dz * dz) / sigma2);
				double phase = 2.0 * Math.PI * ((dx * ax + dz * az) / spacing + Noise.unit(this.seed, i, j, 415 + 10L * scale));
				c += weight * StrictMath.cos(phase);
				s += weight * StrictMath.sin(phase);
				w += weight;
			}
		}
		// where the patches cancel there is no stripe to show: fade out, but only where they nearly do, so the stripes keep their depth
		double agree = Noise.smooth(0.15, 0.60, Math.hypot(c, s) / w);
		double theta = StrictMath.atan2(s, c);
		return (StrictMath.pow(0.5 * (1.0 + StrictMath.cos(theta)), 2.5) - 0.2) * agree * fall[1];
	}
}
