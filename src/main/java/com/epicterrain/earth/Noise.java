package com.epicterrain.earth;

/**
 * Deterministic hashing and smooth noise. Only {@link StrictMath} is used for anything that is not exactly rounded, so the same seed gives
 * the same world on every machine.
 */
final class Noise {
	private Noise() {
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static long hash(long seed, long a, long b) {
		return mix(seed ^ mix(a * 0x9E3779B97F4A7C15L + mix(b + 0x632BE59BD9B4E019L)));
	}

	/** A number in [0, 1) from a hash. */
	static double unit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}

	static double unit(long seed, long a, long b, long salt) {
		return unit(hash(seed + salt * 0x2545F4914F6CDD1DL, a, b));
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
	}

	/** Smooth value noise in [0, 1). */
	static double value(long seed, double x, double z) {
		double fx = StrictMath.floor(x);
		double fz = StrictMath.floor(z);
		int ix = (int) fx;
		int iz = (int) fz;
		double tx = fade(x - fx);
		double tz = fade(z - fz);
		double a = unit(hash(seed, ix, iz));
		double b = unit(hash(seed, ix + 1, iz));
		double c = unit(hash(seed, ix, iz + 1));
		double d = unit(hash(seed, ix + 1, iz + 1));
		return (a * (1.0 - tx) + b * tx) * (1.0 - tz) + (c * (1.0 - tx) + d * tx) * tz;
	}

	/** Fractal noise in [-1, 1]. */
	static double fbm(long seed, double x, double z, int octaves) {
		double sum = 0.0;
		double amplitude = 1.0;
		double norm = 0.0;
		double frequency = 1.0;
		for (int o = 0; o < octaves; o++) {
			sum += amplitude * (2.0 * value(seed + o * 7919L, x * frequency + o * 17.3, z * frequency - o * 9.1) - 1.0);
			norm += amplitude;
			amplitude *= 0.5;
			frequency *= 2.0;
		}
		return sum / norm;
	}

	static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : Math.min(v, hi);
	}

	/** 0 below a, 1 above b, smooth between. */
	static double smooth(double a, double b, double x) {
		double t = clamp((x - a) / (b - a), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	/** A bell of unit height and spread {@code sigma}. */
	static double gauss(double d, double sigma) {
		double q = d / sigma;
		return StrictMath.exp(-0.5 * q * q);
	}
}
