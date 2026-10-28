package com.epicterrain.earth;

/**
 * The rock a landscape is cut from: a column of beds, hard and soft, one above another. The simulation tracks how high in this column the
 * surface of each cell stands (rock rises with the uplift, so only erosion and deposition change it), and the bed at the surface decides
 * how fast water cuts and soil creeps there: a hard bed stands as a cliff, a bench or the cap of a mesa, a soft one wears away into
 * slopes and badlands. The beds are laterally continuous, tilted and folded (see {@link Landscape}), so the same bed outcrops again and
 * again along a hillside and in a fold as parallel ridges and valleys.
 *
 * <p>Pure, and a function of height in the column only.
 */
final class Strata {
	/** The average thickness of a bed, in metres: 6 to 7 blocks of height. */
	static final double SPACING = 70.0;
	/** A bed's boundary wanders this far (a share of the spacing) from its even position, so the beds are not all alike. */
	static final double JITTER = 0.3;
	/** How fast each kind of bed erodes, compared with an average one: hard, firm, medium and soft. Their average, weighted by chance, is about 1. */
	static final double[] LEVELS = { 0.15, 0.45, 1.0, 2.5 };
	private static final double[] CUMULATIVE = { 0.28, 0.50, 0.75, 1.0 };
	private static final int LOW = -1500;
	private static final int HIGH = 1500;

	private final double[] edge = new double[HIGH - LOW + 2];
	private final double[] ln = new double[HIGH - LOW + 1];

	Strata(long seed) {
		for (int j = LOW; j <= HIGH + 1; j++) {
			this.edge[j - LOW] = (j + JITTER * (2.0 * Noise.unit(seed, j, 0, 601) - 1.0)) * SPACING;
		}
		for (int j = LOW; j <= HIGH; j++) {
			double u = Noise.unit(seed, j, 0, 603);
			int kind = 0;
			while (u > CUMULATIVE[kind]) {
				kind++;
			}
			this.ln[j - LOW] = Math.log(LEVELS[kind]);
		}
	}

	/** Index of the bed that a height in the column is in. */
	private int bed(double s) {
		int j = (int) Math.floor(s / SPACING);
		j = Math.max(LOW + 1, Math.min(HIGH - 1, j));
		for (int k = j - 1; k <= j + 1; k++) {
			if (s >= this.edge[k - LOW] && s < this.edge[k - LOW + 1]) {
				return k;
			}
		}
		return j;
	}

	/** The natural log of how fast the bed at this height erodes, relative to an average bed. */
	double ln(double s) {
		return this.ln[this.bed(s) - LOW];
	}

	/** How fast the bed at this height erodes, relative to an average bed. */
	double hardness(double s) {
		return Math.exp(this.ln(s));
	}
}
