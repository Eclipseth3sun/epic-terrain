package com.epicterrain.river;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.epicterrain.worldgen.RangeCatalog;
import com.epicterrain.worldgen.RangeShape;

/**
 * Plans the rivers of each range: a few tributaries that rise on the flanks and end in the first water they meet, and, rarely, one
 * grand river that runs from the range to a sizeable sea.
 *
 * <p>Everything is a pure function of the world seed, the range and the terrain, so any chunk can ask for the plan of a nearby
 * range and always gets the same answer; nothing is stored. Rivers are routed over the ground as it is before any river exists
 * (see {@link RiverRouter}), then smoothed, given a water level that only falls, widths and depths that grow downstream, and
 * waterfalls wherever the water drops steeply.
 *
 * <p>After the range's steep flank a grand river runs at sea level, over low ground, so vanilla rivers (all at sea level) join it
 * flush and the grand river takes priority only inside its own corridor.
 */
public final class RiverPlanner {
	private static final double SEA = River.SEA_LEVEL;
	/** Ground this low is under water already: a vanilla river, lake or ocean (sea level is 63, so the top water block is 62). */
	private static final double WATER_GROUND = 62.5;
	/** A grand river has arrived once it is this close to its target sea and its ground is at sea level. */
	private static final double ARRIVE = 300.0;
	private static final double ARRIVE_GROUND = 63.5;
	/** Water falling this much per block of river counts as steep (5 blocks within 32). */
	private static final double STEEP_SLOPE = 5.0 / 32.0;
	/** The banks are looked at this far beyond the water's edge. */
	private static final double BANK_PROBE = 2.0;
	/** A bank is only measured exactly if the cheap estimate puts it at least this much below the river's line. */
	private static final double BANK_SCREEN = 1.5;
	/** The water is set lower than the line's own ground by at most this much to clear a low bank; beyond that the lip is all the bank gets. */
	private static final double MAX_BANK_DIP = 10.0;
	/** A river must have fallen at least this much from its source before it may end in another river. */
	private static final double MIN_JOIN_DROP = 30.0;
	/** Another river may only end in a river at least this far (blocks) downstream of that river's source. */
	private static final double MIN_JOIN_TRAVEL = 400.0;
	private static final int MAX_EXPANSIONS = 150_000;
	/** A grand river's route stays within this multiple of the reach (as the crow flies) of its source. */
	public static final double GRAND_ROUTE_FACTOR = 1.4;

	private final long seed;
	private final RiverConfig config;
	private final TerrainView terrain;
	private final RangeCatalog catalog;

	public RiverPlanner(long seed, RiverConfig config, TerrainView terrain, RangeCatalog catalog) {
		this.seed = seed;
		this.config = config;
		this.terrain = terrain;
		this.catalog = catalog;
	}

	public RiverConfig config() {
		return this.config;
	}

	/** Plans every range whose centre lies in a catalog cell. */
	public List<RangeRivers> planCell(int cellX, int cellZ) {
		return this.catalog.shapesInCell(cellX, cellZ).stream().map(this::plan).toList();
	}

	/** Does this range win the chance of a grand river? (Whether it can actually have one depends on there being a sea in reach.) */
	public boolean rollsGrand(RangeShape range) {
		return unit(hash(range, 0x6772616E64L)) < this.config.grandChance();
	}

	public RangeRivers plan(RangeShape range) {
		RiverRouter router = new RiverRouter(this.terrain, this.config.digCharge(), MAX_EXPANSIONS);
		Occupancy occupancy = new Occupancy();
		List<River> rivers = new ArrayList<>();
		List<double[]> sources = new ArrayList<>();
		boolean rolled = this.rollsGrand(range);
		if (rolled) {
			River grand = this.planGrand(range, router, sources);
			if (grand != null) {
				rivers.add(grand);
				occupancy.add(grand);
			}
		}

		SplittableRandom rng = new SplittableRandom(hash(range, 0x736F75726365L));
		int count = (int) Math.max(this.config.minSources(), Math.min(this.config.maxSources(),
			Math.round(range.spineLength() / 1000.0 * this.config.sourcesPerKm())));
		double taper = range.taper();
		int failed = 0;
		for (int k = 0; k < count; k++) {
			double t = taper + (1.0 - 2.0 * taper) * (k + 0.2 + 0.6 * rng.nextDouble()) / count;
			int side = rng.nextBoolean() ? 1 : -1;
			double fraction = this.config.sourceFractionMin() + (this.config.sourceFractionMax() - this.config.sourceFractionMin()) * rng.nextDouble();
			double[] source = this.pickSource(range, router, t, side, fraction);
			if (source == null) {
				source = this.pickSource(range, router, t, -side, fraction);
			}
			if (source == null || this.tooClose(source, sources)) {
				failed++;
				continue;
			}
			River river = this.planTributary(range, k, source, router, occupancy);
			if (river == null) {
				failed++;
				continue;
			}
			sources.add(source);
			rivers.add(river);
			occupancy.add(river);
		}
		return new RangeRivers(range, List.copyOf(rivers), rolled, count, failed);
	}

	// ---- sources -----------------------------------------------------------------------------------------------------

	/**
	 * A point on the flank of the range near spine position {@code t}, on one side, where the range has fallen to {@code fraction} of
	 * its crest height: high on the slope, where a river begins. Null if the range is too low there or the ground is too low.
	 */
	private double[] pickSource(RangeShape range, RiverRouter router, double t, int side, double fraction) {
		double[] spine = range.spine(t);
		double[] crest = this.catalog.toWorld(spine[0], spine[1]);
		double crestUplift = this.terrain.uplift(crest[0], crest[1]);
		if (crestUplift < this.config.minCrestUplift()) {
			return null;
		}
		double nx = -spine[3] * side;
		double nz = spine[2] * side;
		double target = crestUplift * fraction;
		double px = crest[0];
		double pz = crest[1];
		for (double d = 32; d <= 1600; d += 32) {
			px = crest[0] + nx * d;
			pz = crest[1] + nz * d;
			if (this.terrain.uplift(px, pz) <= target) {
				break;
			}
		}
		int gx = (int) Math.round(px / RiverRouter.STEP);
		int gz = (int) Math.round(pz / RiverRouter.STEP);
		if (router.ground(gx, gz) < this.config.minSourceGround()) {
			return null;
		}
		return new double[] { (double) gx * RiverRouter.STEP, (double) gz * RiverRouter.STEP };
	}

	private boolean tooClose(double[] source, List<double[]> others) {
		for (double[] other : others) {
			if (Math.hypot(source[0] - other[0], source[1] - other[1]) < this.config.minSourceSpacing()) {
				return true;
			}
		}
		return false;
	}

	// ---- tributaries ---------------------------------------------------------------------------------------------------

	private River planTributary(RangeShape range, int index, double[] source, RiverRouter router, Occupancy occupancy) {
		RiverRouter.WaterBodies bodies = router.new WaterBodies(this.config.minWaterKm2());
		// a river only joins another once it has come down this far from its own source: no creeping along the slope to meet a
		// neighbour at its head
		double sourceWater = Math.max(SEA, router.accurateGround((int) Math.round(source[0] / RiverRouter.STEP), (int) Math.round(source[1] / RiverRouter.STEP)) - 2.0);
		RiverConfig config = this.config;
		RiverRouter.Goal goal = new RiverRouter.Goal() {
			@Override
			public boolean blocked(int gx, int gz) {
				return Math.hypot((double) gx * RiverRouter.STEP - source[0], (double) gz * RiverRouter.STEP - source[1]) > config.maxStray();
			}

			@Override
			public boolean reached(int gx, int gz, double ground, double water) {
				if (ground <= WATER_GROUND && router.accurateGround(gx, gz) <= WATER_GROUND && bodies.isBig(gx, gz)) {
					return true;
				}
				Double other = occupancy.levelAt(gx, gz);
				return other != null && water + 3.0 > other && water <= sourceWater - MIN_JOIN_DROP;
			}

			@Override
			public double heuristic(int gx, int gz) {
				return 0.0;
			}
		};
		RiverRouter.Result result = router.route((int) Math.round(source[0] / RiverRouter.STEP), (int) Math.round(source[1] / RiverRouter.STEP), goal);
		if (!result.ok()) {
			return null;
		}
		int[] last = result.cells().get(result.cells().size() - 1);
		Occupancy.Hit hit = occupancy.hit(last[0], last[1]);
		boolean reachedWater = router.accurateGround(last[0], last[1]) <= WATER_GROUND && bodies.isBig(last[0], last[1]);
		River joined = !reachedWater && hit != null ? hit.river() : null;
		return this.build(range.id() + "/t" + index, River.Kind.TRIBUTARY, range, this.config.tributary(), result.cells(),
			joined == null ? River.Ending.VANILLA_WATER : River.Ending.RIVER, joined);
	}

	// ---- the grand river -------------------------------------------------------------------------------------------------

	private River planGrand(RangeShape range, RiverRouter router, List<double[]> sources) {
		double[] middle = this.catalog.toWorld(range.spine(0.5)[0], range.spine(0.5)[1]);
		double radius = range.spineLength() / 2.0 + this.config.grandReach() + 10_000.0;
		SeaMap sea = SeaMap.build(this.terrain, middle[0], middle[1], radius, this.config.seaMinKm2());

		// the source: the flank point of the range that is closest to a sea big enough to run to
		double[] best = null;
		double bestDistance = Double.MAX_VALUE;
		int candidates = 32;
		double taper = range.taper();
		for (int i = 0; i < candidates; i++) {
			double t = taper + (1.0 - 2.0 * taper) * (i + 0.5) / candidates;
			for (int side = -1; side <= 1; side += 2) {
				double[] source = this.pickSource(range, router, t, side, 0.7);
				if (source == null) {
					continue;
				}
				double d = sea.distanceAt(source[0], source[1]);
				if (!Double.isNaN(d) && d < bestDistance) {
					bestDistance = d;
					best = source;
				}
			}
		}
		if (best == null || bestDistance > this.config.grandReach()) {
			return null;
		}

		double maxRoute = this.config.grandReach() * GRAND_ROUTE_FACTOR;
		double[] origin = best;
		RiverRouter.Goal goal = new RiverRouter.Goal() {
			@Override
			public boolean blocked(int gx, int gz) {
				double x = (double) gx * RiverRouter.STEP;
				double z = (double) gz * RiverRouter.STEP;
				double d = sea.distanceAt(x, z);
				return Double.isNaN(d) || (sea.oceanAt(x, z) && d > ARRIVE) || Math.hypot(x - origin[0], z - origin[1]) > maxRoute;
			}

			@Override
			public boolean reached(int gx, int gz, double ground, double water) {
				double d = sea.distanceAt((double) gx * RiverRouter.STEP, (double) gz * RiverRouter.STEP);
				return !Double.isNaN(d) && d <= ARRIVE && ground <= ARRIVE_GROUND && router.accurateGround(gx, gz) <= ARRIVE_GROUND;
			}

			@Override
			public double heuristic(int gx, int gz) {
				double d = sea.distanceAt((double) gx * RiverRouter.STEP, (double) gz * RiverRouter.STEP);
				return Double.isNaN(d) ? 0.0 : Math.max(0.0, d - ARRIVE);
			}
		};
		RiverRouter.Result result = router.route((int) Math.round(best[0] / RiverRouter.STEP), (int) Math.round(best[1] / RiverRouter.STEP), goal);
		if (!result.ok()) {
			return null;
		}
		River river = this.build(range.id() + "/g", River.Kind.GRAND, range, this.config.grand(), result.cells(), River.Ending.SEA, null);
		if (river != null) {
			sources.add(best);
		}
		return river;
	}

	// ---- turning a route into a river -----------------------------------------------------------------------------------

	private River build(String id, River.Kind kind, RangeShape range, RiverConfig.Profile profile, List<int[]> cells, River.Ending ending, River joined) {
		List<double[]> points = new ArrayList<>();
		for (int[] cell : cells) {
			points.add(new double[] { (double) cell[0] * RiverRouter.STEP, (double) cell[1] * RiverRouter.STEP });
		}
		River.Node joinNode = null;
		if (joined != null) {
			double[] end = points.get(points.size() - 1);
			double nearest = Double.MAX_VALUE;
			for (int i = 0; i < joined.nodes().size(); i++) {
				River.Node candidate = joined.nodes().get(i);
				if (candidate.travelled() < MIN_JOIN_TRAVEL) {
					continue;
				}
				double d = Math.hypot(candidate.x() - end[0], candidate.z() - end[1]);
				if (d < nearest) {
					nearest = d;
					joinNode = candidate;
				}
			}
			points.set(points.size() - 1, new double[] { joinNode.x(), joinNode.z() });
		}
		List<double[]> gridLine = resample(smooth(smooth(points)), this.config.nodeSpacing() / 2.0);
		if (gridLine.size() < 3) {
			return null;
		}

		boolean flares = ending != River.Ending.RIVER;
		double routedLength = length(gridLine);
		// the router's corners are tens of blocks across: a wide river is smoothed to the scale of its own width, then winds where it
		// runs over gentle ground
		List<double[]> routed = Meander.relax(gridLine, t -> profile.widthAt(t, routedLength, flares));
		List<double[]> line = routed.size() < 12 ? routed
			: resample(smooth(Meander.apply(routed, t -> profile.widthAt(t, routedLength, flares), this.terrain, this.hashOf(id, 0x6D65616E646572L))), this.config.nodeSpacing() / 2.0);
		if (line.size() < 3) {
			return null;
		}

		int n = line.size();
		double[] x = new double[n];
		double[] z = new double[n];
		double[] travelled = new double[n];
		for (int i = 0; i < n; i++) {
			x[i] = line.get(i)[0];
			z[i] = line.get(i)[1];
			if (i > 0) {
				travelled[i] = travelled[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
			}
		}
		double length = travelled[n - 1];
		double[] ground = new double[n];
		double[] water = new double[n];
		double lowest = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			ground[i] = this.terrain.ground(x[i], z[i]);
			// The water runs two blocks under the ground it flows over, and no higher than the banks either side of it: a river is cut
			// into the ground, so on a hillside it sits down in the slope rather than on a built-up shelf. It can only fall, and never
			// goes below the sea.
			double onLine = ground[i] - 2.0;
			// once the water is down at sea level the banks cannot lower it further: nothing to look at
			double banks = lowest <= SEA ? Double.MAX_VALUE : this.lowestBank(x, z, travelled, i, length, profile, flares);
			double cap = Math.max(onLine - MAX_BANK_DIP, Math.min(onLine, banks));
			lowest = Math.min(lowest, cap);
			water[i] = Math.max(SEA, lowest);
		}
		if (joinNode != null) {
			ground[n - 1] = joinNode.ground();
			water[n - 1] = Math.min(water[n - 1], Math.max(SEA, joinNode.water()));
		}

		List<River.Node> nodes = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			nodes.add(new River.Node(x[i], z[i], travelled[i], ground[i], water[i],
				profile.widthAt(travelled[i], length, flares), profile.depthAt(travelled[i], length, flares)));
		}
		for (River.Node node : nodes) {
			if (node.cut() > this.config.maxCut()) {
				return null; // it would have to be dug into a canyon
			}
		}
		return new River(id, kind, range.id(), List.copyOf(nodes), List.copyOf(this.falls(nodes)), ending, joined == null ? "" : joined.id(), length);
	}

	/** The lowest ground beside the channel at node {@code i}, a couple of blocks out from the water's edge, on either side. */
	private double lowestBank(double[] x, double[] z, double[] travelled, int i, double length, RiverConfig.Profile profile, boolean flares) {
		int before = Math.max(0, i - 1);
		int after = Math.min(x.length - 1, i + 1);
		double tx = x[after] - x[before];
		double tz = z[after] - z[before];
		double t = Math.hypot(tx, tz);
		if (t <= 0.0) {
			return Double.MAX_VALUE;
		}
		double reach = Math.max(Channel.MIN_HALF_WIDTH, profile.widthAt(travelled[i], length, flares) / 2.0) + BANK_PROBE;
		double nx = -tz / t * reach;
		double nz = tx / t * reach;
		// the cheap estimate says whether a bank is lower than the line at all; only then is the ground measured exactly
		double onLine = this.terrain.roughGround(x[i], z[i]);
		double lowest = Math.min(this.terrain.roughGround(x[i] + nx, z[i] + nz), this.terrain.roughGround(x[i] - nx, z[i] - nz));
		if (lowest > onLine - BANK_SCREEN) {
			return Double.MAX_VALUE;
		}
		return Math.min(this.terrain.ground(x[i] + nx, z[i] + nz), this.terrain.ground(x[i] - nx, z[i] - nz));
	}

	private static double length(List<double[]> line) {
		double length = 0.0;
		for (int i = 1; i < line.size(); i++) {
			length += Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
		}
		return length;
	}

	/** Steep reaches of the water, merged into falls and sorted into slender plunges, cascades and wide curtains. */
	private List<River.Fall> falls(List<River.Node> nodes) {
		List<River.Fall> falls = new ArrayList<>();
		int i = 0;
		while (i < nodes.size() - 1) {
			if (!isSteep(nodes.get(i), nodes.get(i + 1))) {
				i++;
				continue;
			}
			int j = i;
			while (j < nodes.size() - 1 && isSteep(nodes.get(j), nodes.get(j + 1))) {
				j++;
			}
			double drop = nodes.get(i).water() - nodes.get(j).water();
			if (drop >= this.config.fallMinDrop()) {
				River.Node lip = nodes.get(i);
				River.Fall.Style style = lip.width() >= 30 ? River.Fall.Style.CURTAIN
					: lip.width() <= 8 && drop >= 40 ? River.Fall.Style.PLUNGE : River.Fall.Style.CASCADE;
				falls.add(new River.Fall(i, j, lip.x(), lip.z(), drop, nodes.get(j).travelled() - lip.travelled(), lip.width(), style));
			}
			i = j;
		}
		return falls;
	}

	/** Does the water fall at least {@link #STEEP_SLOPE} blocks per block of river between these nodes? */
	private static boolean isSteep(River.Node from, River.Node to) {
		return from.water() - to.water() >= STEEP_SLOPE * Math.max(1.0, to.travelled() - from.travelled());
	}

	/** One round of corner cutting that keeps both ends where they are. */
	private static List<double[]> smooth(List<double[]> points) {
		if (points.size() < 3) {
			return points;
		}
		List<double[]> out = new ArrayList<>();
		out.add(points.get(0));
		for (int i = 0; i < points.size() - 1; i++) {
			double[] a = points.get(i);
			double[] b = points.get(i + 1);
			out.add(new double[] { 0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1] });
			out.add(new double[] { 0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1] });
		}
		out.add(points.get(points.size() - 1));
		return out;
	}

	/** Points every {@code spacing} blocks along the line, ending exactly on its last point. */
	private static List<double[]> resample(List<double[]> line, double spacing) {
		List<double[]> out = new ArrayList<>();
		out.add(line.get(0));
		double carried = 0.0;
		for (int i = 0; i < line.size() - 1; i++) {
			double[] a = line.get(i);
			double[] b = line.get(i + 1);
			double segment = Math.hypot(b[0] - a[0], b[1] - a[1]);
			if (segment <= 0.0) {
				continue;
			}
			double position = spacing - carried;
			while (position <= segment) {
				out.add(new double[] { a[0] + (b[0] - a[0]) * position / segment, a[1] + (b[1] - a[1]) * position / segment });
				position += spacing;
			}
			carried = segment - (position - spacing);
		}
		double[] last = line.get(line.size() - 1);
		double[] end = out.get(out.size() - 1);
		if (Math.hypot(last[0] - end[0], last[1] - end[1]) < spacing * 0.5 && out.size() > 1) {
			out.set(out.size() - 1, last);
		} else {
			out.add(last);
		}
		return out;
	}

	// ---- bookkeeping -----------------------------------------------------------------------------------------------------

	/** The water level of the rivers already planned in this range, per routing cell, so later rivers can end in them. */
	private static final class Occupancy {
		record Hit(River river) {
		}

		private final Map<Long, Double> levels = new HashMap<>();
		private final Map<Long, River> owners = new HashMap<>();

		void add(River river) {
			// no other river ends in the source area: a confluence belongs well downstream of where a river begins
			for (int i = 0; i < river.nodes().size(); i++) {
				River.Node node = river.nodes().get(i);
				if (node.travelled() < MIN_JOIN_TRAVEL) {
					continue;
				}
				long key = RiverRouter.key((int) Math.round(node.x() / RiverRouter.STEP), (int) Math.round(node.z() / RiverRouter.STEP));
				this.levels.merge(key, node.water(), Math::min);
				this.owners.putIfAbsent(key, river);
			}
		}

		Double levelAt(int gx, int gz) {
			return this.levels.get(RiverRouter.key(gx, gz));
		}

		Hit hit(int gx, int gz) {
			River owner = this.owners.get(RiverRouter.key(gx, gz));
			return owner == null ? null : new Hit(owner);
		}
	}

	private long hashOf(String id, long salt) {
		return mix(this.seed ^ mix((long) id.hashCode() * 0x9E3779B97F4A7C15L + salt));
	}

	private long hash(RangeShape range, long salt) {
		return mix(this.seed ^ mix((long) range.cellX() * 0x9E3779B97F4A7C15L + (long) range.cellZ() * 0xC2B2AE3D27D4EB4FL
			+ (long) range.slot() * 0x165667B19E3779F9L + salt));
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
