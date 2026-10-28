package com.epicterrain.river;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds where a river runs: a cheapest path over the ground from a source to water.
 *
 * <p>The search state is the cell <em>and</em> the water level, because that is what makes a river different from a path. The
 * level can only fall: it follows the ground down (two blocks beneath it) and never rises, so a hill in the way has to be dug
 * through, and the level cannot go below sea level. A step costs its length times one plus {@code digCharge} per block of
 * ground above the water, so the cheapest route digs little, runs downhill into the lowlands and stays in them. A state is only
 * worth keeping if no other way to reach the same cell has both a higher water level and a lower cost.
 */
final class RiverRouter {
	/** Cell size of the routing grid in blocks. */
	static final int STEP = 64;
	/** The water level is searched in buckets of this many blocks. */
	private static final int WSTEP = 3;
	private static final double SEA = River.SEA_LEVEL;
	private static final int[][] DIRS = {
		{ 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
		{ 2, 1 }, { 2, -1 }, { -2, 1 }, { -2, -1 }, { 1, 2 }, { 1, -2 }, { -1, 2 }, { -1, -2 }
	};

	/** Where a route may go and where it ends. Cells are routing-grid cells. */
	interface Goal {
		/** Cells no river may enter. */
		boolean blocked(int gx, int gz);

		/** Has the river reached water it can end in? {@code water} is the river's current level. */
		boolean reached(int gx, int gz, double ground, double water);

		/** A lower bound on the distance still to travel, in blocks (0 if unknown). */
		double heuristic(int gx, int gz);
	}

	/** The cells of a route from source to end, or {@code ok == false} if none was found. */
	record Result(List<int[]> cells, boolean ok, int expansions) {
	}

	private record Node(int gx, int gz, int bucket, double cost, double estimate, Node parent) {
	}

	/** The cheapest ways found to reach one cell. */
	private static final class Pareto {
		private int[] bucket = new int[4];
		private double[] cost = new double[4];
		private int size;

		boolean offer(int b, double c) {
			for (int i = 0; i < this.size; i++) {
				if (this.bucket[i] >= b && this.cost[i] <= c) {
					return false;
				}
			}
			int keep = 0;
			for (int i = 0; i < this.size; i++) {
				if (!(this.bucket[i] <= b && this.cost[i] >= c)) {
					this.bucket[keep] = this.bucket[i];
					this.cost[keep] = this.cost[i];
					keep++;
				}
			}
			this.size = keep;
			if (this.size == this.bucket.length) {
				this.bucket = java.util.Arrays.copyOf(this.bucket, this.size * 2);
				this.cost = java.util.Arrays.copyOf(this.cost, this.size * 2);
			}
			this.bucket[this.size] = b;
			this.cost[this.size] = c;
			this.size++;
			return true;
		}

		boolean has(int b, double c) {
			for (int i = 0; i < this.size; i++) {
				if (this.bucket[i] == b && Math.abs(this.cost[i] - c) < 1e-9) {
					return true;
				}
			}
			return false;
		}
	}

	private final TerrainView terrain;
	private final double digCharge;
	private final int maxExpansions;
	private final Map<Long, Double> groundCache = new HashMap<>();
	private final Map<Long, Double> accurateCache = new HashMap<>();

	RiverRouter(TerrainView terrain, double digCharge, int maxExpansions) {
		this.terrain = terrain;
		this.digCharge = digCharge;
		this.maxExpansions = maxExpansions;
	}

	static long key(int gx, int gz) {
		return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
	}

	/**
	 * Is the water at a routing cell part of a body at least {@code minCells} cells big? Found by flood-filling the cells whose ground
	 * is under water, stopping as soon as the body is big enough, and remembering the answer for every cell touched.
	 */
	final class WaterBodies {
		private final int minCells;
		private final Map<Long, Boolean> big = new HashMap<>();

		WaterBodies(double minKm2) {
			double cellKm2 = (STEP / 1000.0) * (STEP / 1000.0);
			// a body this big is certainly "big", and the cap keeps a silly setting from flood-filling the whole world
			this.minCells = (int) Math.min(200_000, Math.ceil(minKm2 / cellKm2));
		}

		boolean isBig(int gx, int gz) {
			if (this.minCells <= 1) {
				return true;
			}
			Boolean known = this.big.get(key(gx, gz));
			if (known != null) {
				return known;
			}
			java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
			java.util.Set<Long> visited = new java.util.HashSet<>();
			stack.push(new int[] { gx, gz });
			visited.add(key(gx, gz));
			while (!stack.isEmpty() && visited.size() < this.minCells) {
				int[] cell = stack.pop();
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						int nx = cell[0] + dx;
						int nz = cell[1] + dz;
						if ((dx != 0 || dz != 0) && !visited.contains(key(nx, nz)) && RiverRouter.this.ground(nx, nz) <= 62.5) {
							visited.add(key(nx, nz));
							stack.push(new int[] { nx, nz });
						}
					}
				}
			}
			boolean result = visited.size() >= this.minCells;
			for (long k : visited) {
				this.big.put(k, result);
			}
			return result;
		}
	}

	/** The cheap ground estimate at the centre of a routing cell (cached): what routing explores with. */
	double ground(int gx, int gz) {
		return this.groundCache.computeIfAbsent(key(gx, gz), k -> this.terrain.roughGround((double) gx * STEP, (double) gz * STEP));
	}

	/** The accurate ground height at the centre of a routing cell (cached): for the few decisions that need it. */
	double accurateGround(int gx, int gz) {
		return this.accurateCache.computeIfAbsent(key(gx, gz), k -> this.terrain.ground((double) gx * STEP, (double) gz * STEP));
	}

	Result route(int startGx, int startGz, Goal goal) {
		double startGround = this.ground(startGx, startGz);
		int startBucket = Math.max(0, (int) Math.floor((startGround - 2 - SEA) / WSTEP));
		Map<Long, Pareto> seen = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate));
		open.add(new Node(startGx, startGz, startBucket, 0, 0, null));
		seen.computeIfAbsent(key(startGx, startGz), k -> new Pareto()).offer(startBucket, 0);
		int expansions = 0;
		Node end = null;
		while (!open.isEmpty()) {
			Node current = open.poll();
			if (!seen.get(key(current.gx(), current.gz())).has(current.bucket(), current.cost())) {
				continue;
			}
			if (++expansions > this.maxExpansions) {
				break;
			}
			double here = this.ground(current.gx(), current.gz());
			if (goal.reached(current.gx(), current.gz(), here, SEA + current.bucket() * WSTEP)) {
				end = current;
				break;
			}
			double cutHere = Math.max(0, here - (SEA + current.bucket() * WSTEP));
			for (int[] d : DIRS) {
				int nx = current.gx() + d[0];
				int nz = current.gz() + d[1];
				if (goal.blocked(nx, nz)) {
					continue;
				}
				double there = this.ground(nx, nz);
				int bucket = Math.max(0, Math.min(current.bucket(), (int) Math.floor((there - 2 - SEA) / WSTEP)));
				double cutThere = Math.max(0, there - (SEA + bucket * WSTEP));
				double cost = current.cost() + Math.hypot(d[0], d[1]) * STEP * (1 + this.digCharge * 0.5 * (cutHere + cutThere));
				if (!seen.computeIfAbsent(key(nx, nz), k -> new Pareto()).offer(bucket, cost)) {
					continue;
				}
				open.add(new Node(nx, nz, bucket, cost, cost + goal.heuristic(nx, nz), current));
			}
		}
		if (end == null) {
			return new Result(List.of(), false, expansions);
		}
		List<int[]> cells = new ArrayList<>();
		for (Node node = end; node != null; node = node.parent()) {
			cells.add(new int[] { node.gx(), node.gz() });
		}
		Collections.reverse(cells);
		return new Result(cells, true, expansions);
	}
}
