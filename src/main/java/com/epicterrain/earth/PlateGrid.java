package com.epicterrain.earth;

/**
 * An infinite, seed-determined set of plates: a jittered grid of seed points whose Voronoi regions are the plates, with the edges wobbled
 * by a smooth warp so they are not straight. Any point can be looked up without looking at any other.
 */
final class PlateGrid {
	/** Result of a lookup; reuse one to avoid allocating per cell. */
	static final class Hit {
		int i;
		int j;
		int ni;
		int nj;
		/** Distance, in blocks, to the nearest plate boundary. */
		double distance;
		/** Distance to the second-nearest boundary: small near a corner where three plates meet. */
		double distance2;
		private final double[] sx = new double[25];
		private final double[] sz = new double[25];
		private final int[] si = new int[25];
		private final int[] sj = new int[25];
	}

	private final long seed;
	final double size;
	private final double jitter;

	PlateGrid(long seed, double size, double jitter) {
		this.seed = seed;
		this.size = size;
		this.jitter = jitter;
	}

	double seedX(int i, int j) {
		return (i + 0.5 + (Noise.unit(this.seed, i, j, 1) - 0.5) * this.jitter) * this.size;
	}

	double seedZ(int i, int j) {
		return (j + 0.5 + (Noise.unit(this.seed, i, j, 2) - 0.5) * this.jitter) * this.size;
	}

	private double warpX(double x, double z) {
		double s = this.size;
		return s * (0.10 * Noise.fbm(this.seed + 11, x / (0.9 * s), z / (0.9 * s), 2) + 0.03 * Noise.fbm(this.seed + 13, x / (0.25 * s), z / (0.25 * s), 2));
	}

	private double warpZ(double x, double z) {
		double s = this.size;
		return s * (0.10 * Noise.fbm(this.seed + 17, x / (0.9 * s), z / (0.9 * s), 2) + 0.03 * Noise.fbm(this.seed + 19, x / (0.25 * s), z / (0.25 * s), 2));
	}

	void lookup(double x, double z, Hit hit) {
		double qx = x + this.warpX(x, z);
		double qz = z + this.warpZ(x, z);
		int i0 = (int) StrictMath.floor(qx / this.size);
		int j0 = (int) StrictMath.floor(qz / this.size);
		int best = -1;
		double bestD = Double.MAX_VALUE;
		int k = 0;
		for (int di = -2; di <= 2; di++) {
			for (int dj = -2; dj <= 2; dj++, k++) {
				int i = i0 + di;
				int j = j0 + dj;
				double sx = this.seedX(i, j);
				double sz = this.seedZ(i, j);
				hit.si[k] = i;
				hit.sj[k] = j;
				hit.sx[k] = sx;
				hit.sz[k] = sz;
				double d = (qx - sx) * (qx - sx) + (qz - sz) * (qz - sz);
				if (d < bestD) {
					bestD = d;
					best = k;
				}
			}
		}
		// the boundary the point is nearest to is the bisector with the closest of the other seeds
		double nearest = Double.MAX_VALUE;
		double second = Double.MAX_VALUE;
		int neighbour = -1;
		for (k = 0; k < 25; k++) {
			if (k == best) {
				continue;
			}
			double dk = (qx - hit.sx[k]) * (qx - hit.sx[k]) + (qz - hit.sz[k]) * (qz - hit.sz[k]);
			double apart = StrictMath.sqrt((hit.sx[k] - hit.sx[best]) * (hit.sx[k] - hit.sx[best]) + (hit.sz[k] - hit.sz[best]) * (hit.sz[k] - hit.sz[best]));
			double bisector = (dk - bestD) / (2.0 * apart);
			if (bisector < nearest) {
				second = nearest;
				nearest = bisector;
				neighbour = k;
			} else if (bisector < second) {
				second = bisector;
			}
		}
		hit.i = hit.si[best];
		hit.j = hit.sj[best];
		hit.ni = hit.si[neighbour];
		hit.nj = hit.sj[neighbour];
		hit.distance = Math.max(0.0, nearest);
		hit.distance2 = Math.max(0.0, second);
	}
}
