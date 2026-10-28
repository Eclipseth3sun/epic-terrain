package com.epicterrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Throwaway experiments for the "grand river" idea (a river from a range all the way to an ocean): how does vanilla generate
 * oceans and deep oceans, how far is the sea from a range, and could a channel be cut that far without absurd gorges? Not run
 * by default ({@code ./gradlew preview --tests com.epicterrain.GrandRiverProbeTest}).
 */
@Tag("preview")
class GrandRiverProbeTest {
	/** Geography grid, blocks per cell. */
	private static final int GRID = 128;
	/** Routing grid, blocks per cell. */
	private static final int STEP = 64;
	/** Continentalness below which vanilla picks an ocean biome, and below which a deep one (confirmed by {@link #oceanBands}). */
	private static final float OCEAN = -0.19f;
	private static final float DEEP = -0.455f;
	/** A route counts as arrived within this distance (blocks) of the target water body, once the ground is at sea level. */
	private static final double ARRIVE = 300;
	/** The water level is searched in buckets of this many blocks, and never goes below sea level. */
	private static final int WSTEP = 3;
	private static final double SEA = 63;
	private static final int MAX_EXPANSIONS = 250_000;
	private static final int[][] DIRS = {
		{ 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
		{ 2, 1 }, { 2, -1 }, { -2, 1 }, { -2, -1 }, { 1, 2 }, { 1, -2 }, { -1, 2 }, { -1, -2 }
	};

	private static DensityFunction fn(WorldgenHarness h, String id) {
		return h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id))).value();
	}

	/** Which vanilla biome does each continentalness select, and does anything but temperature change the answer? */
	@Test
	void oceanBands() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		float[] temps = { -0.7f, -0.3f, 0.0f, 0.4f, 0.8f };
		float[] erosions = { -0.9f, -0.3f, 0.25f, 0.6f };
		float[] weirds = { -0.7f, 0.0f, 0.7f };
		float[] humidities = { -0.5f, 0.0f, 0.5f };
		String previous = "";
		for (int i = 0; i <= 48; i++) {
			float cont = -1.2f + i * 0.025f;
			StringBuilder row = new StringBuilder();
			for (float temp : temps) {
				Set<String> names = new TreeSet<>();
				for (float erosion : erosions) {
					for (float weird : weirds) {
						for (float humidity : humidities) {
							Climate.TargetPoint p = Climate.target(temp, humidity, cont, erosion, 0.0f, weird);
							names.add(source.getNoiseBiome(p).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
						}
					}
				}
				row.append(names.size() == 1 ? names.iterator().next() : names.toString()).append(" | ");
			}
			if (!row.toString().equals(previous)) {
				System.out.printf("[grand] continentalness from %6.3f: %s%n", cont, row);
				previous = row.toString();
			}
		}
	}

	/** Draws the same patch of world twice, once from the raw continents function and once from the real biome source. */
	@Test
	void continentsVersusBiomes() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		DensityFunction continentsFn = fn(h, "minecraft:overworld/continents");
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		RandomState rs = h.randomState(4242L, settings);
		Climate.Sampler climate = rs.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		net.minecraft.world.level.biome.BiomeResolver resolver = source.createResolver(climate);
		int step = Integer.getInteger("epicterrain.step", 256);
		int cols = 110;
		int rows = 40;
		StringBuilder byFunction = new StringBuilder();
		StringBuilder byBiome = new StringBuilder();
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int x = (col - cols / 2) * step;
				int z = (row - rows / 2) * step;
				float c = rs.sampleBlockValueUncached(continentsFn, x, 0, z);
				byFunction.append(c < DEEP ? '~' : c < OCEAN ? 'o' : c < -0.11f ? ',' : c < 0.03f ? '.' : c < 0.3f ? '+' : '#');
				String name = resolver.getNoiseBiome(x >> 2, 15, z >> 2).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				byBiome.append(name.contains("deep") ? '~' : name.contains("ocean") ? 'o' : name.contains("beach") || name.contains("shore") ? ',' : name.contains("river") ? 'r' : '+');
			}
			byFunction.append('\n');
			byBiome.append('\n');
		}
		System.out.printf("[grand] continents function (%d-block cells; ~ deep, o ocean, , coast, . near inland, + mid, # far inland)%n%s%n", step, byFunction);
		System.out.printf("[grand] real biome source (~ deep ocean, o ocean, , beach/shore, r river, + other land)%n%s%n", byBiome);
	}

	private record Node(int gx, int gz, int wb, double g, double f, Node parent) {
	}

	private interface Ground {
		double at(int gx, int gz);
	}

	/** The coarse distance-to-target field plus the ground height, as one routing problem. */
	private record Field(int half, int n, float[] distance, boolean[] ocean, Ground ground) {
		/** Ocean biomes cannot carry a river: a route may only enter one at its destination. */
		boolean isOcean(int gx, int gz) {
			int col = Math.floorDiv(gx * STEP + half, GRID);
			int row = Math.floorDiv(gz * STEP + half, GRID);
			return col >= 0 && row >= 0 && col < n && row < n && ocean[row * n + col];
		}

		double distTo(int gx, int gz) {
			int col = Math.floorDiv(gx * STEP + half, GRID);
			int row = Math.floorDiv(gz * STEP + half, GRID);
			if (col < 1 || row < 1 || col >= n - 1 || row >= n - 1) {
				return Double.NaN;
			}
			return distance[row * n + col];
		}
	}

	/** A routed river: for every node, the distance travelled, the water level, the ground, and whether vanilla water is near. */
	private record Route(boolean arrived, double[] travelled, double[] water, double[] ground, boolean[] nearWater, int[] gx, int[] gz) {
	}

	private static long key(int gx, int gz) {
		return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
	}

	/** The cheapest ways to reach one cell: a state is worth keeping only if no other has both a higher water level and a lower cost. */
	private static final class Pareto {
		int[] bucket = new int[4];
		double[] cost = new double[4];
		int size;

		boolean offer(int b, double c) {
			for (int i = 0; i < size; i++) {
				if (bucket[i] >= b && cost[i] <= c) {
					return false;
				}
			}
			int keep = 0;
			for (int i = 0; i < size; i++) {
				if (!(bucket[i] <= b && cost[i] >= c)) {
					bucket[keep] = bucket[i];
					cost[keep] = cost[i];
					keep++;
				}
			}
			size = keep;
			if (size == bucket.length) {
				bucket = java.util.Arrays.copyOf(bucket, size * 2);
				cost = java.util.Arrays.copyOf(cost, size * 2);
			}
			bucket[size] = b;
			cost[size] = c;
			size++;
			return true;
		}

		boolean has(int b, double c) {
			for (int i = 0; i < size; i++) {
				if (bucket[i] == b && Math.abs(cost[i] - c) < 1e-9) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * A* where the state is the cell and the water level. The level can only fall (it follows the ground down, two blocks under it)
	 * and cannot go below sea level; the price of a step is its length times one plus {@code beta} per block of ground above the water,
	 * so the cheapest route digs little: it goes downhill into the lowlands and stays there. Vanilla water is ordinary low ground.
	 */
	private static Route route(Field f, int startGx, int startGz, double beta) {
		double h0 = f.ground().at(startGx, startGz);
		int wb0 = Math.max(0, (int) Math.floor((h0 - 2 - SEA) / WSTEP));
		Map<Long, Pareto> seen = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
		open.add(new Node(startGx, startGz, wb0, 0, 0, null));
		seen.computeIfAbsent(key(startGx, startGz), k -> new Pareto()).offer(wb0, 0);
		int expansions = 0;
		Node goal = null;
		while (!open.isEmpty()) {
			Node cur = open.poll();
			if (!seen.get(key(cur.gx(), cur.gz())).has(cur.wb(), cur.g())) {
				continue;
			}
			if (++expansions > MAX_EXPANSIONS) {
				break;
			}
			double hc = f.ground().at(cur.gx(), cur.gz());
			double dc = f.distTo(cur.gx(), cur.gz());
			if (dc <= ARRIVE && hc <= 64.5) {
				goal = cur;
				break;
			}
			double cutCur = Math.max(0, hc - (SEA + cur.wb() * WSTEP));
			for (int[] d : DIRS) {
				int nx = cur.gx() + d[0];
				int nz = cur.gz() + d[1];
				double dn = f.distTo(nx, nz);
				if (Double.isNaN(dn) || (dn > ARRIVE && f.isOcean(nx, nz))) {
					continue;
				}
				double hn = f.ground().at(nx, nz);
				int wbn = Math.max(0, Math.min(cur.wb(), (int) Math.floor((hn - 2 - SEA) / WSTEP)));
				double cutNext = Math.max(0, hn - (SEA + wbn * WSTEP));
				double g = cur.g() + Math.hypot(d[0], d[1]) * STEP * (1 + beta * 0.5 * (cutCur + cutNext));
				if (!seen.computeIfAbsent(key(nx, nz), k -> new Pareto()).offer(wbn, g)) {
					continue;
				}
				open.add(new Node(nx, nz, wbn, g, g + Math.max(0, dn - ARRIVE), cur));
			}
		}
		if (goal == null) {
			return new Route(false, null, null, null, null, null, null);
		}

		List<Node> path = new ArrayList<>();
		for (Node nd = goal; nd != null; nd = nd.parent()) {
			path.add(nd);
		}
		Collections.reverse(path);
		int count = path.size();
		double[] travelled = new double[count];
		double[] water = new double[count];
		double[] ground = new double[count];
		boolean[] nearWater = new boolean[count];
		int[][] ring = { { 4, 0 }, { -4, 0 }, { 0, 4 }, { 0, -4 }, { 3, 3 }, { 3, -3 }, { -3, 3 }, { -3, -3 } };
		for (int i = 0; i < count; i++) {
			Node nd = path.get(i);
			if (i > 0) {
				Node prev = path.get(i - 1);
				travelled[i] = travelled[i - 1] + Math.hypot(nd.gx() - prev.gx(), nd.gz() - prev.gz()) * STEP;
			}
			water[i] = SEA + nd.wb() * WSTEP;
			ground[i] = f.ground().at(nd.gx(), nd.gz());
			boolean near = ground[i] <= 65;
			for (int[] r : ring) {
				near |= f.ground().at(nd.gx() + r[0], nd.gz() + r[1]) <= 65;
			}
			nearWater[i] = near;
		}
		int[] gxs = new int[count];
		int[] gzs = new int[count];
		for (int i = 0; i < count; i++) {
			gxs[i] = path.get(i).gx();
			gzs[i] = path.get(i).gz();
		}
		return new Route(true, travelled, water, ground, nearWater, gxs, gzs);
	}

	private static int[] label(boolean[] mask, int n, List<Integer> sizes) {
		int[] label = new int[n * n];
		java.util.Arrays.fill(label, -1);
		int[] stack = new int[n * n];
		for (int start = 0; start < n * n; start++) {
			if (!mask[start] || label[start] >= 0) {
				continue;
			}
			int id = sizes.size();
			int top = 0;
			int count = 0;
			stack[top++] = start;
			label[start] = id;
			while (top > 0) {
				int i = stack[--top];
				count++;
				int x = i % n;
				int z = i / n;
				if (x > 0 && mask[i - 1] && label[i - 1] < 0) {
					label[i - 1] = id;
					stack[top++] = i - 1;
				}
				if (x < n - 1 && mask[i + 1] && label[i + 1] < 0) {
					label[i + 1] = id;
					stack[top++] = i + 1;
				}
				if (z > 0 && mask[i - n] && label[i - n] < 0) {
					label[i - n] = id;
					stack[top++] = i - n;
				}
				if (z < n - 1 && mask[i + n] && label[i + n] < 0) {
					label[i + n] = id;
					stack[top++] = i + n;
				}
			}
			sizes.add(count);
		}
		return label;
	}

	/** Chamfer distance (blocks) from every cell to the nearest set cell of the mask. */
	private static float[] distanceTo(boolean[] target, int n) {
		float inf = 1e9f;
		float s2 = (float) Math.sqrt(2);
		float[] d = new float[n * n];
		for (int i = 0; i < d.length; i++) {
			d[i] = target[i] ? 0 : inf;
		}
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int i = z * n + x;
				float v = d[i];
				if (x > 0) {
					v = Math.min(v, d[i - 1] + 1);
				}
				if (z > 0) {
					v = Math.min(v, d[i - n] + 1);
					if (x > 0) {
						v = Math.min(v, d[i - n - 1] + s2);
					}
					if (x < n - 1) {
						v = Math.min(v, d[i - n + 1] + s2);
					}
				}
				d[i] = v;
			}
		}
		for (int z = n - 1; z >= 0; z--) {
			for (int x = n - 1; x >= 0; x--) {
				int i = z * n + x;
				float v = d[i];
				if (x < n - 1) {
					v = Math.min(v, d[i + 1] + 1);
				}
				if (z < n - 1) {
					v = Math.min(v, d[i + n] + 1);
					if (x < n - 1) {
						v = Math.min(v, d[i + n + 1] + s2);
					}
					if (x > 0) {
						v = Math.min(v, d[i + n - 1] + s2);
					}
				}
				d[i] = v;
			}
		}
		for (int i = 0; i < d.length; i++) {
			d[i] *= GRID;
		}
		return d;
	}

	private static double pct(List<Double> sorted, double p) {
		if (sorted.isEmpty()) {
			return Double.NaN;
		}
		return sorted.get(Math.min(sorted.size() - 1, (int) (p * sorted.size())));
	}

	private static double share(List<Double> values, java.util.function.DoublePredicate test) {
		return values.isEmpty() ? Double.NaN : 100.0 * values.stream().filter(v -> test.test(v)).count() / values.size();
	}

	private static String km(double blocks) {
		return blocks >= 1e7 ? "none" : String.format("%.1f", blocks / 1000);
	}

	/** Cells belonging to a connected body of at least {@code km2} square kilometres. */
	private static boolean[] bodiesAtLeast(int[] label, List<Integer> sizes, double km2, double cellKm2) {
		boolean[] mask = new boolean[label.length];
		for (int i = 0; i < label.length; i++) {
			mask[i] = label[i] >= 0 && sizes.get(label[i]) * cellKm2 >= km2;
		}
		return mask;
	}

	private static String bodyCounts(List<Integer> sizes, double cellKm2) {
		StringBuilder out = new StringBuilder();
		for (double km2 : new double[] { 0.5, 1, 5, 25, 100 }) {
			out.append(String.format("%.1f+ km2: %d, ", km2, sizes.stream().filter(s -> s * cellKm2 >= km2).count()));
		}
		out.append(String.format("largest %.0f km2", sizes.stream().mapToInt(Integer::intValue).max().orElse(0) * cellKm2));
		return out.toString();
	}

	@Test
	void rangesToOceans() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("epicterrain:overworld").orElseThrow();
		DensityFunction continentsFn = fn(h, "minecraft:overworld/continents");
		DensityFunction upliftFn = fn(h, "epicterrain:range/uplift");
		DensityFunction surfaceFn = settings.noiseRouter().chunkSurfaceLevel();
		int half = Integer.getInteger("epicterrain.half", 80_000);
		int margin = Integer.getInteger("epicterrain.margin", 25_000);
		double routeMax = Integer.getInteger("epicterrain.routeMax", 30_000);
		int n = 2 * half / GRID;
		double cellKm2 = (GRID / 1000.0) * (GRID / 1000.0);
		long[] seeds = System.getProperty("epicterrain.seed") != null
			? new long[] { Long.getLong("epicterrain.seed") }
			: new long[] { 4242L, 4310335781435980812L };

		for (long seed : seeds) {
			long t0 = System.currentTimeMillis();
			RandomState rs = h.randomState(seed, settings);
			float[] cont = new float[n * n];
			float[] uplift = new float[n * n];
			IntStream.range(0, n).parallel().forEach(row -> {
				for (int col = 0; col < n; col++) {
					int x = -half + col * GRID + GRID / 2;
					int z = -half + row * GRID + GRID / 2;
					cont[row * n + col] = rs.sampleBlockValueUncached(continentsFn, x, 0, z);
					uplift[row * n + col] = rs.sampleBlockValueUncached(upliftFn, x, 0, z);
				}
			});

			// ---- oceans -----------------------------------------------------------------------------------------------------
			boolean[] ocean = new boolean[n * n];
			boolean[] deep = new boolean[n * n];
			int oceanCells = 0;
			int deepCells = 0;
			for (int i = 0; i < n * n; i++) {
				// under a range's footprint the climate is pushed to mountain values, so the epic world has no ocean biome there
				ocean[i] = cont[i] < OCEAN && uplift[i] < 3;
				deep[i] = cont[i] < DEEP && uplift[i] < 3;
				oceanCells += ocean[i] ? 1 : 0;
				deepCells += deep[i] ? 1 : 0;
			}
			List<Integer> oceanSizes = new ArrayList<>();
			int[] oceanLabel = label(ocean, n, oceanSizes);
			List<Integer> deepSizes = new ArrayList<>();
			int[] deepLabel = label(deep, n, deepSizes);
			int[] deepIn = new int[oceanSizes.size()];
			for (int i = 0; i < n * n; i++) {
				if (deep[i]) {
					deepIn[oceanLabel[i]]++;
				}
			}
			boolean[] withCore = new boolean[n * n];
			for (int i = 0; i < n * n; i++) {
				withCore[i] = ocean[i] && deepIn[oceanLabel[i]] * cellKm2 >= 2;
			}
			System.out.printf("%n[grand] seed %d, %.0f x %.0f km at %d-block cells: ocean biomes cover %.1f%% of the map, deep ocean %.1f%%%n",
				seed, 2.0 * half / 1000, 2.0 * half / 1000, GRID, 100.0 * oceanCells / (n * n), 100.0 * deepCells / (n * n));
			System.out.printf("[grand]   ocean bodies by area: %d in all; %s%n", oceanSizes.size(), bodyCounts(oceanSizes, cellKm2));
			System.out.printf("[grand]   deep-ocean bodies by area: %d in all; %s%n", deepSizes.size(), bodyCounts(deepSizes, cellKm2));

			// ---- ranges ------------------------------------------------------------------------------------------------------
			boolean[] hills = new boolean[n * n];
			for (int i = 0; i < n * n; i++) {
				hills[i] = uplift[i] >= 40;
			}
			List<Integer> rangeSizes = new ArrayList<>();
			int[] rangeLabel = label(hills, n, rangeSizes);
			String[] classNames = {
				"any ocean biome", "ocean body 5+ km2", "ocean body 10+ km2", "ocean body 25+ km2",
				"deep body 1+ km2", "deep body 2+ km2", "ocean body with 2+ km2 of deep core"
			};
			boolean[][] masks = {
				ocean, bodiesAtLeast(oceanLabel, oceanSizes, 5, cellKm2), bodiesAtLeast(oceanLabel, oceanSizes, 10, cellKm2), bodiesAtLeast(oceanLabel, oceanSizes, 25, cellKm2),
				bodiesAtLeast(deepLabel, deepSizes, 1, cellKm2), bodiesAtLeast(deepLabel, deepSizes, 2, cellKm2), withCore
			};
			int classes = masks.length;
			float[][] dist = new float[classes][];
			for (int t = 0; t < classes; t++) {
				dist[t] = distanceTo(masks[t], n);
			}
			int ranges = rangeSizes.size();
			double[][] fromSummit = new double[classes][ranges];
			double[][] fromCrest = new double[classes][ranges];
			int[] crestCell = new int[ranges];
			boolean[] inside = new boolean[ranges];
			boolean[] hasSummit = new boolean[ranges];
			java.util.Arrays.fill(inside, true);
			for (double[] row : fromSummit) {
				java.util.Arrays.fill(row, 1e12);
			}
			for (int i = 0; i < n * n; i++) {
				int r = rangeLabel[i];
				if (r < 0) {
					continue;
				}
				int x = -half + (i % n) * GRID;
				int z = -half + (i / n) * GRID;
				if (Math.abs(x) > half - margin || Math.abs(z) > half - margin) {
					inside[r] = false;
				}
				if (uplift[i] >= 100) {
					hasSummit[r] = true;
					for (int t = 0; t < classes; t++) {
						fromSummit[t][r] = Math.min(fromSummit[t][r], dist[t][i]);
					}
				}
				if (uplift[i] > uplift[crestCell[r]] || rangeLabel[crestCell[r]] != r) {
					crestCell[r] = i;
				}
			}
			List<Integer> counted = new ArrayList<>();
			for (int r = 0; r < ranges; r++) {
				if (inside[r] && hasSummit[r] && rangeSizes.get(r) * cellKm2 >= 4) {
					counted.add(r);
					for (int t = 0; t < classes; t++) {
						fromCrest[t][r] = dist[t][crestCell[r]];
					}
				}
			}
			System.out.printf("[grand]   %d ranges (hills 40+ blocks over 4+ km2, with a 100+ block summit zone) at least %d km inside the map%n", counted.size(), margin / 1000);
			for (int t = 0; t < classes; t++) {
				List<Double> near = new ArrayList<>();
				List<Double> crestD = new ArrayList<>();
				for (int r : counted) {
					near.add(fromSummit[t][r]);
					crestD.add(fromCrest[t][r]);
				}
				Collections.sort(near);
				Collections.sort(crestD);
				System.out.printf("[grand]   distance from a range's summit zone to %-36s: p10 %5s, median %5s, p90 %5s km | within 5/10/20/40 km: %.0f/%.0f/%.0f/%.0f%% | from the crest: median %s km%n",
					classNames[t], km(pct(near, 0.1)), km(pct(near, 0.5)), km(pct(near, 0.9)),
					share(near, v -> v <= 5000), share(near, v -> v <= 10_000), share(near, v -> v <= 20_000), share(near, v -> v <= 40_000), km(pct(crestD, 0.5)));
			}

			// ---- routes ------------------------------------------------------------------------------------------------------
			ConcurrentHashMap<Long, Double> cache = new ConcurrentHashMap<>();
			Ground ground = (gx, gz) -> cache.computeIfAbsent(key(gx, gz), k -> (double) rs.sampleBlockValueUncached(surfaceFn, gx * STEP, 0, gz * STEP));
			int[] routeClasses = { 2, 3 };
			double[] betas = { 0.05, 0.25 };
			WorldPreset epicPreset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
				.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:epic"))).value();
			net.minecraft.world.level.biome.BiomeResolver resolver = epicPreset.overworld().orElseThrow().generator().getBiomeSource()
				.createResolver(rs.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
			java.util.function.BiFunction<Integer, Integer, String> classify = (gx, gz) -> {
				String name = resolver.getNoiseBiome((gx * STEP) >> 2, 15, (gz * STEP) >> 2).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				return name.contains("river") ? "river" : name.contains("ocean") ? "ocean" : name.contains("beach") || name.contains("shore") ? "shore"
					: name.contains("swamp") || name.contains("mangrove") ? "wetland" : name.contains("foothill") || name.contains("peaks") ? "epic" : "other land";
			};
			java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(4);
			for (int t : routeClasses) {
				List<Integer> targets = new ArrayList<>();
				for (int r : counted) {
					if (fromSummit[t][r] <= routeMax) {
						targets.add(r);
					}
				}
				int[] edgeStarts = new int[targets.size()];
				int[] crestStarts = new int[targets.size()];
				for (int k = 0; k < targets.size(); k++) {
					int r = targets.get(k);
					int best = -1;
					for (int pass = 0; pass < 2 && best < 0; pass++) {
						for (int i = 0; i < n * n; i++) {
							if (rangeLabel[i] == r && (uplift[i] >= 60 || pass == 1) && (best < 0 || dist[t][i] < dist[t][best])) {
								best = i;
							}
						}
					}
					edgeStarts[k] = best;
					crestStarts[k] = crestCell[r];
				}
				Field field = new Field(half, n, dist[t], ocean, ground);
				for (int[] starts : new int[][] { edgeStarts, crestStarts }) {
					String sourceName = starts == edgeStarts ? "the summit-zone edge nearest the sea" : "the range crest";
					for (double beta : betas) {
						Route[] routes = new Route[targets.size()];
						try {
							pool.submit(() -> IntStream.range(0, targets.size()).parallel().forEach(k -> {
								int x = -half + (starts[k] % n) * GRID + GRID / 2;
								int z = -half + (starts[k] / n) * GRID + GRID / 2;
								routes[k] = route(field, Math.floorDiv(x, STEP), Math.floorDiv(z, STEP), beta);
							})).get();
						} catch (Exception e) {
							throw new IllegalStateException(e);
						}
						report(classNames[t], sourceName, beta, routeMax, targets.size(), routes, classify);
					}
				}
			}
			pool.shutdown();
			System.out.printf("[grand]   (%.0fs)%n", (System.currentTimeMillis() - t0) / 1000.0);
		}
	}

	private static void report(String className, String sourceName, double beta, double routeMax, int attempted, Route[] routes,
		java.util.function.BiFunction<Integer, Integer, String> classify) {
		Map<String, Double> lowByBiome = new java.util.TreeMap<>();
		double lowTotal = 0;
		List<Double> total = new ArrayList<>();
		List<Double> untilSea = new ArrayList<>();
		List<Double> until12 = new ArrayList<>();
		List<Double> until30 = new ArrayList<>();
		List<Double> seaShare = new ArrayList<>();
		List<Double> waterShare = new ArrayList<>();
		List<Double> waterShareLow = new ArrayList<>();
		List<Double> nearShareLow = new ArrayList<>();
		List<Double> highCuts = new ArrayList<>();
		List<Double> lowCuts = new ArrayList<>();
		List<Double> deepest = new ArrayList<>();
		List<Double> drops = new ArrayList<>();
		List<Double> dropsPerRoute = new ArrayList<>();
		List<Double> startGround = new ArrayList<>();
		double[] slopes = { 1.5, 3, 6 };
		List<List<Double>> fills = new ArrayList<>();
		List<List<Double>> cutsE = new ArrayList<>();
		for (int s = 0; s < slopes.length; s++) {
			fills.add(new ArrayList<>());
			cutsE.add(new ArrayList<>());
		}
		int arrived = 0;
		for (Route route : routes) {
			if (!route.arrived()) {
				continue;
			}
			arrived++;
			double[] d = route.travelled();
			double[] w = route.water();
			double[] g = route.ground();
			int last = d.length - 1;
			double length = d[last];
			int sea = last;
			int at12 = last;
			int at30 = last;
			for (int i = last; i >= 0; i--) {
				if (w[i] <= SEA + 2) {
					sea = i;
				}
				if (w[i] <= SEA + 12) {
					at12 = i;
				}
				if (w[i] <= SEA + 30) {
					at30 = i;
				}
			}
			total.add(length / 1000);
			untilSea.add(d[sea] / 1000);
			until12.add(d[at12] / 1000);
			until30.add(d[at30] / 1000);
			seaShare.add(length <= 0 ? 0 : 100.0 * (length - d[sea]) / length);
			double onWater = 0;
			double onWaterLow = 0;
			int nearLow = 0;
			int lowNodes = 0;
			double deepestCut = 0;
			int dropCount = 0;
			for (int i = 1; i <= last; i++) {
				double segment = d[i] - d[i - 1];
				if (g[i] <= 65) {
					onWater += segment;
					if (i >= sea) {
						onWaterLow += segment;
					}
				}
				if (i >= sea) {
					lowNodes++;
					nearLow += route.nearWater()[i] ? 1 : 0;
					lowTotal += segment;
					lowByBiome.merge(classify.apply(route.gx()[i], route.gz()[i]), segment, Double::sum);
				}
				double cut = Math.max(0, g[i] - w[i]);
				(i < sea ? highCuts : lowCuts).add(cut);
				deepestCut = Math.max(deepestCut, cut);
				if (w[i - 1] - w[i] >= 12) {
					dropCount++;
					drops.add(w[i - 1] - w[i]);
				}
			}
			waterShare.add(length <= 0 ? 0 : 100.0 * onWater / length);
			if (length - d[sea] > 0) {
				waterShareLow.add(100.0 * onWaterLow / (length - d[sea]));
			}
			if (lowNodes > 0) {
				nearShareLow.add(100.0 * nearLow / lowNodes);
			}
			deepest.add(deepestCut);
			dropsPerRoute.add((double) dropCount);
			startGround.add(g[0]);

			// an elevated river instead: the water leaves the flank where the ground meets a design slope (blocks of fall per km of
			// river) and follows it to the sea, so the ground along it must be raised wherever it sits below the water
			for (int s = 0; s < slopes.length; s++) {
				double runMin = Double.MAX_VALUE;
				double previous = Double.MAX_VALUE;
				boolean onDesign = false;
				for (int i = 0; i <= last; i++) {
					runMin = Math.min(runMin, g[i] - 2);
					double design = SEA + slopes[s] * (length - d[i]) / 1000.0;
					onDesign |= g[i] - 2 <= design;
					double level = Math.min(previous, onDesign ? design : runMin);
					previous = level;
					if (onDesign) {
						fills.get(s).add(Math.max(0, level + 1 - g[i]));
						cutsE.get(s).add(Math.max(0, g[i] - level));
					}
				}
			}
		}
		for (List<Double> list : List.of(total, untilSea, until12, until30, seaShare, waterShare, waterShareLow, nearShareLow, highCuts, lowCuts, deepest, drops, dropsPerRoute, startGround)) {
			Collections.sort(list);
		}
		System.out.printf("[grand]   ROUTES from %s to %s within %.0f km, digging charge %.2f/block: %d attempted, %d arrived%n",
			sourceName, className, routeMax / 1000, beta, attempted, arrived);
		if (arrived == 0) {
			return;
		}
		System.out.printf("[grand]     total length km: p10 %.1f, median %.1f, p90 %.1f | source ground y: median %.0f%n",
			pct(total, 0.1), pct(total, 0.5), pct(total, 0.9), pct(startGround, 0.5));
		System.out.printf("[grand]     LENGTH UNTIL THE WATER IS WITHIN 30 / 12 / 2 BLOCKS OF SEA LEVEL (km): median %.1f / %.1f / %.1f; p90 %.1f / %.1f / %.1f%n",
			pct(until30, 0.5), pct(until12, 0.5), pct(untilSea, 0.5), pct(until30, 0.9), pct(until12, 0.9), pct(untilSea, 0.9));
		System.out.printf("[grand]     share of the length spent at sea level (the low reach): median %.0f%%, p10 %.0f%%, p90 %.0f%%%n",
			pct(seaShare, 0.5), pct(seaShare, 0.1), pct(seaShare, 0.9));
		System.out.printf("[grand]     share of the length already inside vanilla water: median %.0f%% overall, %.0f%% in the low reach | low reach with vanilla water within ~250 blocks: median %.0f%% of nodes%n",
			pct(waterShare, 0.5), pct(waterShareLow, 0.5), pct(nearShareLow, 0.5));
		StringBuilder biomes = new StringBuilder();
		for (Map.Entry<String, Double> e : lowByBiome.entrySet()) {
			biomes.append(String.format("%s %.0f%%, ", e.getKey(), 100.0 * e.getValue() / Math.max(1, lowTotal)));
		}
		System.out.printf("[grand]     what the low reach runs through (by length, all routes): %s%n", biomes);
		System.out.printf("[grand]     digging: high reach median %.0f, p90 %.0f | low reach median %.0f, p90 %.0f, share deeper than 15/30: %.0f/%.0f%% | deepest gorge per route: median %.0f, p90 %.0f; at most 15/30/60: %.0f/%.0f/%.0f%% of routes%n",
			pct(highCuts, 0.5), pct(highCuts, 0.9), pct(lowCuts, 0.5), pct(lowCuts, 0.9), share(lowCuts, v -> v > 15), share(lowCuts, v -> v > 30),
			pct(deepest, 0.5), pct(deepest, 0.9), share(deepest, v -> v <= 15), share(deepest, v -> v <= 30), share(deepest, v -> v <= 60));
		System.out.printf("[grand]     falls of 12+ blocks between 64-block steps: median %.0f per route (single drops median %.0f, p90 %.0f)%n",
			pct(dropsPerRoute, 0.5), pct(drops, 0.5), pct(drops, 0.9));
		if (sourceName.contains("crest") && beta < 0.1) {
			for (int s = 0; s < slopes.length; s++) {
				List<Double> fill = fills.get(s);
				List<Double> cut = cutsE.get(s);
				Collections.sort(fill);
				Collections.sort(cut);
				System.out.printf("[grand]     ELEVATED at %.1f blocks of fall per km (after leaving the flank): ground to raise below the water: median %.0f, p90 %.0f blocks; more than 5/15/30 blocks of raising on %.0f/%.0f/%.0f%% of the length; ground to cut: more than 15/30 on %.0f/%.0f%%%n",
					slopes[s], pct(fill, 0.5), pct(fill, 0.9), share(fill, v -> v > 5), share(fill, v -> v > 15), share(fill, v -> v > 30), share(cut, v -> v > 15), share(cut, v -> v > 30));
			}
		}
	}
}
