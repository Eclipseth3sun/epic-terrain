package com.epicterrain.earth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.epicterrain.earth.Rivers.River;

/**
 * Rivers as part of the ground, not cut into it afterwards.
 *
 * <p>The original model laid a channel over ground that knew nothing of the river (a trench with walls at a fixed slope, a lip along the
 * bank, a bed floor, water put in by a separate feature) and it looked cut-and-pasted. Here the river is one more term in the height
 * function, and the water is whatever lies below the river's own level, so the two cannot disagree:
 * <ul>
 *   <li><b>A water profile first.</b> Each river gets a water level along its length that only ever falls, sits a little under the ground
 *       at both its banks (never perched), is smoothed so it does not stair-step with every bump, drops no faster than a river of that
 *       size can (the ground ahead of a steep reach is cut into a gorge, up to a limit, instead of the water cascading over a ledge),
 *       ends at the level of whatever it runs into (the sea, a lake, the bigger river it joins) and starts from a shallow head.</li>
 *   <li><b>A cross-section that follows from it.</b> A bed under the water, deeper toward the outside of a bend (and the bank there
 *       steeper) and shallower, with a gentler bank, on the inside (a point bar); a bank that rises to just above the water; and, where
 *       the ground is gentle, a flat floodplain beside it that the terrain is eased toward, widest for the widest rivers. Where the
 *       ground is steep the floodplain narrows to nothing and the river keeps whatever valley the ground already had.</li>
 *   <li><b>Soft edges.</b> The floodplain eases the ground toward its own level with a falloff, the bank and bed are smooth curves, the
 *       banks only cut (the ground is never built up more than a few blocks), and everything is zero beyond the river's reach.</li>
 *   <li><b>Water as a level, not a feature.</b> A column is water where the ground is below the river's level there (or a lake's); rivers
 *       and lakes run into one another at the same level because the profile ends where the next level begins.</li>
 * </ul>
 * Pure, and a function of position only. All heights are blocks above sea level.
 */
public final class RiverValley {
	/** @param depth multiplies the depth of every river; @param floodplain multiplies the width of every floodplain */
	public record Dials(double depth, double floodplain) {
		public static Dials defaults() {
			return new Dials(1.0, 1.0);
		}

		public static Dials fromProperties() {
			String depth = System.getProperty("epicterrain.earth.riverDepth");
			String floodplain = System.getProperty("epicterrain.earth.floodplain");
			return new Dials(depth == null ? 1.0 : Double.parseDouble(depth), floodplain == null ? 1.0 : Double.parseDouble(floodplain));
		}
	}

	/** The banks stand this far above the water. */
	static final double FREEBOARD = 1.2;
	/** The floodplain builds the ground up by at most this much, and cuts it by at most {@link #MAX_PULL_CUT}. */
	static final double MAX_RAISE = 3.0;
	static final double MAX_PULL_CUT = 14.0;
	/** The water profile is held no more than this far under the ground at its banks; a steeper fall is a cascade. */
	static final double MAX_GORGE = 14.0;
	/** The steepest fall of the water (blocks per block) for a river 0 blocks wide; wider rivers fall more gently (3 wide: 0.36, 70 wide: 0.05). */
	static final double FALL = 0.5;
	/** At the edge of the lake cells the ground has to be this far under a lake's surface to be flooded. */
	static final double LAKE_EDGE = 2.5;
	/** Side of the squares of the grid the river segments are kept in, in blocks. */
	static final double GRID = 128.0;

	/** What a river is, node by node. */
	static final class Reach {
		final River river;
		final double[] x;
		final double[] z;
		final double[] water;
		final double[] depth;
		final double[] half;
		final double[] asym;
		final double[] bank;
		final double[] fp;
		final double[] reach;

		Reach(River river) {
			int n = river.line.size();
			this.river = river;
			this.x = new double[n];
			this.z = new double[n];
			this.water = new double[n];
			this.depth = new double[n];
			this.half = new double[n];
			this.asym = new double[n];
			this.bank = new double[n];
			this.fp = new double[n];
			this.reach = new double[n];
			for (int k = 0; k < n; k++) {
				this.x[k] = river.line.get(k)[0];
				this.z[k] = river.line.get(k)[1];
				this.half[k] = river.width[k] / 2.0;
			}
		}
	}

	private final Detail detail;
	private final Continent continent;
	private final Dials dials;
	private final Reach[] reaches;
	private final float[] lakeSurface;
	private final float[] lakeMask;
	private final float[] lakeWeighted;
	private final double minX;
	private final double minZ;
	private final int gx;
	private final int gz;
	private final int[] start;
	private final int[] segments;
	private final int[] segRiver;
	private final int[] segNode;

	public RiverValley(Continent continent, List<River> rivers, Detail detail, Dials dials) {
		this.continent = continent;
		this.detail = detail;
		this.dials = dials;
		int n = continent.nx * continent.nz;
		this.lakeSurface = new float[n];
		this.lakeMask = new float[n];
		this.lakeWeighted = new float[n];
		for (int i = 0; i < n; i++) {
			this.lakeSurface[i] = continent.lakeDepth[i] > 0.0f ? (float) Continent.blocksAbove(continent.height[i] + continent.lakeDepth[i]) : Float.NaN;
			this.lakeMask[i] = continent.lakeDepth[i] > 0.0f ? 1.0f : 0.0f;
			this.lakeWeighted[i] = continent.lakeDepth[i] > 0.0f ? this.lakeSurface[i] : 0.0f;
		}
		// blurred twice, so the shore is a rounded curve and not the staircase of the cells
		for (int pass = 0; pass < 2; pass++) {
			this.blur(this.lakeMask);
			this.blur(this.lakeWeighted);
		}

		this.reaches = new Reach[rivers.size()];
		boolean[] done = new boolean[rivers.size()];
		for (River r : rivers) {
			this.profile(r, rivers, done);
		}

		// a grid of the segments, each in every square its reach touches
		double lo = Double.MAX_VALUE;
		double loZ = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		double hiZ = -Double.MAX_VALUE;
		int total = 0;
		for (Reach r : this.reaches) {
			for (int k = 0; k < r.x.length; k++) {
				lo = Math.min(lo, r.x[k] - r.reach[k]);
				hi = Math.max(hi, r.x[k] + r.reach[k]);
				loZ = Math.min(loZ, r.z[k] - r.reach[k]);
				hiZ = Math.max(hiZ, r.z[k] + r.reach[k]);
			}
			total += Math.max(0, r.x.length - 1);
		}
		this.minX = lo;
		this.minZ = loZ;
		this.gx = Math.max(1, (int) Math.ceil((hi - lo) / GRID) + 1);
		this.gz = Math.max(1, (int) Math.ceil((hiZ - loZ) / GRID) + 1);
		this.segRiver = new int[total];
		this.segNode = new int[total];
		int[] counts = new int[this.gx * this.gz];
		int[] placed = null;
		int[] listed = null;
		int[] first = new int[this.gx * this.gz + 1];
		for (int pass = 0; pass < 2; pass++) {
			int seg = 0;
			for (int ri = 0; ri < this.reaches.length; ri++) {
				Reach r = this.reaches[ri];
				for (int k = 0; k < r.x.length - 1; k++, seg++) {
					double reach = Math.max(r.reach[k], r.reach[k + 1]);
					int x0 = this.cellX(Math.min(r.x[k], r.x[k + 1]) - reach);
					int x1 = this.cellX(Math.max(r.x[k], r.x[k + 1]) + reach);
					int z0 = this.cellZ(Math.min(r.z[k], r.z[k + 1]) - reach);
					int z1 = this.cellZ(Math.max(r.z[k], r.z[k + 1]) + reach);
					for (int cz = z0; cz <= z1; cz++) {
						for (int cx = x0; cx <= x1; cx++) {
							int cell = cz * this.gx + cx;
							if (pass == 0) {
								counts[cell]++;
							} else {
								listed[placed[cell]++] = seg;
							}
						}
					}
					this.segRiver[seg] = ri;
					this.segNode[seg] = k;
				}
			}
			if (pass == 0) {
				for (int i = 0; i < counts.length; i++) {
					first[i + 1] = first[i] + counts[i];
				}
				listed = new int[first[counts.length]];
				placed = Arrays.copyOf(first, counts.length);
			}
		}
		this.start = first;
		this.segments = listed;
	}

	private int cellX(double x) {
		return Math.max(0, Math.min(this.gx - 1, (int) Math.floor((x - this.minX) / GRID)));
	}

	private int cellZ(double z) {
		return Math.max(0, Math.min(this.gz - 1, (int) Math.floor((z - this.minZ) / GRID)));
	}

	/** The water level, depth, width and so on of a river, node by node; a bigger river first, so a tributary can end at its level. */
	private void profile(River river, List<River> rivers, boolean[] done) {
		if (done[river.index]) {
			return;
		}
		if (river.joins >= 0) {
			this.profile(rivers.get(river.joins), rivers, done);
		}
		done[river.index] = true;
		Reach r = new Reach(river);
		this.reaches[river.index] = r;
		int n = r.x.length;
		double spacing = Rivers.SPACING;

		// the bed the simulation settled on along the cells it routed this river over: it falls all the way down by construction, and
		// it is the valley bottom, so the water stands a little under it. (Reading the ground at the nodes instead lets every dip near the
		// source set the level for the whole river, a gorge twelve kilometres long.)
		int m = river.cells.length;
		double[] chain = new double[m];
		double[] along = new double[m];
		for (int j = 0; j < m; j++) {
			int cell = river.cells[j];
			chain[j] = Math.max(0.0, Continent.blocksAbove(this.continent.height[cell])) - FREEBOARD;
			if (j > 0) {
				int before = river.cells[j - 1];
				along[j] = along[j - 1] + this.continent.cell * Math.hypot((cell % this.continent.nx) - (before % this.continent.nx), (cell / this.continent.nx) - (before / this.continent.nx));
			}
		}
		int lastCell = river.cells[m - 1];
		if (!this.continent.river[lastCell] && this.continent.lakeDepth[lastCell] > 0.0f) {
			chain[m - 1] = this.lakeSurface[lastCell];
		}
		// a river that leaves a lake starts at the lake's level, not a bank's height under it, or the water would step at the shore
		int first = river.cells[0];
		boolean outlet = false;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int nx = first % this.continent.nx + dx;
				int nz = first / this.continent.nx + dz;
				if (nx < 0 || nz < 0 || nx >= this.continent.nx || nz >= this.continent.nz) {
					continue;
				}
				int neighbour = nz * this.continent.nx + nx;
				if (this.continent.lakeDepth[neighbour] > 0.0f && this.continent.receiver[neighbour] == first) {
					chain[0] = Math.max(chain[0], this.lakeSurface[neighbour]);
					outlet = true;
				}
			}
		}
		for (int j = 1; j < m; j++) {
			chain[j] = Math.min(chain[j], chain[j - 1]);
		}
		double[] level = new double[n];
		double[] slope = new double[n];
		double travelled = 0.0;
		double total = river.length;
		for (int k = 0; k < n; k++) {
			if (k > 0) {
				travelled += Math.hypot(r.x[k] - r.x[k - 1], r.z[k] - r.z[k - 1]);
			}
			double target = Math.max(0.0, Math.min(1.0, travelled / Math.max(1.0e-9, total))) * along[m - 1];
			int j = 1;
			while (j < m - 1 && along[j] < target) {
				j++;
			}
			double span = along[j] - along[j - 1];
			double t = span > 0.0 ? Math.max(0.0, Math.min(1.0, (target - along[j - 1]) / span)) : 0.0;
			level[k] = lerp(chain[j - 1], chain[j], t);

			int a2 = Math.max(0, k - 1);
			int b2 = Math.min(n - 1, k + 1);
			double tx = r.x[b2] - r.x[a2];
			double tz = r.z[b2] - r.z[a2];
			double len = Math.hypot(tx, tz);
			double nx = len > 0 ? -tz / len : 0.0;
			double nz = len > 0 ? tx / len : 0.0;
			double bank = Math.max(1.5, 0.18 * river.width[k]);
			r.bank[k] = bank;
			double far = r.half[k] + bank + 25.0;
			double centre = this.detail.smoothBlocks(r.x[k], r.z[k]);
			double lf = this.detail.smoothBlocks(r.x[k] + nx * far, r.z[k] + nz * far);
			double rf = this.detail.smoothBlocks(r.x[k] - nx * far, r.z[k] - nz * far);
			slope[k] = 0.5 * (Math.max(0.0, lf - centre) + Math.max(0.0, rf - centre)) / far;
		}
		// smoothed over about a river's width, so the piecewise-linear bed between cells does not show
		double[] smoothed = new double[n];
		for (int k = 0; k < n; k++) {
			int radius = (int) Math.max(1, Math.min(40, Math.round((river.width[k] + 24.0) / spacing)));
			int lo = Math.max(0, k - radius);
			int hi = Math.min(n - 1, k + radius);
			int use = Math.min(k - lo, hi - k);
			double sum = 0.0;
			for (int j = k - use; j <= k + use; j++) {
				sum += level[j];
			}
			smoothed[k] = sum / (2 * use + 1);
		}
		double running = Double.MAX_VALUE;
		for (int k = 0; k < n; k++) {
			running = Math.min(running, smoothed[k]);
			level[k] = running;
		}
		// it ends at the level of what it runs into: the sea, a lake, the bigger river
		double end = 0.0;
		if (river.joins >= 0) {
			Reach host = this.reaches[river.joins];
			double nearest = Double.MAX_VALUE;
			for (int j = 0; j < host.x.length; j++) {
				double d = Math.hypot(host.x[j] - r.x[n - 1], host.z[j] - r.z[n - 1]);
				if (d < nearest) {
					nearest = d;
					end = host.water[j];
				}
			}
		} else if (!this.continent.river[lastCell] && this.continent.lakeDepth[lastCell] > 0.0f) {
			end = this.lakeSurface[lastCell];
		}
		// a river that arrives above what it runs into comes down to it at the last node, and the fall limit spreads the drop upstream
		level[n - 1] = Math.min(level[n - 1], end);
		// a river falls only so fast: the ground ahead of a very steep reach is cut into a gorge (up to a limit) instead
		double[] bed = level.clone();
		for (int k = n - 2; k >= 0; k--) {
			double fall = FALL / (1.0 + river.width[k] / 8.0);
			level[k] = Math.min(level[k], level[k + 1] + fall * spacing);
		}
		running = Double.MAX_VALUE;
		for (int k = 0; k < n; k++) {
			level[k] = Math.max(level[k], bed[k] - MAX_GORGE);
			running = Math.min(running, level[k]);
			level[k] = running;
		}
		for (int k = 0; k < n; k++) {
			r.water[k] = Math.max(0.0, Math.max(level[k], end));
		}
		// the head is a shallow trickle that deepens over its first stretch
		travelled = 0.0;
		for (int k = 0; k < n; k++) {
			if (k > 0) {
				travelled += Math.hypot(r.x[k] - r.x[k - 1], r.z[k] - r.z[k - 1]);
			}
			// (a river that leaves a lake has the lake's whole depth behind it: no trickle at its start)
			double head = outlet ? 1.0 : 0.35 + 0.65 * Noise.smooth(0.0, 4.0 * river.width[k] + 60.0, travelled);
			r.depth[k] = Math.min(8.0, 1.6 + 0.1 * river.width[k]) * this.dials.depth() * head;
			double gentle = 1.0 - Noise.smooth(0.015, 0.10, slope[k]);
			r.fp[k] = this.dials.floodplain() * r.half[k] * (0.4 + 3.0 * gentle);
		}
		// outside of a bend the bed is deeper and the bank steeper, inside it shallower and gentler
		double[] curve = new double[n];
		for (int k = 1; k < n - 1; k++) {
			int baseline = Math.max(1, (int) Math.round(river.width[k] / spacing));
			if (k - baseline < 0 || k + baseline > n - 1) {
				continue;
			}
			double d1x = r.x[k] - r.x[k - baseline];
			double d1z = r.z[k] - r.z[k - baseline];
			double d2x = r.x[k + baseline] - r.x[k];
			double d2z = r.z[k + baseline] - r.z[k];
			double chords = Math.hypot(d1x, d1z) * Math.hypot(d2x, d2z) * Math.hypot(r.x[k + baseline] - r.x[k - baseline], r.z[k + baseline] - r.z[k - baseline]);
			curve[k] = chords > 0.0 ? 2.0 * (d1x * d2z - d1z * d2x) / chords * 0.9 * river.width[k] : 0.0;
		}
		for (int pass = 0; pass < 3; pass++) {
			double[] c2 = curve.clone();
			double[] f2 = r.fp.clone();
			for (int k = 1; k < n - 1; k++) {
				c2[k] = 0.25 * curve[k - 1] + 0.5 * curve[k] + 0.25 * curve[k + 1];
				f2[k] = 0.25 * r.fp[k - 1] + 0.5 * r.fp[k] + 0.25 * r.fp[k + 1];
			}
			curve = c2;
			System.arraycopy(f2, 0, r.fp, 0, n);
		}
		for (int k = 0; k < n; k++) {
			r.asym[k] = Math.max(-0.5, Math.min(0.5, curve[k]));
			r.reach[k] = r.half[k] + 1.8 * r.bank[k] + r.fp[k] + 4.0;
		}
	}

	Reach reach(int river) {
		return this.reaches[river];
	}

	/** The water level of a river at a node, in blocks above sea level. */
	public double waterAt(int river, int node) {
		return this.reaches[river].water[node];
	}

	public double depthAt(int river, int node) {
		return this.reaches[river].depth[node];
	}

	public double floodplainAt(int river, int node) {
		return this.reaches[river].fp[node];
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double smooth01(double t) {
		double c = Math.max(0.0, Math.min(1.0, t));
		return c * c * (3.0 - 2.0 * c);
	}

	/**
	 * The ground and the water at a column. {@code out[0]} is the height of the ground (the {@link Detail} ground with the valleys
	 * worked into it) and {@code out[1]} the level of the water standing over it, or NaN if there is none from a river or a lake; if the array has a
	 * third place it is told whose water it is (0 none, 1 a river's, 2 a lake's).
	 */
	public void column(double wx, double wz, double[] out) {
		double g = this.detail.blocks(wx, wz);
		double pull = 0.0;
		double weights = 0.0;
		double strongest = 0.0;
		double surface = Double.MAX_VALUE;
		double level = Double.NaN;
		double across = Double.MAX_VALUE;
		double fx = (wx - this.minX) / GRID;
		double fz = (wz - this.minZ) / GRID;
		if (fx >= 0.0 && fz >= 0.0 && fx < this.gx && fz < this.gz) {
			int cell = (int) fz * this.gx + (int) fx;
			for (int s = this.start[cell]; s < this.start[cell + 1]; s++) {
				int seg = this.segments[s];
				Reach r = this.reaches[this.segRiver[seg]];
				int k = this.segNode[seg];
				double ax = r.x[k];
				double az = r.z[k];
				double dx = r.x[k + 1] - ax;
				double dz = r.z[k + 1] - az;
				double len2 = dx * dx + dz * dz;
				double t = len2 > 0.0 ? Math.max(0.0, Math.min(1.0, ((wx - ax) * dx + (wz - az) * dz) / len2)) : 0.0;
				double px = wx - (ax + t * dx);
				double pz = wz - (az + t * dz);
				double dist = Math.hypot(px, pz);
				if (dist > lerp(r.reach[k], r.reach[k + 1], t)) {
					continue;
				}
				double half = lerp(r.half[k], r.half[k + 1], t);
				double wl = lerp(r.water[k], r.water[k + 1], t);
				double a = lerp(r.asym[k], r.asym[k + 1], t);
				double side = dx * pz - dz * px >= 0.0 ? 1.0 : -1.0;
				double u = side * dist / half;
				if (dist <= half) {
					double f = Math.max(0.0, Math.min(1.6, Math.pow(Math.max(0.0, 1.0 - u * u), 0.8) * (1.0 - a * u)));
					surface = Math.min(surface, wl - lerp(r.depth[k], r.depth[k + 1], t) * f);
					if (Math.abs(u) < across) {
						across = Math.abs(u);
						level = wl;
					}
					pull += clampPull(wl + FREEBOARD - g);
					weights += 1.0;
					strongest = 1.0;
					continue;
				}
				double bank = lerp(r.bank[k], r.bank[k + 1], t);
				boolean outer = side * a < 0.0;
				bank *= outer ? 1.0 - Math.min(0.6, 1.2 * Math.abs(a)) : 1.0 + 1.2 * Math.abs(a);
				double e = dist - half;
				if (e < bank) {
					surface = Math.min(surface, wl + FREEBOARD * smooth01(e / bank));
					if (1.0 + e / half < across) {
						across = 1.0 + e / half;
						level = wl;
					}
				}
				double fp = lerp(r.fp[k], r.fp[k + 1], t);
				double weight = e <= bank ? 1.0 : (fp > 0.0 ? 1.0 - smooth01((e - bank - 0.15 * fp) / (0.85 * fp)) : 0.0);
				if (weight > 0.0) {
					pull += weight * clampPull(wl + FREEBOARD - g);
					weights += weight;
					strongest = Math.max(strongest, weight);
				}
			}
		}
		double h = weights > 0.0 ? g + strongest * (pull / weights) : g;
		h = Math.min(h, surface);
		double water = !Double.isNaN(level) && h < level ? level : Double.NaN;
		int source = Double.isNaN(water) ? 0 : 1;

		// a lake: where the lake cells (smoothed between cells) cover the column and the ground is under the lake's surface
		double surfaceHere = this.lakeSurfaceAt(wx, wz);
		if (!Double.isNaN(surfaceHere)) {
			// the ground must be further under the surface the nearer the edge of the lake cells: the shore follows both
			double inside = Math.min(1.0, this.lakeness(wx, wz) / 0.85);
			if (h < surfaceHere - LAKE_EDGE * (1.0 - inside) && (Double.isNaN(water) || surfaceHere > water)) {
				water = surfaceHere;
				source = 2;
			}
		}
		out[0] = h;
		out[1] = water;
		if (out.length > 2) {
			out[2] = source; // 0 no water, 1 a river's, 2 a lake's
		}
	}

	/** A [1 2 1] x [1 2 1] average over the cells, in place. */
	private void blur(float[] field) {
		int nx = this.continent.nx;
		int nz = this.continent.nz;
		float[] copy = field.clone();
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				float sum = 0.0f;
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						int cx = Math.max(0, Math.min(nx - 1, x + dx));
						int cz = Math.max(0, Math.min(nz - 1, z + dz));
						sum += copy[cz * nx + cx] * (dx == 0 ? 2.0f : 1.0f) * (dz == 0 ? 2.0f : 1.0f);
					}
				}
				field[z * nx + x] = sum / 16.0f;
			}
		}
	}

	/** The surface of the lake over a position, or NaN: the lake cells blurred between cells, a lake where more than half of it is lake. */
	private double lakeSurfaceAt(double wx, double wz) {
		double gxc = (wx - this.continent.x0) / this.continent.cell;
		double gzc = (wz - this.continent.z0) / this.continent.cell;
		int x = (int) Math.floor(gxc);
		int z = (int) Math.floor(gzc);
		double tx = gxc - x;
		double tz = gzc - z;
		double mask = 0.0;
		double weighted = 0.0;
		for (int dz = 0; dz <= 1; dz++) {
			for (int dx = 0; dx <= 1; dx++) {
				int cx = x + dx;
				int cz = z + dz;
				if (cx < 0 || cz < 0 || cx >= this.continent.nx || cz >= this.continent.nz) {
					continue;
				}
				double w = (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz);
				mask += w * this.lakeMask[cz * this.continent.nx + cx];
				weighted += w * this.lakeWeighted[cz * this.continent.nx + cx];
			}
		}
		return mask > 0.05 ? weighted / mask : Double.NaN;
	}

	/** 1 well inside a lake, falling to 0 at its edge: the blurred lake cells over a position. */
	private double lakeness(double wx, double wz) {
		double gxc = (wx - this.continent.x0) / this.continent.cell;
		double gzc = (wz - this.continent.z0) / this.continent.cell;
		int x = (int) Math.floor(gxc);
		int z = (int) Math.floor(gzc);
		double tx = gxc - x;
		double tz = gzc - z;
		double mask = 0.0;
		for (int dz = 0; dz <= 1; dz++) {
			for (int dx = 0; dx <= 1; dx++) {
				int cx = x + dx;
				int cz = z + dz;
				if (cx >= 0 && cz >= 0 && cx < this.continent.nx && cz < this.continent.nz) {
					mask += (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz) * this.lakeMask[cz * this.continent.nx + cx];
				}
			}
		}
		return mask;
	}

	private static double clampPull(double wanted) {
		return Math.max(-MAX_PULL_CUT, Math.min(MAX_RAISE, wanted));
	}

	/** The ground with the valleys in it, in blocks above sea level. */
	public double ground(double wx, double wz) {
		double[] out = new double[2];
		this.column(wx, wz, out);
		return out[0];
	}

	/** The level of the river or lake water over a column, or NaN. */
	public double water(double wx, double wz) {
		double[] out = new double[2];
		this.column(wx, wz, out);
		return out[1];
	}

	/** Every river's nodes, for tests and previews: how many rivers there are. */
	public int rivers() {
		return this.reaches.length;
	}

	/** For previews: the river nodes' unit list. */
	List<double[]> line(int river) {
		List<double[]> out = new ArrayList<>(this.reaches[river].x.length);
		for (int k = 0; k < this.reaches[river].x.length; k++) {
			out.add(new double[] { this.reaches[river].x[k], this.reaches[river].z[k] });
		}
		return out;
	}
}
