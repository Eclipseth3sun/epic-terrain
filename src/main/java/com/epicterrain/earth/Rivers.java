package com.epicterrain.earth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

import com.epicterrain.river.Meander;
import com.epicterrain.river.TerrainView;

/**
 * The rivers of a simulated continent as lines with a width, ready to be drawn or carved.
 *
 * <p>The simulation already knows where the water runs: every cell drains to one neighbour, and a cell is a river where at least
 * {@link ContinentSimulator#RIVER_BLOCKS2} square blocks drain through it. That is a tree. It is cut into rivers: each starts at a source
 * and follows the cells downstream, carrying on through each confluence with whichever branch drains more, until it reaches the water or
 * is itself taken in by a bigger river. The cells are tens of blocks apart and the route is a chain of 45-degree steps, so each river
 * is smoothed (corners cut, then relaxed to the scale of its own width) and, where the ground is gentle, given bends by the same
 * {@link Meander} the planned rivers of the original model use. A river that joins another ends on the finished line of the bigger one.
 *
 * <p>True to scale these rivers would be hair-thin: a block here is 21 m across, so even the biggest river of a 98,000 km2 catchment is
 * about ten blocks wide, and a stream of the smallest catchment counted is a fraction of a block. Width is therefore an exaggeration
 * with a rule: {@link Dials#minWidth} blocks wide at the smallest river, growing with the square root of the drainage area (the way
 * real channels do), which makes the biggest river on a typical window about 70 blocks across.
 *
 * <p>Pure, and deterministic in the continent and the ground it is given.
 */
public final class Rivers {
	/** Nodes of a finished river are about this far apart, in blocks. */
	static final double SPACING = 16.0;
	/**
	 * A river joins the bigger one at its nearest node, if that is within this many cells of where the simulation had them meet: a wide river's
	 * bends carry it several hundred blocks (up to about 430) from the cells it was routed over.
	 */
	static final double JOIN_CELLS = 14.0;

	/** @param minWidth the width, in blocks, of the smallest river: one that drains {@link ContinentSimulator#RIVER_BLOCKS2} */
	public record Dials(double minWidth) {
		public static Dials defaults() {
			return new Dials(3.0);
		}

		public static Dials fromProperties() {
			String value = System.getProperty("epicterrain.earth.riverWidth");
			return new Dials(value == null ? 3.0 : Double.parseDouble(value));
		}
	}

	/** One river, from its source to the water or to the bigger river that takes it in. */
	public static final class River {
		public final int index;
		/** The cells of the simulation it was routed over, source first; the last is the one it ends in (a river's, or water). */
		public final int[] cells;
		/** The finished line, as (x, z) in blocks. */
		public final List<double[]> line;
		/** The width, in blocks, at each node of the line. */
		public final double[] width;
		/** Blocks along the finished line. */
		public final double length;
		/** Blocks along the chain of cells, for comparing: the winding adds the difference. */
		public final double routedLength;
		/** The index of the river this one flows into, or -1 if it reaches water. */
		public final int joins;

		River(int index, int[] cells, List<double[]> line, double[] width, double length, double routedLength, int joins) {
			this.index = index;
			this.cells = cells;
			this.line = line;
			this.width = width;
			this.length = length;
			this.routedLength = routedLength;
			this.joins = joins;
		}

		public double mouthWidth() {
			return this.width[this.width.length - 1];
		}
	}

	private Rivers() {
	}

	/** The width in blocks of a river that drains the given number of square blocks (never less than the smallest river's). */
	public static double width(double drainageBlocks2, Dials dials) {
		return dials.minWidth() * Math.sqrt(Math.max(1.0, drainageBlocks2 / ContinentSimulator.RIVER_BLOCKS2));
	}

	private record View(DoubleBinaryOperator height) implements TerrainView {
		@Override
		public double ground(double x, double z) {
			return this.height.applyAsDouble(x, z);
		}

		@Override
		public double uplift(double x, double z) {
			return 0.0;
		}

		@Override
		public double land(double x, double z) {
			return 1.0;
		}
	}

	/**
	 * @param ground the height of the smooth ground at a block position (only differences are used, to tell gentle ground from steep and
	 *               to keep a bend from climbing out of its valley)
	 */
	public static List<River> build(Continent c, DoubleBinaryOperator ground, Dials dials) {
		int n = c.nx * c.nz;
		double blocks2 = c.config.blockMetres() * c.config.blockMetres();
		int[] inflow = new int[n];
		int[] main = new int[n];
		Arrays.fill(main, -1);
		for (int i = 0; i < n; i++) {
			int r = c.receiver[i];
			if (c.river[i] && r != i && c.river[r]) {
				inflow[r]++;
				if (main[r] < 0 || c.area[i] > c.area[main[r]]) {
					main[r] = i;
				}
			}
		}

		// cut the tree into chains: a river carries on through a confluence along the branch that drains more
		int[] owner = new int[n];
		Arrays.fill(owner, -1);
		List<int[]> chains = new ArrayList<>();
		List<Integer> tails = new ArrayList<>();
		List<Integer> joinCells = new ArrayList<>();
		for (int source = 0; source < n; source++) {
			if (!c.river[source] || inflow[source] > 0) {
				continue;
			}
			int id = chains.size();
			List<Integer> cells = new ArrayList<>();
			cells.add(source);
			owner[source] = id;
			int cur = source;
			int join = -1;
			while (true) {
				int next = c.receiver[cur];
				if (next == cur) {
					break;
				}
				cells.add(next);
				if (!c.river[next]) {
					break;
				}
				if (main[next] == cur) {
					owner[next] = id;
					cur = next;
				} else {
					join = next;
					break;
				}
			}
			chains.add(cells.stream().mapToInt(Integer::intValue).toArray());
			tails.add(cur);
			joinCells.add(join);
		}

		// a bigger river is finished before the smaller ones that end on it
		Integer[] order = new Integer[chains.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -c.area[tails.get(i)]));

		TerrainView view = new View(ground);
		River[] built = new River[chains.size()];
		for (int id : order) {
			int[] cells = chains.get(id);
			int join = joinCells.get(id);
			int host = join >= 0 ? owner[join] : -1;

			int m = cells.length;
			List<double[]> points = new ArrayList<>(m);
			double[] cellWidth = new double[m];
			for (int k = 0; k < m; k++) {
				int cell = cells[k];
				points.add(new double[] { c.x0 + (cell % c.nx) * c.cell, c.z0 + (cell / c.nx) * c.cell });
				// water at the end (and the confluence, which is the bigger river's) takes the width of the last river cell before it
				int measured = k > 0 && (!c.river[cell] || (k == m - 1 && join >= 0)) ? cells[k - 1] : cell;
				cellWidth[k] = width(c.area[measured] / blocks2, dials);
			}
			if (host >= 0) {
				double[] at = points.get(m - 1);
				double nearest = JOIN_CELLS * c.cell;
				for (double[] node : built[host].line) {
					double d = Math.hypot(node[0] - at[0], node[1] - at[1]);
					if (d < nearest) {
						nearest = d;
						at = node;
					}
				}
				points.set(m - 1, at);
			}
			double[] along = new double[m];
			for (int k = 1; k < m; k++) {
				along[k] = along[k - 1] + Math.hypot(points.get(k)[0] - points.get(k - 1)[0], points.get(k)[1] - points.get(k - 1)[1]);
			}
			double routedLength = along[m - 1];

			List<double[]> grid = resample(chaikin(chaikin(points)), SPACING);
			double gridLength = length(grid);
			DoubleUnaryOperator widthOnGrid = t -> widthAt(t / Math.max(1.0e-9, gridLength), along, cellWidth);
			List<double[]> routed = Meander.relax(grid, widthOnGrid);
			double routedGridLength = length(routed);
			DoubleUnaryOperator widthOnRouted = t -> widthAt(t / Math.max(1.0e-9, routedGridLength), along, cellWidth);
			long seed = c.config.seed() * 0x9E3779B97F4A7C15L + cells[0];
			List<double[]> line = routed.size() < 12 ? resample(routed, SPACING) : resample(smooth(Meander.apply(routed, widthOnRouted, view, seed)), SPACING);

			double[] width = new double[line.size()];
			double total = length(line);
			double travelled = 0.0;
			for (int k = 0; k < width.length; k++) {
				if (k > 0) {
					travelled += Math.hypot(line.get(k)[0] - line.get(k - 1)[0], line.get(k)[1] - line.get(k - 1)[1]);
				}
				width[k] = widthAt(travelled / Math.max(1.0e-9, total), along, cellWidth);
			}
			built[id] = new River(id, cells, line, width, total, routedLength, host);
		}
		return Arrays.asList(built);
	}

	/**
	 * The share of a river's nodes, where it is at least {@code minWidth} wide, at which it bends tighter than it is wide: the radius of the
	 * circle through the line one river's width either side of the node is under the width. The winding is meant to keep this near zero.
	 */
	static double tightShare(River r, double minWidth) {
		int n = r.line.size();
		int bent = 0;
		int counted = 0;
		for (int i = 1; i < n - 1; i++) {
			double w = r.width[i];
			if (w < minWidth) {
				continue;
			}
			int m = Math.max(1, (int) Math.round(w / Rivers.SPACING));
			if (i - m < 0 || i + m > n - 1) {
				continue;
			}
			double[] a = r.line.get(i - m);
			double[] b = r.line.get(i);
			double[] c = r.line.get(i + m);
			double cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
			double chords = Math.hypot(b[0] - a[0], b[1] - a[1]) * Math.hypot(c[0] - b[0], c[1] - b[1]) * Math.hypot(c[0] - a[0], c[1] - a[1]);
			double radius = Math.abs(cross) < 1.0e-9 ? Double.MAX_VALUE : chords / (2.0 * Math.abs(cross));
			counted++;
			bent += radius < w ? 1 : 0;
		}
		return counted == 0 ? 0.0 : (double) bent / counted;
	}

	/** The width at a fraction of the way along the chain of cells, between the widths of the cells either side of it. */
	private static double widthAt(double fraction, double[] along, double[] cellWidth) {
		double target = Math.max(0.0, Math.min(1.0, fraction)) * along[along.length - 1];
		int k = 1;
		while (k < along.length - 1 && along[k] < target) {
			k++;
		}
		double span = along[k] - along[k - 1];
		double t = span > 0.0 ? Math.max(0.0, Math.min(1.0, (target - along[k - 1]) / span)) : 0.0;
		return cellWidth[k - 1] + (cellWidth[k] - cellWidth[k - 1]) * t;
	}

	static double length(List<double[]> line) {
		double length = 0.0;
		for (int i = 1; i < line.size(); i++) {
			length += Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
		}
		return length;
	}

	/** Corner cutting that leaves the two ends where they are. */
	private static List<double[]> chaikin(List<double[]> p) {
		if (p.size() < 3) {
			return p;
		}
		List<double[]> out = new ArrayList<>();
		out.add(p.get(0));
		for (int i = 0; i < p.size() - 1; i++) {
			double[] a = p.get(i);
			double[] b = p.get(i + 1);
			if (i > 0) {
				out.add(new double[] { 0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1] });
			}
			if (i < p.size() - 2) {
				out.add(new double[] { 0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1] });
			}
		}
		out.add(p.get(p.size() - 1));
		return out;
	}

	/** A [1 2 1] average along the line that leaves the two ends where they are. */
	private static List<double[]> smooth(List<double[]> p) {
		if (p.size() < 3) {
			return p;
		}
		List<double[]> out = new ArrayList<>(p.size());
		out.add(p.get(0));
		for (int i = 1; i < p.size() - 1; i++) {
			out.add(new double[] { 0.25 * p.get(i - 1)[0] + 0.5 * p.get(i)[0] + 0.25 * p.get(i + 1)[0], 0.25 * p.get(i - 1)[1] + 0.5 * p.get(i)[1] + 0.25 * p.get(i + 1)[1] });
		}
		out.add(p.get(p.size() - 1));
		return out;
	}

	/** The same line with its points evenly spaced (about {@code spacing} apart), both ends kept. */
	private static List<double[]> resample(List<double[]> p, double spacing) {
		double total = length(p);
		int count = Math.max(2, (int) Math.round(total / spacing) + 1);
		List<double[]> out = new ArrayList<>(count);
		out.add(p.get(0));
		int segment = 0;
		double start = 0.0;
		for (int k = 1; k < count - 1; k++) {
			double target = total * k / (count - 1);
			while (segment < p.size() - 2) {
				double segLength = Math.hypot(p.get(segment + 1)[0] - p.get(segment)[0], p.get(segment + 1)[1] - p.get(segment)[1]);
				if (start + segLength >= target) {
					break;
				}
				start += segLength;
				segment++;
			}
			double[] a = p.get(segment);
			double[] b = p.get(segment + 1);
			double segLength = Math.hypot(b[0] - a[0], b[1] - a[1]);
			double t = segLength > 0.0 ? (target - start) / segLength : 0.0;
			out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t });
		}
		out.add(p.get(p.size() - 1));
		return out;
	}
}
