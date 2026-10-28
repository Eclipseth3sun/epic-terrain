package com.epicterrain.earth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.epicterrain.earth.Tectonics.Boundary;
import com.epicterrain.earth.Tectonics.Contact;
import com.epicterrain.earth.Tectonics.Plate;

/**
 * Islands: land that stands alone in the sea, never on a continent. They come from the same tectonic skeleton as everything else, and what
 * drives the mountains on one is what drives them on Earth:
 * <ul>
 *   <li><b>Hotspot chains</b> (Hawaii). Some oceanic plates have a plume of hot rock under them. The plate slides over it, so the plume
 *       builds a volcano, the plate carries that away and the plume builds another: a line of islands trailing back along the plate's motion,
 *       the youngest at the plume. The first is a young, tall, unworn {@link Kind#VOLCANO} with a crater; the next ones are older, lower and
 *       cut into radial ridges and valleys ({@link Kind#OLD_VOLCANO}); the oldest have worn down and sunk to a ring of coral and a lagoon
 *       ({@link Kind#ATOLL}).</li>
 *   <li><b>Island arcs</b> (Aleutians, the Marianas). Where two oceanic plates converge, the one that rides over the other grows a line
 *       of volcanoes parallel to the boundary, a little way back from it.</li>
 *   <li><b>Mushroom islands</b>: low, gentle, isolated islands in the deepest water, at about the rate vanilla has them, with no volcano
 *       behind them (the biome says what lives there). Their biome is picked by vanilla's table at continentalness below -1.05.</li>
 *   <li><b>Fragments</b>: small hilly pieces of continental crust standing in the seas on a plate; these come from a simulated window.</li>
 * </ul>
 * Everything in the open ocean is a pure function of the seed and position, so a chunk anywhere can ask. An island is a height above sea
 * level that rises out of whatever floor is under it, and it is exactly the floor beyond its footprint.
 */
public final class Islands {
	public enum Kind {
		MUSHROOM, VOLCANO, OLD_VOLCANO, ATOLL, FRAGMENT
	}

	/** What made it. */
	public enum Origin {
		HOTSPOT, ARC, MYSTERY, SEA
	}

	/**
	 * @param radius    of the shoreline, in blocks (of the ring, for an atoll)
	 * @param crest     height of the top above sea level, blocks
	 * @param age       0 for a young volcano to 1 for one worn almost flat
	 * @param dirX      the way the island is stretched (along its chain or its arc), a unit vector
	 * @param chain     position in the hotspot chain, 0 at the plume; -1 if not in one
	 * @param chainSize how many islands the chain has
	 */
	public record Island(Kind kind, Origin origin, long key, double x, double z, double radius, double crest, double age, double dirX, double dirZ, double elongation, int chain,
			int chainSize) {
		/** The furthest from the centre that the island changes the sea floor. */
		public double footprint() {
			return this.elongation * (this.radius + width(this.kind, this.radius)) / 0.85;
		}
	}

	/**
	 * @param mushroom islands per 1,000 km2 of open ocean that qualifies. Vanilla has about one island of a fifth of a square kilometre or
	 *                 more per 260 km2 of its ocean (3.85 per 1,000 km2); some candidates are lost to the room the volcanic islands take and
	 *                 to the clear water they need, so 4.1 comes out at about that
	 * @param volcanic multiplies the chance of a hotspot on an oceanic plate and of a volcano on an arc
	 * @param size     multiplies the radius of every island
	 */
	public record Dials(double mushroom, double volcanic, double size) {
		public static Dials defaults() {
			return new Dials(4.1, 1.0, 1.0);
		}

		public static Dials fromProperties() {
			Dials d = defaults();
			return new Dials(option("mushroom", d.mushroom()), option("volcanic", d.volcanic()), option("islandSize", d.size()));
		}

		private static double option(String key, double fallback) {
			String value = System.getProperty("epicterrain.earth." + key);
			return value == null ? fallback : Double.parseDouble(value);
		}
	}

	/** The cells mushroom candidates and arc candidates are laid out on, in blocks. */
	static final double MUSHROOM_CELL = 6_000.0;
	static final double ARC_CELL = 2_400.0;
	/** How much sea with no continental crust a mushroom island needs around it, and a volcanic island. */
	static final double MUSHROOM_CLEAR = 6_000.0;
	static final double VOLCANIC_CLEAR = 2_500.0;
	/** Clear water around a fragment in a sea on a plate, between its shore and the land. */
	static final double SEA_CLEAR = 1_200.0;
	/** The floor of the open ocean over oceanic crust, blocks above sea level (negative). */
	public static final double OCEAN_FLOOR = Continent.blocksAbove(-3500.0);
	private static final double NEVER = Double.NEGATIVE_INFINITY;
	/** A lookup result each thread reuses. */
	private static final ThreadLocal<PlateGrid.Hit> HIT = ThreadLocal.withInitial(PlateGrid.Hit::new);

	private final Tectonics tec;
	private final long seed;
	private final Dials dials;
	private final Map<Long, List<Island>> chainsRaw = new ConcurrentHashMap<>();
	private final Map<Long, List<Island>> chains = new ConcurrentHashMap<>();
	private final Map<Long, List<Island>> arcsRaw = new ConcurrentHashMap<>();
	private final Map<Long, List<Island>> arcs = new ConcurrentHashMap<>();
	private final Map<Long, List<Island>> mushrooms = new ConcurrentHashMap<>();

	public Islands(Tectonics tec, Dials dials) {
		this.tec = tec;
		this.seed = tec.config().seed() * 0x9E3779B97F4A7C15L + 0x15L;
		this.dials = dials;
	}

	private static long key(int a, int b) {
		return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
	}

	/** Metres of sea floor over which an island's flank falls from its shore to the floor under it, in blocks. */
	static double width(Kind kind, double radius) {
		return switch (kind) {
			case MUSHROOM, VOLCANO -> 2_000.0;
			case OLD_VOLCANO -> 1_800.0;
			case ATOLL -> 1_000.0;
			case FRAGMENT -> 1_200.0;
		};
	}

	// ---- where there is room ----------------------------------------------------------------------------------------------

	private boolean oceanic(double x, double z) {
		return !this.tec.plateAt(x, z, HIT.get()).continental();
	}

	/** Oceanic crust at the point and, in every direction, as far as {@code radius}: sea with no continent near. */
	boolean clear(double x, double z, double radius) {
		if (!this.oceanic(x, z)) {
			return false;
		}
		for (int k = 0; k < 16; k++) {
			double a = k * Math.PI / 8.0;
			double c = Math.cos(a);
			double s = Math.sin(a);
			if (!this.oceanic(x + c * radius, z + s * radius) || !this.oceanic(x + c * radius * 0.5, z + s * radius * 0.5)) {
				return false;
			}
		}
		return true;
	}

	/** Open ocean that a mushroom island could stand in. */
	public boolean qualifies(double x, double z) {
		return this.clear(x, z, MUSHROOM_CLEAR);
	}

	private static boolean overlap(Island a, Island b, double margin) {
		return Math.hypot(a.x() - b.x(), a.z() - b.z()) < a.radius() + b.radius() + margin;
	}

	// ---- hotspot chains -----------------------------------------------------------------------------------------------------

	private List<Island> chainRaw(int i, int j) {
		return this.chainsRaw.computeIfAbsent(key(i, j), k -> this.buildChain(i, j));
	}

	private List<Island> buildChain(int i, int j) {
		Plate plate = this.tec.plate(i, j);
		if (plate.continental() || Noise.unit(this.seed, i, j, 701) >= 0.40 * this.dials.volcanic()) {
			return List.of();
		}
		PlateGrid.Hit hit = new PlateGrid.Hit();
		double px = 0.0;
		double pz = 0.0;
		boolean found = false;
		for (int attempt = 0; attempt < 8 && !found; attempt++) {
			double angle = 2.0 * Math.PI * Noise.unit(this.seed, i, j, 710 + attempt);
			double reach = (0.05 + 0.30 * Noise.unit(this.seed, i, j, 720 + attempt)) * this.tec.config().plateBlocks();
			px = plate.x() + Math.cos(angle) * reach;
			pz = plate.z() + Math.sin(angle) * reach;
			this.tec.grid.lookup(px, pz, hit);
			found = hit.i == i && hit.j == j && hit.distance >= 4_000.0 && this.clear(px, pz, VOLCANIC_CLEAR);
		}
		if (!found) {
			return List.of();
		}
		double speed = Math.hypot(plate.vx(), plate.vz());
		double dx = -plate.vx() / speed; // the chain trails back along the plate's motion
		double dz = -plate.vz() / speed;
		int size = 4 + (int) (5.0 * Noise.unit(this.seed, i, j, 730));
		double r0 = (900.0 + 600.0 * Noise.unit(this.seed, i, j, 731)) * this.dials.size();
		List<Island> out = new ArrayList<>();
		double along = 0.0;
		for (int k = 0; k < size; k++) {
			double u = Noise.unit(this.seed, i, j, 740 + k);
			double v = Noise.unit(this.seed, i, j, 760 + k);
			if (k > 0) {
				along += (2_300.0 + 1_300.0 * u) * this.dials.size();
			}
			double lateral = (v - 0.5) * 700.0;
			double x = px + dx * along - dz * lateral;
			double z = pz + dz * along + dx * lateral;
			double radius = r0 * Math.pow(0.80, k) * (0.9 + 0.2 * v);
			Kind kind;
			double age;
			if (k == 0) {
				kind = Kind.VOLCANO;
				age = 0.10 + 0.04 * u;
			} else if (k == 1) {
				kind = u < 0.7 ? Kind.VOLCANO : Kind.OLD_VOLCANO;
				age = kind == Kind.VOLCANO ? 0.30 : 0.55;
			} else if (k == 2 || (k == 3 && u >= 0.35)) {
				kind = Kind.OLD_VOLCANO;
				age = 0.62 + 0.10 * k + 0.04 * u; // older with every step down the chain
			} else {
				kind = Kind.ATOLL;
				age = 1.0;
			}
			double crest;
			if (kind == Kind.VOLCANO) {
				crest = Noise.clamp(0.20 * radius * (0.85 + 0.30 * v), 60.0, 380.0);
			} else if (kind == Kind.OLD_VOLCANO) {
				crest = Noise.clamp(0.11 * radius * (0.9 + 0.2 * u), 25.0, 170.0);
			} else {
				radius = Noise.clamp(0.70 * r0 * (0.9 + 0.2 * u), 450.0 * this.dials.size(), 1_100.0 * this.dials.size());
				crest = 7.0;
			}
			if (!this.clear(x, z, VOLCANIC_CLEAR)) {
				continue; // this one would have stood by a continent
			}
			double elongation = kind == Kind.ATOLL ? 1.0 : 1.0 + 0.30 * Noise.unit(this.seed, i, j, 780 + k);
			out.add(new Island(kind, Origin.HOTSPOT, Noise.hash(this.seed, key(i, j), k), x, z, radius, crest, age, dx, dz, elongation, k, size));
		}
		return out;
	}

	/** A chain with the islands that overlap a chain of an earlier plate left out. */
	private List<Island> chainFinal(int i, int j) {
		return this.chains.computeIfAbsent(key(i, j), k -> {
			List<Island> raw = this.chainRaw(i, j);
			if (raw.isEmpty()) {
				return raw;
			}
			List<Island> out = new ArrayList<>();
			for (Island m : raw) {
				boolean dropped = false;
				for (int di = -2; di <= 2 && !dropped; di++) {
					for (int dj = -2; dj <= 2 && !dropped; dj++) {
						int qi = i + di;
						int qj = j + dj;
						if (qi < i || (qi == i && qj < j)) {
							for (Island o : this.chainRaw(qi, qj)) {
								dropped |= overlap(m, o, 1_500.0);
							}
						}
					}
				}
				if (!dropped) {
					out.add(m);
				}
			}
			return out;
		});
	}

	// ---- island arcs --------------------------------------------------------------------------------------------------------

	private List<Island> arcRaw(int ci, int cj) {
		return this.arcsRaw.computeIfAbsent(key(ci, cj), k -> {
			double x = (ci + 0.2 + 0.6 * Noise.unit(this.seed, ci, cj, 801)) * ARC_CELL;
			double z = (cj + 0.2 + 0.6 * Noise.unit(this.seed, ci, cj, 802)) * ARC_CELL;
			PlateGrid.Hit hit = new PlateGrid.Hit();
			Plate a = this.tec.plateAt(x, z, hit);
			if (a.continental() || Math.abs(hit.distance - 800.0) > 900.0) {
				return List.of();
			}
			Plate b = this.tec.plate(hit.ni, hit.nj);
			Contact contact = this.tec.contact(a, b);
			if (contact.type() != Boundary.ISLAND_ARC || !contact.firstOverrides() || Noise.unit(this.seed, ci, cj, 803) >= 0.60 * this.dials.volcanic()) {
				return List.of();
			}
			if (!this.clear(x, z, VOLCANIC_CLEAR)) {
				return List.of();
			}
			double u = Noise.unit(this.seed, ci, cj, 804);
			double v = Noise.unit(this.seed, ci, cj, 805);
			Kind kind = u < 0.70 ? Kind.VOLCANO : Kind.OLD_VOLCANO;
			double radius = (550.0 + 650.0 * v) * this.dials.size();
			double crest = kind == Kind.VOLCANO ? Noise.clamp(0.20 * radius * (0.85 + 0.30 * u), 60.0, 380.0) : Noise.clamp(0.11 * radius * (0.9 + 0.2 * u), 25.0, 170.0);
			// the arc runs along the boundary, across the line from one plate's centre to the other's
			double nx = b.x() - a.x();
			double nz = b.z() - a.z();
			double length = Math.hypot(nx, nz);
			return List.of(new Island(kind, Origin.ARC, Noise.hash(this.seed, key(ci, cj), 900), x, z, radius, crest, kind == Kind.VOLCANO ? 0.20 + 0.15 * v : 0.70, -nz / length, nx / length,
				1.25, -1, 0));
		});
	}

	private List<Island> arcFinal(int ci, int cj) {
		return this.arcs.computeIfAbsent(key(ci, cj), k -> {
			List<Island> raw = this.arcRaw(ci, cj);
			if (raw.isEmpty()) {
				return raw;
			}
			Island a = raw.get(0);
			return this.touchesChain(a, 1_500.0) ? List.of() : raw;
		});
	}

	private boolean touchesChain(Island a, double margin) {
		PlateGrid.Hit hit = new PlateGrid.Hit();
		this.tec.grid.lookup(a.x(), a.z(), hit);
		int i0 = hit.i;
		int j0 = hit.j;
		for (int di = -2; di <= 2; di++) {
			for (int dj = -2; dj <= 2; dj++) {
				for (Island o : this.chainFinal(i0 + di, j0 + dj)) {
					if (overlap(a, o, margin)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// ---- mushroom islands ---------------------------------------------------------------------------------------------------

	private List<Island> mushroomFinal(int ci, int cj) {
		return this.mushrooms.computeIfAbsent(key(ci, cj), k -> {
			double p = this.dials.mushroom() / 1_000.0 * (MUSHROOM_CELL * MUSHROOM_CELL / 1.0e6);
			if (Noise.unit(this.seed, ci, cj, 901) >= p) {
				return List.of();
			}
			double x = (ci + 0.15 + 0.7 * Noise.unit(this.seed, ci, cj, 902)) * MUSHROOM_CELL;
			double z = (cj + 0.15 + 0.7 * Noise.unit(this.seed, ci, cj, 903)) * MUSHROOM_CELL;
			if (!this.clear(x, z, MUSHROOM_CLEAR)) {
				return List.of();
			}
			double radius = 250.0 * Math.pow(3.6, Noise.unit(this.seed, ci, cj, 904)) * this.dials.size();
			double crest = 6.0 + 18.0 * Noise.unit(this.seed, ci, cj, 905);
			Island m = new Island(Kind.MUSHROOM, Origin.MYSTERY, Noise.hash(this.seed, key(ci, cj), 910), x, z, radius, crest, 1.0, 1.0, 0.0, 1.0, -1, 0);
			// room between it and every volcanic island and chain
			if (this.touchesChain(m, 3_000.0)) {
				return List.of();
			}
			int reach = (int) Math.ceil((radius + 3_000.0 + 1_900.0) / ARC_CELL);
			int ai = (int) Math.floor(x / ARC_CELL);
			int aj = (int) Math.floor(z / ARC_CELL);
			for (int di = -reach; di <= reach; di++) {
				for (int dj = -reach; dj <= reach; dj++) {
					for (Island a : this.arcFinal(ai + di, aj + dj)) {
						if (overlap(m, a, 3_000.0)) {
							return List.of();
						}
					}
				}
			}
			return List.of(m);
		});
	}

	// ---- asking ---------------------------------------------------------------------------------------------------------------

	private static final int NEAR_SLOTS = 64;

	/** A thread's recent answers of {@link #near} for a point itself: one biome lookup asks about the same column four or five times. */
	private static final class NearCache {
		final double[] xs = new double[NEAR_SLOTS];
		final double[] zs = new double[NEAR_SLOTS];
		@SuppressWarnings("unchecked")
		final List<Island>[] lists = new List[NEAR_SLOTS];

		NearCache() {
			Arrays.fill(this.xs, Double.NaN);
		}
	}

	private final ThreadLocal<NearCache> nearCache = ThreadLocal.withInitial(NearCache::new);

	/** The islands whose footprint comes within {@code reach} blocks of the point (not to be changed: for the point itself the answer is remembered). */
	public List<Island> near(double x, double z, double reach) {
		if (reach != 0.0) {
			return this.nearUncached(x, z, reach);
		}
		NearCache cache = this.nearCache.get();
		int slot = (Double.hashCode(x) * 73856093 ^ Double.hashCode(z) * 19349663) & (NEAR_SLOTS - 1);
		if (cache.xs[slot] == x && cache.zs[slot] == z) {
			return cache.lists[slot];
		}
		List<Island> found = List.copyOf(this.nearUncached(x, z, 0.0));
		cache.xs[slot] = x;
		cache.zs[slot] = z;
		cache.lists[slot] = found;
		return found;
	}

	private List<Island> nearUncached(double x, double z, double reach) {
		List<Island> out = new ArrayList<>();
		PlateGrid.Hit hit = new PlateGrid.Hit();
		this.tec.grid.lookup(x, z, hit);
		int i0 = hit.i;
		int j0 = hit.j;
		for (int di = -2; di <= 2; di++) {
			for (int dj = -2; dj <= 2; dj++) {
				for (Island is : this.chainFinal(i0 + di, j0 + dj)) {
					if (Math.hypot(is.x() - x, is.z() - z) <= is.footprint() + reach) {
						out.add(is);
					}
				}
			}
		}
		double span = 5_500.0 + reach;
		int a0 = (int) Math.floor((x - span) / ARC_CELL);
		int a1 = (int) Math.floor((x + span) / ARC_CELL);
		int b0 = (int) Math.floor((z - span) / ARC_CELL);
		int b1 = (int) Math.floor((z + span) / ARC_CELL);
		for (int ci = a0; ci <= a1; ci++) {
			for (int cj = b0; cj <= b1; cj++) {
				for (Island is : this.arcFinal(ci, cj)) {
					if (Math.hypot(is.x() - x, is.z() - z) <= is.footprint() + reach) {
						out.add(is);
					}
				}
			}
		}
		int m0 = (int) Math.floor((x - span) / MUSHROOM_CELL);
		int m1 = (int) Math.floor((x + span) / MUSHROOM_CELL);
		int n0 = (int) Math.floor((z - span) / MUSHROOM_CELL);
		int n1 = (int) Math.floor((z + span) / MUSHROOM_CELL);
		for (int ci = m0; ci <= m1; ci++) {
			for (int cj = n0; cj <= n1; cj++) {
				for (Island is : this.mushroomFinal(ci, cj)) {
					if (Math.hypot(is.x() - x, is.z() - z) <= is.footprint() + reach) {
						out.add(is);
					}
				}
			}
		}
		return out;
	}

	/** The height above sea level at a column of the open ocean: the floor, or an island rising out of it. */
	public double height(double wx, double wz, double floor) {
		double best = floor;
		for (Island is : this.near(wx, wz, 0.0)) {
			best = Math.max(best, profile(is, wx, wz, floor));
		}
		return best;
	}

	/** The island that is highest at a column (at least half a block above the floor), or null: the one a column of land belongs to. */
	public Island islandAt(double wx, double wz, double floor) {
		Island best = null;
		double bestHeight = floor + 0.5;
		for (Island is : this.near(wx, wz, 0.0)) {
			double h = profile(is, wx, wz, floor);
			if (h > bestHeight) {
				bestHeight = h;
				best = is;
			}
		}
		return best;
	}

	/** How far a column of an island is from its shore, in blocks: what the coast bands of the biome mapping ask for. An atoll's islets are all coast. */
	public static double coastBlocks(Island is, double wx, double wz) {
		return is.kind() == Kind.ATOLL ? 30.0 : Math.max(0.0, is.radius() - outline(is, wx, wz));
	}

	/** The mushroom island the column belongs to, if it does: on the island's shallows, shore and land. */
	public Island mushroomAt(double wx, double wz, double floor) {
		for (Island is : this.near(wx, wz, 0.0)) {
			if (is.kind() == Kind.MUSHROOM && outline(is, wx, wz) <= 1.15 * is.radius() && profile(is, wx, wz, floor) > -12.0) {
				return is;
			}
		}
		return null;
	}

	// ---- climate and water ------------------------------------------------------------------------------------------------

	/**
	 * The climate numbers vanilla's table is asked about for a column of an island (not a mushroom island: see {@link #mushroomClimate}). An island
	 * stands up from the sea, so its relief is its own height; it is wet (rain 1,200 mm and more with height); and the coast bands come from how far
	 * the column is from the water.
	 *
	 * @param heightBlocks the ground above sea level, blocks (0 or more)
	 * @param coastBlocks  distance to the water, blocks
	 * @param t0           vanilla's temperature noise here, h0 its humidity, w0 its weirdness
	 */
	public static void climate(double heightBlocks, double coastBlocks, double t0, double h0, double w0, double wx, double wz, long seed, BiomeClimate.Dials dials,
			BiomeClimate.Cell out) {
		double metres = heightBlocks * EarthConfig.METRES_PER_BLOCK_UP;
		BiomeClimate.cell(metres, Math.max(0.0, metres), Math.min(2_400.0, 1_200.0 + 0.35 * metres), coastBlocks, false, t0, h0, w0, wx, wz, seed, dials, out);
	}

	/**
	 * Biomes the island features have of their own, so that vanilla's {@code /locate biome} can find them (see the {@code earth_*} tags). Mushroom
	 * islands are not here: their biome is vanilla's mushroom fields, asked for through {@link #mushroomClimate}.
	 */
	public enum Highlight {
		/** The summit and crater of a young volcano. */
		VOLCANO("epicterrain:volcano"),
		/** The reef flat and the islets of an atoll. */
		ATOLL("epicterrain:atoll"),
		/** The water of a crater lake. */
		CRATER_LAKE("epicterrain:crater_lake");

		public final String biome;

		Highlight(String biome) {
			this.biome = biome;
		}
	}

	/**
	 * The feature biome of a column, or null where the climate decides: a crater lake's water; the upper part of a young volcano (inside 45% of
	 * its radius and at least 30 blocks above the sea: its summit zone, which is hundreds of blocks across, so a search by sampling cannot miss it);
	 * an atoll's reef flat and islets (a little wider than the reef and no deeper than 3 blocks).
	 */
	public Highlight highlight(double wx, double wz, double floor) {
		if (!Double.isNaN(this.craterWater(wx, wz, floor))) {
			return Highlight.CRATER_LAKE;
		}
		for (Island is : this.near(wx, wz, 0.0)) {
			double rr = outline(is, wx, wz);
			if (is.kind() == Kind.VOLCANO && rr <= 0.45 * is.radius() && profile(is, wx, wz, floor) >= 30.0) {
				return Highlight.VOLCANO;
			}
			if (is.kind() == Kind.ATOLL && rr <= is.radius() + Math.max(70.0, 0.18 * is.radius()) + 60.0 && profile(is, wx, wz, floor) > -3.0) {
				return Highlight.ATOLL;
			}
		}
		return null;
	}

	/** Continentalness for a mushroom island and its shallows: below -1.05, where vanilla's table answers mushroom fields in every climate. */
	public static final double MUSHROOM_CONTINENTALNESS = -1.1;

	/** Turns a cell's climate into a mushroom island's. */
	public static void mushroomClimate(BiomeClimate.Cell out) {
		out.continentalness = MUSHROOM_CONTINENTALNESS;
	}

	private final Map<Long, Double> craterLevels = new ConcurrentHashMap<>();

	/** The share of the crest a crater is cut by: 16% of a young volcano's, less as it ages. */
	private static double craterDepth(Island is) {
		return (0.16 - 0.10 * is.age()) * is.crest();
	}

	/** The crater's radius as a share of the volcano's, and the rim's. */
	private static double craterRadius(Island is) {
		return 0.09 + 0.06 * Noise.unit(is.key(), 1, 0, 3);
	}

	/**
	 * The level of the lake in a young volcano's crater (half of them have one), in blocks above sea level, or NaN. It is half way up the crater's
	 * depth but never above the lowest point of the rim all the way round, less a block and a half, so it cannot spill.
	 */
	public double craterLake(Island is, double floor) {
		if (is.kind() != Kind.VOLCANO || is.age() > 0.30 || Noise.unit(is.key(), 2, 0, 21) >= 0.5) {
			return Double.NaN;
		}
		return this.craterLevels.computeIfAbsent(is.key(), k -> {
			double rim = Double.MAX_VALUE;
			double ring = 1.15 * craterRadius(is) * is.radius();
			for (int a = 0; a < 24; a++) {
				double angle = a * Math.PI / 12.0;
				// a point on the rim, in the island's own stretched measure
				double ex = Math.cos(angle) * ring * is.elongation();
				double ez = Math.sin(angle) * ring / is.elongation();
				double wx = is.x() + ex * is.dirX() - ez * is.dirZ();
				double wz = is.z() + ex * is.dirZ() + ez * is.dirX();
				rim = Math.min(rim, profile(is, wx, wz, floor));
			}
			double bottom = profile(is, is.x(), is.z(), floor);
			double level = Math.min(bottom + 0.55 * craterDepth(is), rim - 1.5);
			return level > bottom + 2.0 ? level : Double.NaN;
		});
	}

	/** The surface of a crater lake at a column, in blocks above sea level, or NaN: inside the rim and under the lake's level. */
	public double craterWater(double wx, double wz, double floor) {
		for (Island is : this.near(wx, wz, 0.0)) {
			double level = this.craterLake(is, floor);
			if (!Double.isNaN(level) && outline(is, wx, wz) <= 1.15 * craterRadius(is) * is.radius() && profile(is, wx, wz, floor) < level) {
				return level;
			}
		}
		return Double.NaN;
	}

	// ---- shapes ---------------------------------------------------------------------------------------------------------------

	private static double sq(double v) {
		return v * v;
	}

	private static double smooth01(double t) {
		double c = Math.max(0.0, Math.min(1.0, t));
		return c * c * (3.0 - 2.0 * c);
	}

	/** Distance from the island's centre in the island's own measure: stretched along its direction and with a wobbly shore. */
	private static double outline(Island is, double wx, double wz) {
		double dx = wx - is.x();
		double dz = wz - is.z();
		double along = dx * is.dirX() + dz * is.dirZ();
		double across = -dx * is.dirZ() + dz * is.dirX();
		double e = is.elongation();
		double r = Math.hypot(along / e, across * e);
		double scale = 0.8 * is.radius() + 200.0;
		double wobble = is.kind() == Kind.ATOLL ? 0.06 : 0.15;
		return r * (1.0 - wobble * Noise.fbm(is.key(), wx / scale, wz / scale, 2));
	}

	/**
	 * The height of one island at a column, above sea level, or negative infinity beyond its footprint. {@code floor} is the sea floor
	 * under it; the island rises out of the floor over its flank and is exactly the floor at the edge of its footprint.
	 */
	public static double profile(Island is, double wx, double wz, double floor) {
		double rr = outline(is, wx, wz);
		double radius = is.radius();
		double width = width(is.kind(), radius);
		if (is.kind() == Kind.ATOLL) {
			return atoll(is, wx, wz, rr, floor);
		}
		if (rr >= radius + width) {
			return NEVER;
		}
		if (rr >= radius) {
			double u = (rr - radius) / width;
			return floor * (1.0 - Math.pow(1.0 - u, 2.2));
		}
		double t = rr / radius;
		double crest = is.crest();
		switch (is.kind()) {
			case MUSHROOM -> {
				double dome = crest * Math.pow(1.0 - t * t, 1.3);
				double hills = 0.20 * crest * Noise.fbm(is.key() + 3, wx / (0.35 * radius + 40.0), wz / (0.35 * radius + 40.0), 2) * Math.sqrt(1.0 - t);
				return dome + hills;
			}
			case FRAGMENT -> {
				double lumps = 0.5 + 0.5 * Noise.fbm(is.key() + 4, wx / (0.55 * radius + 40.0), wz / (0.55 * radius + 40.0), 3);
				return crest * Math.pow(1.0 - t * t, 0.8) * (0.55 + 0.45 * lumps) + 0.12 * crest * Noise.fbm(is.key() + 6, wx / 90.0, wz / 90.0, 2) * (1.0 - t);
			}
			default -> {
				double cone = crest * Math.pow(1.0 - t, 1.15);
				double theta = Math.atan2(-(wx - is.x()) * is.dirZ() + (wz - is.z()) * is.dirX(), (wx - is.x()) * is.dirX() + (wz - is.z()) * is.dirZ());
				double rc = 0.09 + 0.06 * Noise.unit(is.key(), 1, 0, 3);
				boolean young = is.kind() == Kind.VOLCANO;
				double crater = young ? (0.16 - 0.10 * is.age()) * crest * Math.pow(Math.max(0.0, 1.0 - t / rc), 1.2) : 0.0;
				double rim = young ? 0.05 * crest * Math.exp(-sq((t - 1.15 * rc) / 0.05)) : 0.0;
				double ramp = Noise.smooth(0.08, 0.40, t) * (1.0 - 0.35 * t);
				// the valleys and the roughness die away toward the shore, so the land meets the sea at sea level and a valley mouth is a bay
				double cut = (0.04 + 0.34 * is.age()) * crest * ramp * valleys(is, theta, t) * Math.pow(1.0 - t, 0.8);
				double fine = 0.02 * crest * Noise.fbm(is.key() + 5, wx / 70.0, wz / 70.0, 2) * Noise.smooth(0.05, 0.30, t) * (1.0 - t);
				// the roughness of walking scale that the continents get from Detail (swells and crags, more of it the higher the ground), dying out at the shore
				double rugged = Noise.smooth(Detail.RELIEF_FROM, Detail.RELIEF_FULL, crest * EarthConfig.METRES_PER_BLOCK_UP);
				double crags = 1.8 * (3.0 * rugged * Noise.fbm(is.key() + 7, wx / 170.0, wz / 170.0, 3) + (0.8 + 1.6 * rugged) * Noise.fbm(is.key() + 8, wx / 15.0, wz / 15.0, 2)) * (1.0 - t);
				return cone - crater + rim - cut + fine + crags;
			}
		}
	}

	/** Radial valleys running down a volcano's flank, 0 on a ridge and 1 in a valley floor: more of them, and finer, further down. */
	private static double valleys(Island is, double theta, double t) {
		int[] count = { 7, 15, 31 };
		double[] weight = { 1.0, 0.7 * smooth01((t - 0.15) / 0.45), 0.5 * smooth01((t - 0.40) / 0.60) };
		double sum = 0.0;
		double total = 0.0;
		for (int o = 0; o < 3; o++) {
			double phase = 2.0 * Math.PI * Noise.unit(is.key(), o, 0, 11);
			double bend = 1.6 * (Noise.value(is.key() + o, t * 5.0, o * 3.1) - 0.5);
			double c = Math.cos(count[o] * theta + phase + 2.0 * bend);
			sum += weight[o] * Math.pow(Math.max(0.0, c), 5.0);
			total += weight[o];
		}
		return Math.min(1.0, 1.8 * sum / total);
	}

	/**
	 * An atoll: a ring of reef a hundred blocks or so across, mostly just under the water, with sandy islets standing above it where the
	 * ring is highest, a shallow lagoon inside it and a steep drop to the floor outside.
	 */
	private static double atoll(Island is, double wx, double wz, double rr, double floor) {
		double ring = is.radius();
		double w = Math.max(70.0, 0.18 * ring);
		double drop = width(Kind.ATOLL, ring);
		double d = rr - ring;
		if (d >= w + drop) {
			return NEVER;
		}
		double theta = Math.atan2(wz - is.z(), wx - is.x());
		double q = Noise.fbm(is.key(), 2.6 * Math.cos(theta) + 5.0, 2.6 * Math.sin(theta) + 5.0, 3);
		double lagoon = -6.5 - 2.5 * Noise.fbm(is.key() + 1, wx / 180.0, wz / 180.0, 2);
		double base;
		if (d <= -w) {
			base = lagoon;
		} else if (d <= w) {
			base = lagoon + (-1.6 - lagoon) * smooth01((d + w) / (2.0 * w));
		} else {
			double u = (d - w) / drop;
			base = -1.6 + (floor + 1.6) * (1.0 - Math.pow(1.0 - u, 2.2));
		}
		return base + 16.0 * Math.max(0.0, q + 0.02) * Math.exp(-sq(d / (0.6 * w)));
	}

	// ---- seas on a plate --------------------------------------------------------------------------------------------------

	/** Chamfer distance, in blocks, from every cell of a window to the nearest land. */
	static float[] distanceToLand(Continent c) {
		int nx = c.nx;
		int nz = c.nz;
		float[] d = new float[nx * nz];
		for (int i = 0; i < d.length; i++) {
			d[i] = c.height[i] > 0.0f ? 0.0f : 1.0e9f;
		}
		float a = (float) c.cell;
		float b = (float) (c.cell * 1.4142);
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				int i = z * nx + x;
				if (x > 0) d[i] = Math.min(d[i], d[i - 1] + a);
				if (z > 0) d[i] = Math.min(d[i], d[i - nx] + a);
				if (x > 0 && z > 0) d[i] = Math.min(d[i], d[i - nx - 1] + b);
				if (x < nx - 1 && z > 0) d[i] = Math.min(d[i], d[i - nx + 1] + b);
			}
		}
		for (int z = nz - 1; z >= 0; z--) {
			for (int x = nx - 1; x >= 0; x--) {
				int i = z * nx + x;
				if (x < nx - 1) d[i] = Math.min(d[i], d[i + 1] + a);
				if (z < nz - 1) d[i] = Math.min(d[i], d[i + nx] + a);
				if (x < nx - 1 && z < nz - 1) d[i] = Math.min(d[i], d[i + nx + 1] + b);
				if (x > 0 && z < nz - 1) d[i] = Math.min(d[i], d[i + nx - 1] + b);
			}
		}
		return d;
	}

	/**
	 * Fragments in the seas of a simulated window: small hilly pieces of continental crust in water that is at least {@link #SEA_CLEAR}
	 * blocks from any land (counting the island's own radius), on a shelf sea floor, and not on the window's rim, which is the edge of the
	 * simulation and not a real sea. Three kilometres apart at the least.
	 */
	public static List<Island> inWindow(Continent c, Dials dials) {
		float[] away = distanceToLand(c);
		List<Island> out = new ArrayList<>();
		double cell = 3_200.0;
		long seed = c.config.seed() * 0x9E3779B97F4A7C15L + 0x51L;
		int cells = (int) Math.ceil(c.nx * c.cell / cell);
		for (int cj = 0; cj < cells; cj++) {
			for (int ci = 0; ci < cells; ci++) {
				double x = c.x0 + (ci + 0.2 + 0.6 * Noise.unit(seed, ci, cj, 1)) * cell;
				double z = c.z0 + (cj + 0.2 + 0.6 * Noise.unit(seed, ci, cj, 2)) * cell;
				int gx = (int) Math.round((x - c.x0) / c.cell);
				int gz = (int) Math.round((z - c.z0) / c.cell);
				if (gx < 0 || gz < 0 || gx >= c.nx || gz >= c.nz) {
					continue;
				}
				int i = gz * c.nx + gx;
				double floor = Continent.blocksAbove(c.height[i]);
				double radius = (250.0 + 400.0 * Noise.unit(seed, ci, cj, 3)) * dials.size();
				if (c.height[i] > 0.0f || c.rim[i] > 0.0f || floor < -70.0 || floor > -6.0 || away[i] < SEA_CLEAR + 1.2 * radius || Noise.unit(seed, ci, cj, 4) >= 0.55) {
					continue;
				}
				boolean crowded = false;
				for (Island o : out) {
					crowded |= Math.hypot(o.x() - x, o.z() - z) < 3_000.0;
				}
				if (!crowded) {
					out.add(new Island(Kind.FRAGMENT, Origin.SEA, Noise.hash(seed, key(ci, cj), 5), x, z, radius, 22.0 + 75.0 * Noise.unit(seed, ci, cj, 6), 0.8, 1.0, 0.0, 1.0, -1, 0));
				}
			}
		}
		return out;
	}
}
