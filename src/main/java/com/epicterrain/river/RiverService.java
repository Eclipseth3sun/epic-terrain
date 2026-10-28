package com.epicterrain.river;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import com.epicterrain.worldgen.RangeCatalog;
import com.epicterrain.worldgen.RangeShape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the world asks about rivers, answered from plans that are computed once, on background threads, and kept.
 *
 * <p>A river is planned per range, and what a column needs to know ("is a river here?") depends on every range whose rivers could
 * reach it: ordinary ranges within a couple of cells, plus the rare ranges that rolled a grand river within the grand reach. That
 * set is fixed by geometry (see {@code RiverConfig.maxStray}), so the answer never depends on the order in which chunks are generated.
 *
 * <p>Plans are produced by {@link #prefetch}, which a server calls for the area around each player so that by the time chunks near a
 * range are generated its plan is ready and generation only reads from the index. {@link #ensureReady} is the safety net for a chunk
 * that gets ahead of the prefetch: it waits for exactly the plans it needs.
 */
public final class RiverService {
	private static final Logger LOGGER = LoggerFactory.getLogger(RiverService.class);
	/** Cell size of the lookup grid, in blocks. */
	private static final int GRID = 256;
	/** How far from a range's centre its rivers can reach, beyond the cells it covers: the spine, the foothills and the corridor. */
	private static final double RANGE_EXTENT = 4000.0 + 1100.0 + Channel.MAX_RADIUS;

	/** What the rivers do to one column. */
	public record ColumnRiver(River river, int segment, double along, double lateral, double halfWidth, double water, double rimWater, double depth, double radius) {
		public River.Kind kind() {
			return this.river.kind();
		}
	}

	private record Segment(River river, int index) {
		ColumnRiver project(double x, double z) {
			River.Node a = this.river.nodes().get(this.index);
			River.Node b = this.river.nodes().get(this.index + 1);
			double dx = b.x() - a.x();
			double dz = b.z() - a.z();
			double length2 = dx * dx + dz * dz;
			double t = length2 <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - a.x()) * dx + (z - a.z()) * dz) / length2));
			double lateral = Math.hypot(x - (a.x() + dx * t), z - (a.z() + dz * t));
			double radius = lerp(Channel.radius(a), Channel.radius(b), t);
			if (lateral > radius) {
				return null;
			}
			double water = lerp(a.water(), b.water(), t);
			return new ColumnRiver(this.river, this.index, lerp(a.travelled(), b.travelled(), t), lateral, lerp(Channel.halfWidth(a), Channel.halfWidth(b), t),
				water, water, lerp(a.depth(), b.depth(), t), radius);
		}
	}

	private final RiverPlanner planner;
	private final RangeCatalog catalog;
	private final Executor pool;
	private final int tributaryCells;
	private final int grandCells;
	private final ConcurrentHashMap<Long, List<RangeShape>> shapes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CompletableFuture<RangeRivers>> plans = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, CopyOnWriteArrayList<Segment>> grid = new ConcurrentHashMap<>();
	private final Set<Long> readyCells = ConcurrentHashMap.newKeySet();
	/** For each cell, one future that completes when every plan a point in it depends on is done; built once, on first ask. */
	private final ConcurrentHashMap<Long, CompletableFuture<Void>> cellPlans = new ConcurrentHashMap<>();
	private final AtomicInteger started = new AtomicInteger();
	private final AtomicInteger finished = new AtomicInteger();

	public RiverService(RiverPlanner planner, RangeCatalog catalog, Executor pool) {
		this.planner = planner;
		this.catalog = catalog;
		this.pool = pool;
		RiverConfig config = planner.config();
		double cell = catalog.cellSize();
		// a tributary stays within maxStray of its source, which is within RANGE_EXTENT of its range's centre; a grand river's route
		// within GRAND_ROUTE_FACTOR * grandReach of its source; and the bends add to either as far as they can reach
		this.tributaryCells = (int) Math.ceil((config.maxStray() + RANGE_EXTENT + Meander.MAX_REACH) / cell);
		this.grandCells = (int) Math.ceil((config.grandReach() * RiverPlanner.GRAND_ROUTE_FACTOR + RANGE_EXTENT + Meander.MAX_REACH) / cell);
	}

	public RiverPlanner planner() {
		return this.planner;
	}

	public int plansStarted() {
		return this.started.get();
	}

	public int plansFinished() {
		return this.finished.get();
	}

	/** The plans that are done so far. */
	public List<RangeRivers> finishedPlans() {
		List<RangeRivers> done = new ArrayList<>();
		for (CompletableFuture<RangeRivers> plan : this.plans.values()) {
			if (plan.isDone() && !plan.isCompletedExceptionally()) {
				done.add(plan.join());
			}
		}
		return done;
	}

	// ---- planning ----------------------------------------------------------------------------------------------------

	private int cellOf(double blocks) {
		return Math.floorDiv((int) Math.floor(blocks), this.catalog.cellSize());
	}

	private List<RangeShape> shapesOf(int cellX, int cellZ) {
		return this.shapes.computeIfAbsent(RiverRouter.key(cellX, cellZ), k -> this.catalog.shapesInCell(cellX, cellZ));
	}

	private CompletableFuture<RangeRivers> planOf(RangeShape shape) {
		return this.plans.computeIfAbsent(shape.id(), id -> {
			this.started.incrementAndGet();
			return CompletableFuture.supplyAsync(() -> this.planAndIndex(shape), this.pool);
		});
	}

	private RangeRivers planAndIndex(RangeShape shape) {
		RangeRivers plan;
		try {
			plan = this.planner.plan(shape);
		} catch (RuntimeException | StackOverflowError e) {
			// a chunk must still generate: better a range without rivers than a stuck world
			LOGGER.error("Could not plan the rivers of range {}", shape.id(), e);
			plan = new RangeRivers(shape, List.of(), false, 0, 0);
		}
		for (River river : plan.rivers()) {
			this.index(river);
			if (river.kind() == River.Kind.GRAND) {
				// where to go to see it: the one thing a tester cannot work out from inside the game
				LOGGER.info("Grand river of range {}: source at {}, {}, mouth at {}, {} ({} blocks long, {} wide at the mouth)", plan.range().id(),
					Math.round(river.source().x()), Math.round(river.source().z()), Math.round(river.mouth().x()), Math.round(river.mouth().z()),
					Math.round(river.length()), Math.round(river.mouth().width()));
			}
		}
		this.finished.incrementAndGet();
		return plan;
	}

	/** The plans a point in this cell depends on: all ranges nearby, plus the nearby ranges that rolled a grand river. */
	private List<CompletableFuture<RangeRivers>> dependencies(int cellX, int cellZ) {
		List<CompletableFuture<RangeRivers>> needed = new ArrayList<>();
		for (int dx = -this.grandCells; dx <= this.grandCells; dx++) {
			for (int dz = -this.grandCells; dz <= this.grandCells; dz++) {
				boolean near = Math.abs(dx) <= this.tributaryCells && Math.abs(dz) <= this.tributaryCells;
				for (RangeShape shape : this.shapesOf(cellX + dx, cellZ + dz)) {
					if (near || this.planner.rollsGrand(shape)) {
						needed.add(this.planOf(shape));
					}
				}
			}
		}
		return needed;
	}

	/** The plans a point in this cell depends on, as one future. The (cheap but not free) walk over the neighbourhood happens once per cell. */
	private CompletableFuture<Void> plansOf(int cellX, int cellZ) {
		return this.cellPlans.computeIfAbsent(RiverRouter.key(cellX, cellZ),
			k -> CompletableFuture.allOf(this.dependencies(cellX, cellZ).toArray(new CompletableFuture[0])));
	}

	/**
	 * Makes sure everything that can affect this point is planned. With {@code block} it waits for the plans; without, it only starts
	 * them and returns whether they are all done already.
	 */
	public boolean ensureReady(double x, double z, boolean block) {
		int cellX = this.cellOf(x);
		int cellZ = this.cellOf(z);
		long key = RiverRouter.key(cellX, cellZ);
		if (this.readyCells.contains(key)) {
			return true;
		}
		CompletableFuture<Void> plans = this.plansOf(cellX, cellZ);
		if (block) {
			plans.join();
		} else if (!plans.isDone()) {
			return false;
		}
		this.readyCells.add(key);
		return true;
	}

	/**
	 * Is everything that can affect this point already planned? Never starts any planning, so it is safe for a thread that must not add
	 * load or wait (a command searching the world), which then simply uses the plans that exist.
	 */
	public boolean isReady(double x, double z) {
		long key = RiverRouter.key(this.cellOf(x), this.cellOf(z));
		if (this.readyCells.contains(key)) {
			return true;
		}
		CompletableFuture<Void> plans = this.cellPlans.get(key);
		if (plans != null && plans.isDone()) {
			this.readyCells.add(key);
			return true;
		}
		return false;
	}

	/** Starts planning everything the area around a point needs, without waiting. Cheap to call again and again. */
	public void prefetch(double x, double z) {
		this.plansOf(this.cellOf(x), this.cellOf(z));
	}

	// ---- the index -----------------------------------------------------------------------------------------------------

	private void index(River river) {
		List<River.Node> nodes = river.nodes();
		for (int i = 0; i < nodes.size() - 1; i++) {
			River.Node a = nodes.get(i);
			River.Node b = nodes.get(i + 1);
			double reach = Math.max(Channel.radius(a), Channel.radius(b));
			int x0 = (int) Math.floor((Math.min(a.x(), b.x()) - reach) / GRID);
			int x1 = (int) Math.floor((Math.max(a.x(), b.x()) + reach) / GRID);
			int z0 = (int) Math.floor((Math.min(a.z(), b.z()) - reach) / GRID);
			int z1 = (int) Math.floor((Math.max(a.z(), b.z()) + reach) / GRID);
			Segment segment = new Segment(river, i);
			for (int gx = x0; gx <= x1; gx++) {
				for (int gz = z0; gz <= z1; gz++) {
					this.grid.computeIfAbsent(RiverRouter.key(gx, gz), k -> new CopyOnWriteArrayList<>()).add(segment);
				}
			}
		}
	}

	/**
	 * The river that governs a column, or null if none reaches it. Where several do, the one whose channel the column is closest to
	 * (or most inside) wins, which makes a grand river take priority over a tributary within its own corridor. Only plans that are
	 * already done are consulted: call {@link #ensureReady} first.
	 */
	public ColumnRiver at(double x, double z) {
		List<Segment> segments = this.grid.get(RiverRouter.key((int) Math.floor(x / GRID), (int) Math.floor(z / GRID)));
		if (segments == null) {
			return null;
		}
		ColumnRiver best = null;
		double bestScore = Double.MAX_VALUE;
		for (Segment segment : segments) {
			ColumnRiver candidate = segment.project(x, z);
			if (candidate == null) {
				continue;
			}
			double score = candidate.lateral() - candidate.halfWidth();
			if (best == null || score < bestScore - 1.0e-9 || (Math.abs(score - bestScore) <= 1.0e-9 && candidate.river().id().compareTo(best.river().id()) < 0)) {
				best = candidate;
				bestScore = score;
			}
		}
		return best == null ? null : this.withRim(best);
	}

	/** Adds the river's water level a few blocks upstream, which is what the bank has to hold back on a steep reach. */
	private ColumnRiver withRim(ColumnRiver column) {
		List<River.Node> nodes = column.river().nodes();
		double target = Math.max(0.0, column.along() - Channel.RIM_LOOKBACK);
		int i = Math.min(column.segment(), nodes.size() - 2);
		while (i > 0 && nodes.get(i).travelled() > target) {
			i--;
		}
		River.Node a = nodes.get(i);
		River.Node b = nodes.get(i + 1);
		double span = b.travelled() - a.travelled();
		double rim = span <= 0 ? a.water() : lerp(a.water(), b.water(), Math.max(0.0, Math.min(1.0, (target - a.travelled()) / span)));
		return new ColumnRiver(column.river(), column.segment(), column.along(), column.lateral(), column.halfWidth(), column.water(),
			Math.max(column.water(), rim), column.depth(), column.radius());
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
