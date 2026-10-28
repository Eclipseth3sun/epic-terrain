package com.epicterrain.river;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Gives a routed river the bends a real one has where it runs wide over gentle ground.
 *
 * <p>The router takes the cheapest way down, which over flat ground is a straight line. Real rivers there wander in bends whose
 * wavelength is a dozen or so river widths, and the lower and flatter the ground, the more they wander. So each point of the line is
 * pushed sideways by an alternating swing of random size whose wavelength follows the river's width and whose reach follows how gentle
 * the grade is; steep reaches keep the line the router found. A bend never climbs: where pushing a point sideways would put it on
 * rising ground the push is eased off, so the river stays in its valley. Both ends stay where they are.
 *
 * <p>Pure, and deterministic in its inputs. It only ever asks for the cheap ground estimate: bends act on gentle lowland, where that is
 * good, and both the grade and the climb are differences between nearby points, in which the estimate's constant bias cancels.
 */
public final class Meander {
	/** Wavelength is this many river widths, within the limits below. */
	static final double WAVE_PER_WIDTH = 18.0;
	static final double MIN_WAVE = 200.0;
	static final double MAX_WAVE = 3000.0;
	/** Peak sideways swing as a fraction of the wavelength. */
	static final double SWING = 0.34;
	/**
	 * A bend's radius at its tightest is at least this many river widths: a sine of wavelength L and swing s*L turns with radius
	 * L / (4 pi^2 s), so a river whose wavelength is short for its width (a very wide one, whose wavelength is capped) swings less.
	 */
	static final double MIN_RADIUS = 1.3;
	/** The furthest a bend can take the line from the one it was routed along, for whoever has to know how far a river can stray. */
	static final double MAX_REACH = SWING * MAX_WAVE;
	/**
	 * The push of the line (blocks sideways) changes by at most this much per block of line, wherever something holds it back. More
	 * than a bend's own steepest slope (2 pi times {@link #SWING}), or the bends themselves would be clipped.
	 */
	static final double MAX_SLOPE = 2.0;
	/** Passes of [1 2 1] averaging that round off the corners the limit leaves. */
	static final int ROUNDING = 3;
	/** A push toward the inside of a turn is at most this fraction of the turn's radius. */
	static final double LOOP_SAFE = 0.5;
	/** {@link #relax} averages over a spread of this many river widths, and leaves a river alone if that is under {@link #MIN_RELAX} blocks. */
	static final double RELAX = 1.2;
	static final double MIN_RELAX = 8.0;
	/** At or below this grade (blocks fallen per block run) a river winds fully; at or above {@link #STEEP} it keeps its line. */
	static final double GENTLE = 0.02;
	static final double STEEP = 0.15;
	/** A bend may stand this much (plus a quarter of the river's width) above the ground of the line it left. */
	static final double CLIMB = 4.0;
	/**
	 * The line is eased in over at least this far from the source, and out over at least this far to the mouth, which must stay where
	 * it is; and over {@link #RAMP} of a wavelength if that is longer, so the swing never has to be thrown sideways over a short distance.
	 */
	static final double HEAD = 96.0;
	static final double MOUTH = 240.0;
	static final double RAMP = 0.6;
	/** How many points either side the grade is measured over. */
	private static final int GRADE_WINDOW = 4;
	private static final double[] EASE = { 1.0, 0.6, 0.3, 0.0 };

	private Meander() {
	}

	/**
	 * @param line  the routed line, points about evenly spaced
	 * @param width the river's width at a distance travelled along {@code line}
	 */
	public static List<double[]> apply(List<double[]> line, DoubleUnaryOperator width, TerrainView terrain, long seed) {
		int n = line.size();
		if (n < 2 * GRADE_WINDOW + 1) {
			return line;
		}
		double[] x = new double[n];
		double[] z = new double[n];
		double[] s = new double[n];
		double[] ground = new double[n];
		for (int i = 0; i < n; i++) {
			x[i] = line.get(i)[0];
			z[i] = line.get(i)[1];
			if (i > 0) {
				s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
			}
			ground[i] = terrain.roughGround(x[i], z[i]);
		}
		double length = s[n - 1];

		double[] swing = new double[n];
		double[] nx = new double[n];
		double[] nz = new double[n];
		double[] ease = new double[n];
		double phase = 0.0;
		for (int i = 0; i < n; i++) {
			double w = width.applyAsDouble(s[i]);
			double wave = Math.max(MIN_WAVE, Math.min(MAX_WAVE, WAVE_PER_WIDTH * w));
			if (i > 0) {
				phase += (s[i] - s[i - 1]) / (wave / 2.0);
			}
			int lo = Math.max(0, i - GRADE_WINDOW);
			int hi = Math.min(n - 1, i + GRADE_WINDOW);
			double grade = Math.max(0.0, ground[lo] - ground[hi]) / Math.max(1.0, s[hi] - s[lo]);
			double fraction = Math.min(SWING, wave / (4.0 * Math.PI * Math.PI * MIN_RADIUS * Math.max(w, 1.0)));
			double amplitude = fraction * wave * gentleness(grade) * ends(s[i], length, wave);

			int a = Math.max(0, i - 1);
			int b = Math.min(n - 1, i + 1);
			double tx = x[b] - x[a];
			double tz = z[b] - z[a];
			double t = Math.hypot(tx, tz);
			nx[i] = t > 0 ? -tz / t : 0.0;
			nz[i] = t > 0 ? tx / t : 0.0;

			int k = (int) Math.floor(phase);
			double u = phase - k;
			double between = (1.0 - Math.cos(Math.PI * u)) / 2.0;
			swing[i] = amplitude * (knot(seed, k) * (1.0 - between) + knot(seed, k + 1) * between);

			// ease the push off where it would climb, or fold the line over itself
			ease[i] = 1.0;
			if (Math.abs(swing[i]) >= 0.5) {
				ease[i] = 0.0;
				for (double e : EASE) {
					double groundThere = terrain.roughGround(x[i] + nx[i] * swing[i] * e, z[i] + nz[i] * swing[i] * e);
					if (groundThere <= ground[i] + CLIMB + 0.25 * w) {
						ease[i] = e;
						break;
					}
				}
				ease[i] = Math.min(ease[i], loopEase(x, z, i, swing[i], length / (n - 1)));
			}
		}
		// The push a node may take (its swing as eased) can only change so fast along the line, or the line zigzags where the ground, or
		// the grade, or the turn of the line changes from one node to the next: the allowance falls to meet a node's limit gradually.
		double spacing = length / (n - 1);
		double[] allowed = new double[n];
		for (int i = 0; i < n; i++) {
			allowed[i] = Math.abs(swing[i]) * ease[i];
		}
		for (int i = 1; i < n; i++) {
			allowed[i] = Math.min(allowed[i], allowed[i - 1] + MAX_SLOPE * spacing);
		}
		for (int i = n - 2; i >= 0; i--) {
			allowed[i] = Math.min(allowed[i], allowed[i + 1] + MAX_SLOPE * spacing);
		}

		// The limit has corners where it starts to bite. They are rounded off, never past what a node is allowed, and only near the
		// nodes that were held back: a bend running free keeps its shape exactly.
		double[] push = new double[n];
		boolean[] held = new boolean[n];
		for (int i = 0; i < n; i++) {
			push[i] = Math.signum(swing[i]) * allowed[i];
			if (allowed[i] < Math.abs(swing[i]) - 0.5) {
				for (int j = Math.max(0, i - ROUNDING - 1); j <= Math.min(n - 1, i + ROUNDING + 1); j++) {
					held[j] = true;
				}
			}
		}
		for (int pass = 0; pass < ROUNDING; pass++) {
			double[] rounded = push.clone();
			for (int i = 1; i < n - 1; i++) {
				if (held[i]) {
					double d = 0.25 * push[i - 1] + 0.5 * push[i] + 0.25 * push[i + 1];
					rounded[i] = Math.signum(d) * Math.min(Math.abs(d), allowed[i]);
				}
			}
			push = rounded;
		}

		List<double[]> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			out.add(new double[] { x[i] + nx[i] * push[i], z[i] + nz[i] * push[i] });
		}
		return out;
	}

	/**
	 * How much of a sideways push a node can take without the line folding over itself. Pushing a curved line toward the inside of its
	 * turn by more than the turn's radius turns it inside out (the classic trouble with offset curves), so the push toward the inside is
	 * held to {@link #LOOP_SAFE} of the radius, judged at every scale up to the push itself.
	 */
	private static double loopEase(double[] x, double[] z, int i, double push, double spacing) {
		double inward = 0.0;
		int widest = Math.max(3, (int) Math.ceil(Math.abs(push) / spacing) + 2);
		for (int m = 3; m <= widest && m <= 48; m *= 2) {
			if (i - m < 0 || i + m > x.length - 1) {
				break;
			}
			double dx1 = x[i] - x[i - m];
			double dz1 = z[i] - z[i - m];
			double dx2 = x[i + m] - x[i];
			double dz2 = z[i + m] - z[i];
			double chords = Math.hypot(dx1, dz1) * Math.hypot(dx2, dz2) * Math.hypot(x[i + m] - x[i - m], z[i + m] - z[i - m]);
			if (chords > 0.0) {
				// 1 / radius, positive when the line turns toward its left normal
				inward = Math.max(inward, 2.0 * (dx1 * dz2 - dz1 * dx2) / chords * Math.signum(push));
			}
		}
		return inward > 0.0 ? Math.min(1.0, LOOP_SAFE / (Math.abs(push) * inward)) : 1.0;
	}

	/**
	 * Smooths a routed line to the scale of the river: a Gaussian average along the line whose spread is {@link #RELAX} river widths,
	 * so a wide river cannot turn tighter than it is wide. The router's grid steps and corners are tens of blocks across, which for a
	 * river a hundred blocks wide would fold the channel over itself. The window is symmetric and shrinks to nothing at the two ends,
	 * which stay where they are, so the smoothing neither drifts nor pulls the ends in.
	 */
	public static List<double[]> relax(List<double[]> line, DoubleUnaryOperator width) {
		int n = line.size();
		if (n < 3) {
			return line;
		}
		double[] s = new double[n];
		for (int i = 1; i < n; i++) {
			s[i] = s[i - 1] + Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
		}
		List<double[]> out = new ArrayList<>(n);
		out.add(line.get(0));
		for (int i = 1; i < n - 1; i++) {
			double sigma = RELAX * width.applyAsDouble(s[i]);
			double reach = Math.min(2.5 * sigma, Math.min(s[i], s[n - 1] - s[i]));
			if (sigma < MIN_RELAX || reach <= 0.0) {
				out.add(line.get(i));
				continue;
			}
			double sx = 0.0;
			double sz = 0.0;
			double total = 0.0;
			for (int j = i; j >= 0 && s[i] - s[j] <= reach; j--) {
				double w = Math.exp(-0.5 * square((s[i] - s[j]) / sigma));
				sx += w * line.get(j)[0];
				sz += w * line.get(j)[1];
				total += w;
			}
			for (int j = i + 1; j < n && s[j] - s[i] <= reach; j++) {
				double w = Math.exp(-0.5 * square((s[j] - s[i]) / sigma));
				sx += w * line.get(j)[0];
				sz += w * line.get(j)[1];
				total += w;
			}
			out.add(new double[] { sx / total, sz / total });
		}
		out.add(line.get(n - 1));
		return out;
	}

	private static double square(double v) {
		return v * v;
	}

	/** 0 on steep ground, 1 on gentle ground, smooth between. */
	static double gentleness(double grade) {
		double t = Math.max(0.0, Math.min(1.0, (STEEP - grade) / (STEEP - GENTLE)));
		return t * t * (3.0 - 2.0 * t);
	}

	/** 0 at the source and the mouth, 1 between, easing in over {@link #HEAD} and out over {@link #MOUTH}, or {@link #RAMP} of a wavelength if longer. */
	static double ends(double travelled, double length, double wave) {
		return smooth(travelled / Math.max(HEAD, RAMP * wave)) * smooth((length - travelled) / Math.max(MOUTH, RAMP * wave));
	}

	private static double smooth(double t) {
		double c = Math.max(0.0, Math.min(1.0, t));
		return c * c * (3.0 - 2.0 * c);
	}

	/** The swing at a bend's peak: alternates side from bend to bend, and varies in size from 65% to 100% of the greatest swing. */
	private static double knot(long seed, int k) {
		double size = 0.65 + 0.35 * unit(mix(seed + (long) k * 0x9E3779B97F4A7C15L));
		return (Math.floorMod(k, 2) == 0 ? 1.0 : -1.0) * size;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static double unit(long hash) {
		return (hash >>> 11) * 0x1.0p-53;
	}
}
