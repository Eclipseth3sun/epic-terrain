package com.epicterrain.earth;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Numbers that say whether a simulated continent behaves like a real one, so it can be judged without only looking at it.
 *
 * <ul>
 *   <li>the shares of the window that are sea, and of the continental plates that are under water (which should match the dial);</li>
 *   <li>the heights land reaches, and how high the young belts and the fossil belts stand (old should be much lower);</li>
 *   <li>the slope players would see, as the share steeper than 30, 45 and 60 degrees;</li>
 *   <li>the concavity of rivers: real channels get gentler downstream as the area they drain grows, slope = c * area^-theta, with theta
 *       around 0.3 to 0.6;</li>
 *   <li>Hack's law: the longest river of a basin grows with its area as length = c * area^h, with h around 0.5 to 0.6;</li>
 *   <li>how many lakes there are, and how much river.</li>
 * </ul>
 */
public final class EarthMetrics {
	public double seaShare;
	public double continentalWaterShare;
	public double[] landBlocks = new double[4];
	public double youngBlocks;
	public double oldBlocks;
	public double steeper30;
	public double steeper45;
	public double steeper60;
	public double medianSlope;
	public double concavity = Double.NaN;
	public double hack = Double.NaN;
	public int lakes;
	/** Length of the main river of the biggest basin, in blocks. */
	public double longestRiverBlocks;
	/** The longest stretch of plains river whose cells all step the same way (a straight line, often at 45 degrees on flat ground), in cells. */
	public int straightestRun;
	public String straightestAt = "";
	public double lakeShare;
	/** Share of the land that floods at all, however shallowly, and how deep that water is (metres). */
	public double lakeFloodedShare;
	public double floodedDepthMedian;
	public double floodedDepthP90;
	public double riverKm;
	public double reliefAtBoundaries;
	public double reliefFossil;
	public double reliefOther;
	public double scale;

	static EarthMetrics of(Continent c, Erosion e) {
		EarthMetrics m = new EarthMetrics();
		int n = c.nx * c.nz;
		int sea = 0;
		int cont = 0;
		int contSea = 0;
		int land = 0;
		for (int i = 0; i < n; i++) {
			boolean wet = c.height[i] < 0.0f;
			sea += wet ? 1 : 0;
			land += wet ? 0 : 1;
			if (c.continental[i] != 0 && c.rim[i] <= 0.0f) {
				cont++;
				contSea += wet ? 1 : 0;
			}
		}
		m.seaShare = (double) sea / n;
		m.continentalWaterShare = cont == 0 ? 0.0 : (double) contSea / cont;
		m.scale = c.scale;

		float[] heights = new float[land];
		int k = 0;
		for (int i = 0; i < n; i++) {
			if (c.height[i] >= 0.0f) {
				heights[k++] = c.height[i];
			}
		}
		Arrays.sort(heights);
		double[] cuts = { 0.10, 0.50, 0.90, 0.99 };
		for (int j = 0; j < cuts.length; j++) {
			m.landBlocks[j] = heights.length == 0 ? 0.0 : heights[(int) Math.min(heights.length - 1, cuts[j] * heights.length)] / EarthConfig.METRES_PER_BLOCK_UP;
		}
		m.youngBlocks = peakWhere(c, true) / EarthConfig.METRES_PER_BLOCK_UP;
		m.oldBlocks = peakWhere(c, false) / EarthConfig.METRES_PER_BLOCK_UP;

		// the slope a player would see, in game units (blocks over blocks)
		int steep30 = 0;
		int steep45 = 0;
		int steep60 = 0;
		int counted = 0;
		float[] slopes = new float[land];
		for (int z = 1; z < c.nz - 1; z++) {
			for (int x = 1; x < c.nx - 1; x++) {
				int i = z * c.nx + x;
				if (c.height[i] < 0.0f) {
					continue;
				}
				double gx = (c.height[i + 1] - c.height[i - 1]) / EarthConfig.METRES_PER_BLOCK_UP / (2.0 * c.cell);
				double gz = (c.height[i + c.nx] - c.height[i - c.nx]) / EarthConfig.METRES_PER_BLOCK_UP / (2.0 * c.cell);
				double degrees = Math.toDegrees(Math.atan(Math.hypot(gx, gz)));
				slopes[counted++] = (float) degrees;
				steep30 += degrees > 30.0 ? 1 : 0;
				steep45 += degrees > 45.0 ? 1 : 0;
				steep60 += degrees > 60.0 ? 1 : 0;
			}
		}
		if (counted > 0) {
			Arrays.sort(slopes, 0, counted);
			m.medianSlope = slopes[counted / 2];
			m.steeper30 = (double) steep30 / counted;
			m.steeper45 = (double) steep45 / counted;
			m.steeper60 = (double) steep60 / counted;
		}

		m.concavity = concavity(c, e);
		m.hack = hack(c, e, m);
		m.lakes = lakes(c);
		m.straightestRun = straightestRun(c, e, m);
		int lakeCells = 0;
		int riverCells = 0;
		for (int i = 0; i < n; i++) {
			lakeCells += c.lakeDepth[i] > 0.05f ? 1 : 0;
			riverCells += c.river[i] ? 1 : 0;
		}
		m.lakeShare = land == 0 ? 0.0 : (double) lakeCells / land;
		m.riverKm = riverCells * c.cell / 1000.0;

		// where the high ground is: at today's boundaries, on fossil belts, or mid-plate
		int high = 0;
		int atBoundary = 0;
		int fossil = 0;
		for (int i = 0; i < n; i++) {
			if (c.height[i] > 1500.0f) {
				high++;
				if (c.young[i] >= 0.1f) {
					atBoundary++;
				} else if (c.old[i] >= 0.1f) {
					fossil++;
				}
			}
		}
		if (high > 0) {
			m.reliefAtBoundaries = (double) atBoundary / high;
			m.reliefFossil = (double) fossil / high;
			m.reliefOther = 1.0 - m.reliefAtBoundaries - m.reliefFossil;
		}
		return m;
	}

	private static double peakWhere(Continent c, boolean young) {
		int n = c.nx * c.nz;
		int count = 0;
		for (int i = 0; i < n; i++) {
			count += qualifies(c, i, young) ? 1 : 0;
		}
		if (count < 20) {
			return 0.0;
		}
		float[] sample = new float[count];
		int k = 0;
		for (int i = 0; i < n; i++) {
			if (qualifies(c, i, young)) {
				sample[k++] = c.height[i];
			}
		}
		Arrays.sort(sample);
		return sample[(int) Math.min(count - 1, Math.floor(0.98 * count))];
	}

	private static boolean qualifies(Continent c, int i, boolean young) {
		if (c.height[i] <= 0.0f) {
			return false;
		}
		return young ? c.young[i] >= 0.4f : c.old[i] >= 0.4f && c.young[i] < 0.1f;
	}

	/** Fits slope = c * area^-theta over the channels. */
	private static double concavity(Continent c, Erosion e) {
		double sx = 0;
		double sy = 0;
		double sxx = 0;
		double sxy = 0;
		int count = 0;
		for (int i = 0; i < c.nx * c.nz; i++) {
			int r = e.receiver[i];
			if (r == i || c.height[i] < 50.0f || e.lake[i] || e.area[i] < 1.0e8 || e.area[i] > 1.0e10) {
				continue;
			}
			double reach = (i % c.nx != r % c.nx && i / c.nx != r / c.nx) ? e.dx * Math.sqrt(2.0) : e.dx;
			double slope = (e.zfill[i] - e.zfill[r]) / reach;
			if (slope <= 1.0e-4) {
				continue;
			}
			double lx = Math.log(e.area[i]);
			double ly = Math.log(slope);
			sx += lx;
			sy += ly;
			sxx += lx * lx;
			sxy += lx * ly;
			count++;
		}
		if (count < 100) {
			return Double.NaN;
		}
		return -((count * sxy - sx * sy) / (count * sxx - sx * sx));
	}

	/** Traces the main stem of the biggest basin upstream and fits length = c * area^h. */
	private static double hack(Continent c, Erosion e, EarthMetrics into) {
		int n = c.nx * c.nz;
		int mouth = -1;
		float biggest = 0.0f;
		for (int i = 0; i < n; i++) {
			int r = e.receiver[i];
			if (r != i && c.height[r] < 0.0f && !isEdge(e, i) && e.area[i] > biggest) {
				biggest = e.area[i];
				mouth = i;
			}
		}
		if (mouth < 0) {
			return Double.NaN;
		}
		ArrayDeque<double[]> samples = new ArrayDeque<>();
		int cell = mouth;
		double length = 0.0;
		while (true) {
			samples.add(new double[] { length, e.area[cell] });
			int next = -1;
			float best = 0.0f;
			int cx = cell % c.nx;
			int cz = cell / c.nx;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dxn = -1; dxn <= 1; dxn++) {
					int xx = cx + dxn;
					int zz = cz + dz;
					if ((dz == 0 && dxn == 0) || xx < 0 || zz < 0 || xx >= c.nx || zz >= c.nz) {
						continue;
					}
					int m = zz * c.nx + xx;
					if (e.receiver[m] == cell && m != cell && e.area[m] > best) {
						best = e.area[m];
						next = m;
					}
				}
			}
			if (next < 0) {
				break;
			}
			length += (cell % c.nx != next % c.nx && cell / c.nx != next / c.nx) ? e.dx * Math.sqrt(2.0) : e.dx;
			cell = next;
		}
		double total = length;
		into.longestRiverBlocks = length / c.config.blockMetres();
		double sx = 0;
		double sy = 0;
		double sxx = 0;
		double sxy = 0;
		int count = 0;
		for (double[] s : samples) {
			double toSource = total - s[0]; // Hack's length is measured from the point up to the divide
			if (s[1] < 1.0e8 || toSource < 1.0e4) {
				continue;
			}
			double lx = Math.log(s[1]);
			double ly = Math.log(toSource);
			sx += lx;
			sy += ly;
			sxx += lx * lx;
			sxy += lx * ly;
			count++;
		}
		if (count < 10) {
			return Double.NaN;
		}
		return (count * sxy - sx * sy) / (count * sxx - sx * sx);
	}

	private static boolean isEdge(Erosion e, int i) {
		return e.edge[i];
	}

	private static int straightestRun(Continent c, Erosion e, EarthMetrics m) {
		int[] run = new int[c.nx * c.nz];
		int longest = 0;
		int at = 0;
		for (int k = 0; k < e.count; k++) {
			int i = e.order[k]; // receivers come before the cells that drain to them
			int r = e.receiver[i];
			if (r == i) {
				continue;
			}
			int offset = r - i;
			int next = e.receiver[r];
			run[i] = 1 + (next != r && next - r == offset ? run[r] : 0);
			if (c.river[i] && c.rim[i] <= 0.0f && c.young[i] < 0.05f && c.old[i] < 0.05f) { // a river on the plains, not on the coastal slope or a range's flank
				if (run[i] > longest) {
					longest = run[i];
					at = i;
				}
			}
		}
		int r0 = e.receiver[at];
		m.straightestAt = String.format("from cell (%d, %d) stepping (%d, %d), height %.0f m", at % c.nx, at / c.nx, r0 % c.nx - at % c.nx, r0 / c.nx - at / c.nx, c.height[at]);
		return longest;
	}

	private static int lakes(Continent c) {
		int n = c.nx * c.nz;
		boolean[] seen = new boolean[n];
		int components = 0;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			if (seen[i] || c.lakeDepth[i] <= 0.05f) {
				continue;
			}
			int size = 0;
			seen[i] = true;
			queue.add(i);
			while (!queue.isEmpty()) {
				int cell = queue.poll();
				size++;
				int cx = cell % c.nx;
				int cz = cell / c.nx;
				int[][] around = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
				for (int[] d : around) {
					int xx = cx + d[0];
					int zz = cz + d[1];
					if (xx < 0 || zz < 0 || xx >= c.nx || zz >= c.nz) {
						continue;
					}
					int m = zz * c.nx + xx;
					if (!seen[m] && c.lakeDepth[m] > 0.05f) {
						seen[m] = true;
						queue.add(m);
					}
				}
			}
			components += size >= 4 ? 1 : 0;
		}
		return components;
	}

	public String report() {
		return String.format(
			"sea %.1f%% of the window, continental plates %.1f%% under water; land height (blocks above sea) p10 %.0f, median %.0f, p90 %.0f, p99 %.0f; "
				+ "young belts reach %.0f, fossil belts %.0f; slope seen by a player: median %.0f deg, over 30 deg %.0f%%, over 45 deg %.0f%%, over 60 deg %.0f%%; "
				+ "river concavity %.2f, Hack exponent %.2f; longest straight river run %d cells (%s); %d lakes a block or more deep (%.1f%% of land; %.1f%% floods at all, median depth %.1f m, p90 %.1f m), %.0f km of river; high ground (over 1500 m): %.0f%% at today's boundaries, %.0f%% on fossil belts, %.0f%% elsewhere; "
				+ "heights were scaled by %.2f to put the young peaks at their target",
			100 * this.seaShare, 100 * this.continentalWaterShare, this.landBlocks[0], this.landBlocks[1], this.landBlocks[2], this.landBlocks[3],
			this.youngBlocks, this.oldBlocks, this.medianSlope, 100 * this.steeper30, 100 * this.steeper45, 100 * this.steeper60,
			this.concavity, this.hack, this.straightestRun, this.straightestAt, this.lakes, 100 * this.lakeShare, 100 * this.lakeFloodedShare, this.floodedDepthMedian, this.floodedDepthP90, this.riverKm,
			100 * this.reliefAtBoundaries, 100 * this.reliefFossil, 100 * this.reliefOther, this.scale);
	}
}
