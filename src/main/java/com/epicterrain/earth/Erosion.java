package com.epicterrain.earth;

import java.util.Arrays;

/**
 * Wears the landscape down while the tectonic episodes lift it: water runs downhill, cuts valleys with a force that grows with the
 * area it drains and the slope it runs down (the stream-power law), carries what it cuts away and drops it where the slope eases
 * (plains, deltas, filled lakes), and soil creeps down the hillsides.
 *
 * <p>Everything is in real units: heights in metres above sea level, cells {@code cellMetres} wide, time in years. Sea level is 0; cells below it
 * are the ocean and the edge of the window is held fixed, so rivers have somewhere to go.
 *
 * <p>Each step does, in order:
 * <ol>
 *   <li>uplift from the episodes that are active at that point in the history, and volcano building near the end;</li>
 *   <li>drainage: the priority-flood fill finds where every cell's water goes (lakes fill to their spill point) and gives an order in
 *       which water always runs from earlier cells to later ones;</li>
 *   <li>the implicit stream-power step, upstream cells measured against their already updated downstream neighbour, so it stays
 *       stable however long the step;</li>
 *   <li>sediment: what was cut is carried down and dropped where the slope can no longer carry it;</li>
 *   <li>creep: a small diffusion that rounds off ridges, which is what turns an old range into rolling hills.</li>
 * </ol>
 */
final class Erosion {
	/** Years of history that are simulated. */
	static final double YEARS = 6.0e7;
	/** Stream-power erodibility, per year (with drainage area in square metres to the half). */
	static final double K = tuned("k", 6.0e-7);
	/** Hillslope diffusivity, square metres per year. */
	static final double DIFFUSION = tuned("d", 0.01);
	/** Transport capacity compared with the cutting power of the same water. */
	static final double KAPPA = tuned("kappa", 2.0);
	/** Uplift, metres per year, at amplitude 1. */
	/** Soil creeps this many times faster on the shelf and coast at the window's edge, where waves round off the headlands. */
	static final double COAST_CREEP = 30.0;
	static final double UPLIFT = tuned("uplift", 1.0e-4);
	private static final double SQRT2 = Math.sqrt(2.0);
	private static final int[] SCAN_X = { -1, 0, 1, -1, 1, -1, 0, 1 };
	private static final int[] SCAN_Z = { -1, -1, -1, 0, 0, 1, 1, 1 };

	/** The erosion constants can be overridden with {@code -Depicterrain.earth.k=...} while tuning. */
	private static double tuned(String key, double fallback) {
		String value = System.getProperty("epicterrain.earth." + key);
		return value == null ? fallback : Double.parseDouble(value);
	}

	final Landscape land;
	final int nx;
	final int nz;
	final int n;
	final double dx;
	final float[] h;
	final boolean[] edge;
	final int[] order;
	final int[] receiver;
	final double[] zfill;
	final float[] area;
	final boolean[] lake;
	/** Cells that water had to be spilled over: pits, lakes, and the bars at their level. */
	final boolean[] flat;
	private final float[] creepFactor;
	/** Tiny (millimetres) smooth random numbers that decide which way water turns on flat ground. */
	private final float[] flatKey;
	/** The height in the column of rock that each cell's surface stands at: only erosion and deposition change it, uplift carries rock and surface together. */
	private final float[] strat;
	/** How fast the rock at each cell's surface erodes, around 1, and how far it resists creep. */
	final float[] lith;
	private final float[] creepLith;
	/** The ground after the uplift of a step, to see what erosion and deposition did to it. */
	private final float[] pre;
	private Strata strata;
	/** A fixed random weight for each cell's eight downhill directions, around 1: see {@link #flood}. */
	private final float[] directionWeight;
	private int stepIndex;
	private final boolean[] closed;
	private final float[] hnew;
	private final float[] surface;
	private final float[] sediment;
	private final float[] scratch;
	private final double[] heapZ;
	private final int[] heapI;
	private int heapSize;
	int count;

	Erosion(Landscape land) {
		this.land = land;
		this.nx = land.nx;
		this.nz = land.nz;
		this.n = land.nx * land.nz;
		this.dx = land.cell * land.config.blockMetres();
		this.h = new float[this.n];
		this.edge = new boolean[this.n];
		this.order = new int[this.n];
		this.receiver = new int[this.n];
		this.zfill = new double[this.n];
		this.area = new float[this.n];
		this.lake = new boolean[this.n];
		this.flat = new boolean[this.n];
		this.creepFactor = new float[this.n];
		this.flatKey = new float[this.n];
		this.strat = new float[this.n];
		this.lith = new float[this.n];
		this.creepLith = new float[this.n];
		this.pre = new float[this.n];
		Arrays.fill(this.lith, 1.0f);
		Arrays.fill(this.creepLith, 1.0f);
		this.directionWeight = new float[8 * this.n];
		this.closed = new boolean[this.n];
		this.hnew = new float[this.n];
		this.surface = new float[this.n];
		this.sediment = new float[this.n];
		this.scratch = new float[this.n];
		this.heapZ = new double[this.n];
		this.heapI = new int[this.n];
	}

	/** Starts from the crust, measured from a provisional sea level, with a little roughness to start the valleys. */
	void initialise(double seaLevel) {
		long s = this.land.config.seed();
		this.strata = new Strata(s);
		for (int z = 0; z < this.nz; z++) {
			for (int x = 0; x < this.nx; x++) {
				int i = z * this.nx + x;
				double wx = this.land.worldX(x);
				double wz = this.land.worldZ(z);
				double rough = 40.0 * Noise.fbm(s + 301, wx / 1500.0, wz / 1500.0, 3) + 20.0 * Noise.fbm(s + 303, wx / 400.0, wz / 400.0, 2);
				this.h[i] = (float) (this.land.base[i] - seaLevel + rough - 1500.0 * this.land.rim[i]);
				this.edge[i] = x == 0 || z == 0 || x == this.nx - 1 || z == this.nz - 1;
				this.strat[i] = this.h[i] - this.land.bedding[i];
				this.creepFactor[i] = 1.0f + (float) (COAST_CREEP * this.land.rim[i]);
				for (int o = 0; o < 8; o++) {
					this.directionWeight[8 * i + o] = (float) (0.55 + 0.9 * Noise.unit(Noise.hash(s + 991L, i, o)));
				}
				this.flatKey[i] = (float) (1.0e-3 * (0.8 * Noise.value(s + 311, wx / 250.0, wz / 250.0) + 0.2 * Noise.unit(Noise.hash(s + 313, x, z))));
			}
		}
	}

	/** Runs the whole history. */
	void run() {
		int steps = this.land.config.steps();
		double dt = YEARS / steps;
		for (int step = 0; step < steps; step++) {
			this.stepIndex = step;
			this.step((step + 0.5) / steps, dt);
		}
	}

	private static double fOld(double t, double off) {
		return 1.0 - Noise.smooth(off, off + 0.08, t);
	}

	void step(double t, double dt) {
		double young = Noise.smooth(0.42, 0.62, t);
		double mid = Noise.smooth(0.25, 0.40, t) * (1.0 - Noise.smooth(0.70, 0.90, t));
		double react = Noise.smooth(0.70, 0.80, t);
		double build = t >= 0.82 ? dt / (0.18 * YEARS) : 0.0;
		for (int i = 0; i < this.n; i++) {
			if (this.edge[i]) {
				continue;
			}
			double u = this.land.old[i] * fOld(t, this.land.toff[i]) + this.land.young[i] * young + this.land.mid[i] * mid + this.land.react[i] * react + this.land.background[i];
			double keep = 1.0 - this.land.rim[i];
			this.h[i] += (float) ((u * UPLIFT * dt + this.land.construct[i] * build) * keep);
		}
		System.arraycopy(this.h, 0, this.pre, 0, this.n);
		this.updateRock();
		this.flood();
		this.accumulate();
		this.cut(dt);
		this.deposit(dt);
		this.creep(dt);
		for (int i = 0; i < this.n; i++) {
			this.strat[i] += this.hnew[i] - this.pre[i];
		}
		System.arraycopy(this.hnew, 0, this.h, 0, this.n);
	}

	/**
	 * Looks up the bed at every cell's surface. {@code rock} scales how much it matters (an exponent on the bed's hardness, so 0 is uniform rock
	 * exactly as before), and the hard core of a dome is hard whatever the bed. Hard rock also resists creep, so it keeps its cliffs.
	 */
	private void updateRock() {
		double rock = this.land.config.rock();
		for (int i = 0; i < this.n; i++) {
			double contrast = rock * this.land.layering[i];
			double dome = Math.max(0.1, 1.0 - rock * (1.0 - this.land.pluton[i]));
			double value = (contrast == 0.0 ? 1.0 : Math.exp(contrast * this.strata.ln(this.strat[i]))) * dome;
			this.lith[i] = (float) value;
			this.creepLith[i] = (float) Math.max(0.3, Math.min(1.0, Math.sqrt(value)));
		}
	}

	// ---- drainage ------------------------------------------------------------------------------------------------------

	private boolean outlet(int i) {
		return this.edge[i] || this.h[i] < 0.0f;
	}

	private void push(double z, int i) {
		int k = this.heapSize++;
		while (k > 0) {
			int parent = (k - 1) >> 1;
			if (this.heapZ[parent] <= z) {
				break;
			}
			this.heapZ[k] = this.heapZ[parent];
			this.heapI[k] = this.heapI[parent];
			k = parent;
		}
		this.heapZ[k] = z;
		this.heapI[k] = i;
	}

	private int pop() {
		int top = this.heapI[0];
		this.heapSize--;
		if (this.heapSize > 0) {
			double z = this.heapZ[this.heapSize];
			int i = this.heapI[this.heapSize];
			int k = 0;
			int half = this.heapSize >> 1;
			while (k < half) {
				int child = 2 * k + 1;
				if (child + 1 < this.heapSize && this.heapZ[child + 1] < this.heapZ[child]) {
					child++;
				}
				if (this.heapZ[child] >= z) {
					break;
				}
				this.heapZ[k] = this.heapZ[child];
				this.heapI[k] = this.heapI[child];
				k = child;
			}
			this.heapZ[k] = z;
			this.heapI[k] = i;
		}
		return top;
	}

	/**
	 * Priority-flood from the sea and the edge inward: every cell's water goes to the neighbour it was reached from, depressions are
	 * filled to their spill point, and {@link #order} lists the cells so that water always runs from earlier ones to later ones.
	 */
	void flood() {
		Arrays.fill(this.closed, false);
		Arrays.fill(this.flat, false);
		this.heapSize = 0;
		int cnt = 0;
		for (int i = 0; i < this.n; i++) {
			if (this.outlet(i)) {
				this.closed[i] = true;
				this.receiver[i] = i;
				this.zfill[i] = this.h[i];
				this.order[cnt++] = i;
			}
		}
		for (int i = 0; i < this.n; i++) {
			if (this.closed[i] && this.touchesLand(i)) {
				this.push(this.zfill[i], i);
			}
		}
		int outlets = cnt;
		while (this.heapSize > 0) {
			int c = this.pop();
			if (!this.outlet(c)) {
				this.order[cnt++] = c; // lowest first: water always runs from an earlier cell to a later one
			}
			double zc = this.zfill[c];
			int cx = c % this.nx;
			int cz = c / this.nx;
			for (int k = 0; k < 8; k++) {
				int xx = cx + SCAN_X[k];
				int zz = cz + SCAN_Z[k];
				if (xx < 0 || zz < 0 || xx >= this.nx || zz >= this.nz) {
					continue;
				}
				int m = zz * this.nx + xx;
				if (this.closed[m]) {
					continue;
				}
				this.closed[m] = true;
				this.receiver[m] = c;
				if (this.h[m] <= zc) {
					// ground lower than the water that reached it: a pit or a flat. It fills to that level, and is taken in a smooth random
					// order rather than strictly nearest-first, which would send every drop down a straight line at 45 degrees
					this.zfill[m] = zc;
					this.flat[m] = true;
					this.push(zc + this.flatKey[m], m);
				} else {
					this.zfill[m] = this.h[m];
					this.push(this.h[m], m);
				}
			}
		}
		// The flood gives every cell the lowest neighbour that reached it first, which on a smooth slope is the same one for every cell:
		// a plane that falls toward the south-west drains in perfectly straight, parallel diagonal lines. So a cell on a slope turns
		// instead to the neighbour with the best slope after a fixed random weighting of the eight directions (somewhere between 55% and
		// 145%), which makes the flow lines zigzag and merge the way water on rough ground does.
		for (int k = outlets; k < cnt; k++) {
			int m = this.order[k];
			if (this.flat[m]) {
				continue;
			}
			int mx = m % this.nx;
			int mz = m / this.nx;
			double zm = this.zfill[m];
			int best = this.receiver[m];
			double bestScore = -1.0;
			for (int o = 0; o < 8; o++) {
				int xx = mx + SCAN_X[o];
				int zz = mz + SCAN_Z[o];
				if (xx < 0 || zz < 0 || xx >= this.nx || zz >= this.nz) {
					continue;
				}
				int q = zz * this.nx + xx;
				double drop = zm - this.zfill[q];
				if (drop <= 2.0e-3) {
					continue; // not clearly lower: it may come after this cell in the order
				}
				double length = (SCAN_X[o] != 0 && SCAN_Z[o] != 0) ? this.dx * SQRT2 : this.dx;
				double score = drop / length * this.directionWeight[8 * m + o];
				if (score > bestScore) {
					bestScore = score;
					best = q;
				}
			}
			this.receiver[m] = best;
		}
		this.count = cnt;
		for (int i = 0; i < this.n; i++) {
			this.lake[i] = this.receiver[i] != i && this.zfill[i] > this.h[i] + 1.0e-3;
		}
	}

	private boolean touchesLand(int i) {
		int cx = i % this.nx;
		int cz = i / this.nx;
		for (int dz = -1; dz <= 1; dz++) {
			for (int ddx = -1; ddx <= 1; ddx++) {
				int xx = cx + ddx;
				int zz = cz + dz;
				if (xx >= 0 && zz >= 0 && xx < this.nx && zz < this.nz && !this.closed[zz * this.nx + xx]) {
					return true;
				}
			}
		}
		return false;
	}

	/** Drainage area, square metres, of every cell. */
	void accumulate() {
		float cellArea = (float) (this.dx * this.dx);
		for (int k = 0; k < this.count; k++) {
			int c = this.order[k];
			this.area[c] = this.receiver[c] == c ? 0.0f : cellArea;
		}
		for (int k = this.count - 1; k >= 0; k--) {
			int c = this.order[k];
			int r = this.receiver[c];
			if (r != c) {
				this.area[r] += this.area[c];
			}
		}
	}

	private double reach(int c, int r) {
		return (c % this.nx != r % this.nx && c / this.nx != r / this.nx) ? this.dx * SQRT2 : this.dx;
	}

	// ---- cutting, carrying, creeping -------------------------------------------------------------------------------------

	private void cut(double dt) {
		for (int k = 0; k < this.count; k++) {
			int c = this.order[k];
			int r = this.receiver[c];
			if (r == c) {
				this.hnew[c] = this.h[c];
				this.surface[c] = this.h[c];
			} else if (this.lake[c]) {
				this.hnew[c] = this.h[c];
				this.surface[c] = (float) this.zfill[c];
			} else {
				double f = K * this.land.erodibility[c] * this.lith[c] * dt * StrictMath.sqrt(this.area[c]) / this.reach(c, r);
				double v = (this.h[c] + f * this.surface[r]) / (1.0 + f);
				this.hnew[c] = (float) v;
				this.surface[c] = (float) v;
			}
		}
	}

	private void deposit(double dt) {
		Arrays.fill(this.sediment, 0.0f);
		double cellArea = this.dx * this.dx;
		for (int k = this.count - 1; k >= 0; k--) {
			int c = this.order[k];
			int r = this.receiver[c];
			if (r == c) {
				if (!this.edge[c] && this.sediment[c] > 0.0f) { // the sea takes what is left: deltas and shelves
					double room = Math.max(0.0, 3.0 - this.hnew[c]);
					this.hnew[c] += (float) Math.min(this.sediment[c] / cellArea, room);
				}
				continue;
			}
			double eroded = Math.max(0.0, this.h[c] - this.hnew[c]);
			double volume = eroded * cellArea + this.sediment[c];
			double slope = Math.max(0.0, (this.surface[c] - this.surface[r]) / this.reach(c, r));
			double capacity = this.lake[c] ? 0.0 : KAPPA * K * this.land.erodibility[c] * this.lith[c] * dt * StrictMath.sqrt(this.area[c]) * slope * cellArea;
			double dropped = Math.max(0.0, volume - capacity);
			if (dropped > 0.0) {
				double thickness = dropped / cellArea;
				if (this.lake[c]) {
					thickness = Math.min(thickness, this.zfill[c] - this.hnew[c]);
				} else {
					thickness = Math.min(thickness, Math.max(0.0, this.h[c] - this.hnew[c]));
				}
				thickness = Math.max(0.0, thickness);
				this.hnew[c] += (float) thickness;
				dropped = thickness * cellArea;
			}
			this.sediment[r] += (float) (volume - dropped);
		}
	}

	private void creep(double dt) {
		double alpha = DIFFUSION * dt / (this.dx * this.dx);
		int sub = Math.max(1, (int) Math.ceil(alpha * (1.0 + COAST_CREEP) / 0.2));
		alpha /= sub;
		for (int pass = 0; pass < sub; pass++) {
			System.arraycopy(this.hnew, 0, this.scratch, 0, this.n);
			for (int z = 1; z < this.nz - 1; z++) {
				for (int x = 1; x < this.nx - 1; x++) {
					int i = z * this.nx + x;
					if (this.hnew[i] < 0.0f || this.lake[i]) {
						continue;
					}
					float laplacian = this.hnew[i - 1] + this.hnew[i + 1] + this.hnew[i - this.nx] + this.hnew[i + this.nx] - 4.0f * this.hnew[i];
					this.scratch[i] = this.hnew[i] + (float) alpha * this.creepFactor[i] * this.creepLith[i] * laplacian;
				}
			}
			System.arraycopy(this.scratch, 0, this.hnew, 0, this.n);
		}
		// Sediment reaches a lake along single paths and fills them first, which leaves stripes. A lake bed settles sideways, evenly,
		// and it only ever moves toward the average of its own neighbours, so it can never rise above the water.
		for (int pass = 0; pass < 6; pass++) {
			System.arraycopy(this.hnew, 0, this.scratch, 0, this.n);
			for (int z = 1; z < this.nz - 1; z++) {
				for (int x = 1; x < this.nx - 1; x++) {
					int i = z * this.nx + x;
					if (!this.flat[i] || this.hnew[i] < 0.0f) {
						continue;
					}
					float sum = 0.0f;
					sum += this.flat[i - 1] && this.hnew[i - 1] >= 0.0f ? this.hnew[i - 1] - this.hnew[i] : 0.0f;
					sum += this.flat[i + 1] && this.hnew[i + 1] >= 0.0f ? this.hnew[i + 1] - this.hnew[i] : 0.0f;
					sum += this.flat[i - this.nx] && this.hnew[i - this.nx] >= 0.0f ? this.hnew[i - this.nx] - this.hnew[i] : 0.0f;
					sum += this.flat[i + this.nx] && this.hnew[i + this.nx] >= 0.0f ? this.hnew[i + this.nx] - this.hnew[i] : 0.0f;
					this.scratch[i] = this.hnew[i] + 0.2f * sum;
				}
			}
			System.arraycopy(this.scratch, 0, this.hnew, 0, this.n);
		}
	}
}
