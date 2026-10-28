package com.epicterrain.earth;

/**
 * The tectonic skeleton of the world: plates, what they are made of, how they move, and what happens where two of them meet. It is a pure
 * function of the seed, so every continent that asks about it gets the same answer and no continent needs to know about any other.
 *
 * <p>Plates carry either continental or oceanic crust. What a boundary does depends on how the two plates move relative to each other
 * (together, apart, or past each other) and on the crust on each side:
 * <ul>
 *   <li>continent meets continent, together: <b>collision</b> (Himalaya, Alps); apart: <b>rift</b>; past: <b>transform</b>;</li>
 *   <li>ocean meets continent, together: <b>subduction</b> (Andes, Cascades), the continent rides over; apart: a <b>passive</b> margin;</li>
 *   <li>ocean meets ocean, together: an <b>island arc</b> (Japan, Aleutians); apart: a <b>spreading</b> ridge.</li>
 * </ul>
 * A second, coarser and unrelated set of plates stands for an earlier age of the planet: where its boundaries once collided there are
 * <b>fossil</b> belts, long since worn down (Appalachians, Urals), now in the middle of today's plates.
 */
public final class Tectonics {
	public enum Crust {
		CONTINENTAL, OCEANIC
	}

	public enum Boundary {
		COLLISION, SUBDUCTION, ISLAND_ARC, RIFT, PASSIVE, SPREADING, TRANSFORM
	}

	public record Plate(int i, int j, double x, double z, Crust crust, double vx, double vz) {
		public boolean continental() {
			return this.crust == Crust.CONTINENTAL;
		}
	}

	/**
	 * What a boundary does, seen from one of its two plates.
	 *
	 * @param convergence   how fast the two plates approach each other, in plate-speed units (negative: moving apart)
	 * @param firstOverrides whether the plate the question was asked about rides over the other
	 */
	public record Contact(Boundary type, double convergence, boolean firstOverrides) {
	}

	/** A belt left by an old collision. */
	record Fossil(boolean collision, double amplitude, double switchOff, boolean reactivated) {
	}

	private final EarthConfig config;
	private final long seed;
	final PlateGrid grid;
	final PlateGrid fossilGrid;

	public Tectonics(EarthConfig config) {
		this.config = config;
		this.seed = config.seed();
		this.grid = new PlateGrid(config.seed() + 0xA1L, config.plateBlocks(), 0.7);
		this.fossilGrid = new PlateGrid(config.seed() + 0xF0L, config.plateBlocks() * 0.85, 0.75);
	}

	public EarthConfig config() {
		return this.config;
	}

	public Plate plate(int i, int j) {
		Crust crust = Noise.unit(this.seed, i, j, 3) < this.config.continentalShare() ? Crust.CONTINENTAL : Crust.OCEANIC;
		double angle = 2.0 * Math.PI * Noise.unit(this.seed, i, j, 4);
		double speed = 0.4 + 0.6 * Noise.unit(this.seed, i, j, 5);
		return new Plate(i, j, this.grid.seedX(i, j), this.grid.seedZ(i, j), crust, speed * StrictMath.cos(angle), speed * StrictMath.sin(angle));
	}

	/** The plate containing a point, and the one across the nearest boundary. */
	public Plate plateAt(double x, double z, PlateGrid.Hit hit) {
		this.grid.lookup(x, z, hit);
		return this.plate(hit.i, hit.j);
	}

	private long pairHash(long layer, int ai, int aj, int bi, int bj) {
		long ha = Noise.hash(this.seed + layer, ai, aj);
		long hb = Noise.hash(this.seed + layer, bi, bj);
		return Noise.mix(ha + hb + 0x77L); // the same from either side
	}

	/** Chance (0 to 1) that this boundary is a pull-apart basin or strait; the same from either side. */
	public double troughChance(Plate a, Plate b) {
		return Noise.unit(this.pairHash(3L, a.i(), a.j(), b.i(), b.j()));
	}

	public Contact contact(Plate a, Plate b) {
		double dx = b.x() - a.x();
		double dz = b.z() - a.z();
		double length = StrictMath.sqrt(dx * dx + dz * dz);
		double nx = dx / length;
		double nz = dz / length;
		double approach = (a.vx() - b.vx()) * nx + (a.vz() - b.vz()) * nz; // the same from either side
		boolean aLow = a.i() < b.i() || (a.i() == b.i() && a.j() < b.j());
		boolean lowOverrides = Noise.unit(this.pairHash(1L, a.i(), a.j(), b.i(), b.j())) < 0.5;
		boolean aOverridesByChance = aLow == lowOverrides;
		boolean ac = a.continental();
		boolean bc = b.continental();
		if (ac && bc) {
			if (approach > 0.35) {
				return new Contact(Boundary.COLLISION, approach, aOverridesByChance);
			}
			return new Contact(approach < -0.35 ? Boundary.RIFT : Boundary.TRANSFORM, approach, false);
		}
		if (ac != bc) {
			if (approach > 0.15) {
				return new Contact(Boundary.SUBDUCTION, approach, ac); // the continent rides over the ocean floor
			}
			return new Contact(approach < -0.15 ? Boundary.PASSIVE : Boundary.TRANSFORM, approach, false);
		}
		if (approach > 0.15) {
			return new Contact(Boundary.ISLAND_ARC, approach, aOverridesByChance);
		}
		return new Contact(approach < -0.15 ? Boundary.SPREADING : Boundary.TRANSFORM, approach, false);
	}

	/** The old belt, if any, that a lookup in the fossil grid landed beside. */
	Fossil fossil(PlateGrid.Hit hit) {
		double[] va = this.fossilVelocity(hit.i, hit.j);
		double[] vb = this.fossilVelocity(hit.ni, hit.nj);
		double dx = this.fossilGrid.seedX(hit.ni, hit.nj) - this.fossilGrid.seedX(hit.i, hit.j);
		double dz = this.fossilGrid.seedZ(hit.ni, hit.nj) - this.fossilGrid.seedZ(hit.i, hit.j);
		double length = StrictMath.sqrt(dx * dx + dz * dz);
		double approach = ((va[0] - vb[0]) * dx + (va[1] - vb[1]) * dz) / length;
		long pair = this.pairHash(0xF055L, hit.i, hit.j, hit.ni, hit.nj);
		boolean collision = approach > 0.2 && Noise.unit(Noise.mix(pair + 1)) < 0.75;
		double amplitude = Noise.clamp(0.6 + 0.4 * approach, 0.5, 1.0);
		int age = (int) (3.0 * Noise.unit(Noise.mix(pair + 2)));
		double switchOff = 0.16 + 0.11 * age + 0.04 * Noise.unit(Noise.mix(pair + 3));
		boolean reactivated = Noise.unit(Noise.mix(pair + 4)) < 0.18;
		return new Fossil(collision, amplitude, switchOff, reactivated);
	}

	private double[] fossilVelocity(int i, int j) {
		double angle = 2.0 * Math.PI * Noise.unit(this.seed + 0xF0L, i, j, 4);
		double speed = 0.4 + 0.6 * Noise.unit(this.seed + 0xF0L, i, j, 5);
		return new double[] { speed * StrictMath.cos(angle), speed * StrictMath.sin(angle) };
	}

	/**
	 * Where to put the simulated window: the continental plate nearest the origin that has an active convergent boundary beside it (so
	 * the first look has mountains in it), or failing that the nearest continental plate.
	 */
	public double[] chooseCenter() {
		double[] best = null;
		double bestDistance = Double.MAX_VALUE;
		double[] fallback = null;
		double fallbackDistance = Double.MAX_VALUE;
		for (int i = -4; i <= 4; i++) {
			for (int j = -4; j <= 4; j++) {
				Plate p = this.plate(i, j);
				if (!p.continental()) {
					continue;
				}
				double d = Math.hypot(p.x(), p.z());
				if (d < fallbackDistance) {
					fallbackDistance = d;
					fallback = new double[] { p.x(), p.z() };
				}
				boolean convergent = false;
				for (int di = -1; di <= 1; di++) {
					for (int dj = -1; dj <= 1; dj++) {
						if (di == 0 && dj == 0) {
							continue;
						}
						Boundary type = this.contact(p, this.plate(i + di, j + dj)).type();
						convergent |= type == Boundary.SUBDUCTION || type == Boundary.COLLISION;
					}
				}
				if (convergent && d < bestDistance) {
					bestDistance = d;
					best = new double[] { p.x(), p.z() };
				}
			}
		}
		return best != null ? best : fallback;
	}
}
