package com.epicterrain.earth;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.epicterrain.earth.Tectonics.Boundary;
import com.epicterrain.earth.Tectonics.Contact;
import com.epicterrain.earth.Tectonics.Fossil;
import com.epicterrain.earth.Tectonics.Plate;

/**
 * The window of world that is simulated, laid out on a grid: the crust it starts as, and every force that will act on it, taken from the
 * tectonic skeleton. Heights are real metres and distances are blocks (the simulation converts).
 *
 * <p>Forces are kept as amplitudes between 0 and 1 (of the greatest uplift rate) for each <i>episode</i> of the planet's history, because
 * when a force acts is what makes mountains young or old:
 * <ul>
 *   <li>{@link #old}: belts of earlier ages, which stop at {@link #toff} (a fraction of the whole history) and are then left to wear down;</li>
 *   <li>{@link #react}: some of those are squeezed again late on, and rise afresh;</li>
 *   <li>{@link #young}: today's active boundaries, switching on in the second half of the history and still rising at the end;</li>
 *   <li>{@link #mid}: mid-plate swells and domes, rising in the middle of the history;</li>
 *   <li>{@link #construct}: height built directly by volcanoes in the last part of the history (metres).</li>
 * </ul>
 */
final class Landscape {
	final EarthConfig config;
	final int nx;
	final int nz;
	final double cell;
	final double x0;
	final double z0;
	/** Starting height of the crust, metres above a sea that has not been chosen yet. */
	final float[] base;
	final float[] young;
	final float[] old;
	final float[] toff;
	final float[] mid;
	final float[] react;
	final float[] construct;
	/** Slow uplift of the whole continent through the whole history: what makes plains into rolling hills. */
	final float[] background;
	/** 0 inside, rising to 1 at the edge of the window: the shelf and coast that surround a continent. */
	final float[] rim;
	/** How easily the rock is worn away, around 1: harder and softer patches break the valleys up. */
	final float[] erodibility;
	/** Where the bedding stands, metres: the height in the column of rock that the starting surface is at (tilt, folds and sag of the beds). */
	final float[] bedding;
	/** How layered the rock is here, 0 for the massive rock of a shield to about 1.25 for a sedimentary basin. */
	final float[] layering;
	/** 1 in ordinary rock, down to 0.2 in the hard core of a granite dome or batholith. */
	final float[] pluton;
	final float[] distance;
	final int[] plateKey;
	final byte[] continental;
	/** The kind of boundary nearest to each cell: {@code Boundary.ordinal() + 1}. */
	final byte[] kind;

	private Landscape(EarthConfig config, int n, double cell, double x0, double z0) {
		this.config = config;
		this.nx = n;
		this.nz = n;
		this.cell = cell;
		this.x0 = x0;
		this.z0 = z0;
		int count = n * n;
		this.base = new float[count];
		this.young = new float[count];
		this.old = new float[count];
		this.toff = new float[count];
		this.mid = new float[count];
		this.react = new float[count];
		this.construct = new float[count];
		this.background = new float[count];
		this.rim = new float[count];
		this.erodibility = new float[count];
		this.bedding = new float[count];
		this.layering = new float[count];
		this.pluton = new float[count];
		Arrays.fill(this.pluton, 1.0f);
		this.distance = new float[count];
		this.plateKey = new int[count];
		this.continental = new byte[count];
		this.kind = new byte[count];
	}

	double worldX(int x) {
		return this.x0 + x * this.cell;
	}

	double worldZ(int z) {
		return this.z0 + z * this.cell;
	}

	private static Plate plate(Map<Long, Plate> cache, Tectonics tec, int i, int j) {
		return cache.computeIfAbsent(((long) i << 32) ^ (j & 0xFFFFFFFFL), k -> tec.plate(i, j));
	}

	static Landscape build(EarthConfig cfg, Tectonics tec, double cx, double cz) {
		double side = cfg.windowPlates() * cfg.plateBlocks();
		int n = (int) Math.ceil(side / cfg.cellBlocks());
		Landscape land = new Landscape(cfg, n, cfg.cellBlocks(), cx - side / 2.0 + cfg.cellBlocks() / 2.0, cz - side / 2.0 + cfg.cellBlocks() / 2.0);
		long s = cfg.seed();
		double sigma = cfg.beltBlocks() / 2.355; // a belt's width is its full width at half height
		PlateGrid.Hit hit = new PlateGrid.Hit();
		PlateGrid.Hit fossilHit = new PlateGrid.Hit();
		Map<Long, Plate> cache = new HashMap<>();
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int idx = z * n + x;
				double wx = land.worldX(x);
				double wz = land.worldZ(z);
				tec.grid.lookup(wx, wz, hit);
				Plate a = plate(cache, tec, hit.i, hit.j);
				Plate b = plate(cache, tec, hit.ni, hit.nj);
				Contact contact = tec.contact(a, b);
				boolean cont = a.continental();
				boolean otherCont = b.continental();
				double d = hit.distance;
				// a belt is not a straight ridge: its edge is bent, and it is stronger here and weaker there
				double bent = Math.max(0.0, d + 1700.0 * Noise.fbm(s + 117, wx / 6000.0, wz / 6000.0, 3) + 450.0 * Noise.fbm(s + 129, wx / 1400.0, wz / 1400.0, 2));
				double strength = (0.6 + 0.8 * Noise.value(s + 121, wx / 2200.0, wz / 2200.0)) * (0.8 + 0.4 * Noise.value(s + 127, wx / 650.0, wz / 650.0));
				// a belt thins out toward a corner where three plates meet, instead of ending in a wedge where the nearest boundary changes
				double taper = Noise.smooth(0.5 * sigma, 3.0 * sigma, hit.distance2);
				// belts come in segments (they break where the noise is low) and vary in width along their length
				double seg = Noise.smooth(0.28, 0.55, Noise.value(s + 101, wx / 9000.0, wz / 9000.0));
				double sg = sigma * (0.75 + 0.5 * Noise.value(s + 103, wx / 6000.0, wz / 6000.0));
				double contBase = 350.0 + 70.0 * Noise.fbm(s + 105, wx / 9000.0, wz / 9000.0, 3) + 40.0 * Noise.fbm(s + 107, wx / 3500.0, wz / 3500.0, 3);
				double oceanBase = -3500.0 + 350.0 * Noise.fbm(s + 109, wx / 9000.0, wz / 9000.0, 2);
				double height = cont ? contBase : oceanBase;
				if (cont && !otherCont) { // a coast: the land falls away over a shelf whose width depends on the kind of margin
					double shelf = switch (contact.type()) {
						case SUBDUCTION -> 900.0;
						case PASSIVE -> 3500.0;
						default -> 1800.0;
					};
					height = oceanBase + (contBase - oceanBase) * Noise.smooth(0.0, shelf, d);
				}
				double up = 0.0;
				double trough = 0.7 * sigma;
				double chance = tec.troughChance(a, b);
				switch (contact.type()) {
					case COLLISION -> {
						up = seg * Noise.gauss(bent, 1.2 * sg);
						if (contact.firstOverrides()) {
							up += 0.28 * Noise.gauss(bent - 2.2 * sg, 2.4 * sg); // the plateau behind the range
						}
					}
					case SUBDUCTION -> {
						if (contact.firstOverrides()) {
							up = 0.9 * seg * Noise.gauss(bent - 0.8 * sg, sg);
							height -= 700.0 * Noise.gauss(d - 5.0 * sg, 1.8 * sg); // the basin behind the arc
						} else {
							height -= 1500.0 * Noise.gauss(d, 700.0); // the trench
						}
					}
					case ISLAND_ARC -> {
						if (contact.firstOverrides()) {
							up = 0.85 * seg * Noise.gauss(bent - 0.6 * sg, 0.7 * sg);
						}
					}
					case TRANSFORM -> {
						up = 0.2 * seg * Noise.gauss(bent, 0.5 * sg);
						if (cont && otherCont && chance < 0.35) {
							height -= (500.0 + 500.0 * chance / 0.35) * Noise.gauss(d, trough); // a pull-apart basin or strait
						}
					}
					case RIFT -> {
						if (chance < 0.7) {
							height -= (700.0 + 1100.0 * chance / 0.7) * Noise.gauss(d, trough); // a strait or rift sea
						}
						up = 0.25 * seg * Noise.gauss(bent - 1.8 * trough, 0.8 * trough); // its shoulders
					}
					case SPREADING -> height += 1200.0 * Noise.gauss(d, 2200.0);
					case PASSIVE -> {
					}
				}
				int edgeCells = Math.min(Math.min(x, z), Math.min(n - 1 - x, n - 1 - z));
				land.rim[idx] = (float) (1.0 - Noise.smooth(0.0, 0.07 * n, edgeCells + 14.0 * Noise.fbm(s + 145, wx / 6500.0, wz / 6500.0, 3) + 4.0 * Noise.fbm(s + 147, wx / 1800.0, wz / 1800.0, 2)));
				land.erodibility[idx] = (float) ((0.65 + 0.7 * Noise.value(s + 141, wx / 2500.0, wz / 2500.0)) * (0.85 + 0.3 * Noise.value(s + 143, wx / 800.0, wz / 800.0)));
				land.base[idx] = (float) height;
				land.young[idx] = (float) (up * strength * taper);
				land.background[idx] = cont ? (float) (0.07 + 0.035 * Noise.value(s + 131, wx / 3500.0, wz / 3500.0)) : 0.0f;
				land.distance[idx] = (float) d;
				land.plateKey[idx] = hit.i * 73856093 ^ hit.j * 19349663;
				land.continental[idx] = (byte) (cont ? 1 : 0);
				land.kind[idx] = (byte) (contact.type().ordinal() + 1);

				if (cont) { // belts of an earlier age
					tec.fossilGrid.lookup(wx, wz, fossilHit);
					Fossil fossil = tec.fossil(fossilHit);
					if (fossil.collision()) {
						double fs = Noise.smooth(0.30, 0.55, Noise.value(s + 111, wx / 8000.0, wz / 8000.0));
						double fw = 1.3 * sigma * (0.8 + 0.4 * Noise.value(s + 113, wx / 5000.0, wz / 5000.0));
						double fossilBent = Math.max(0.0, fossilHit.distance + 1100.0 * Noise.fbm(s + 119, wx / 5000.0, wz / 5000.0, 3));
						double amplitude = fossil.amplitude() * fs * Noise.gauss(fossilBent, fw) * (0.6 + 0.8 * Noise.value(s + 123, wx / 2200.0, wz / 2200.0))
							* (0.8 + 0.4 * Noise.value(s + 125, wx / 650.0, wz / 650.0)) * Noise.smooth(0.5 * sigma, 3.0 * sigma, fossilHit.distance2);
						land.old[idx] = (float) amplitude;
						land.toff[idx] = (float) fossil.switchOff();
						land.react[idx] = fossil.reactivated() ? (float) (0.4 * amplitude) : 0.0f;
					}
				}
			}
		}
		land.addIntraplate(tec);
		land.addVolcanoes(tec, sigma);
		land.addStrata(sigma);
		return land;
	}

	/** A box average of a field over {@code radius} cells either way (twice, so it is nearly a bell), for fields that vary over a belt's width. */
	private static float[] blur(float[] field, int nx, int nz, int radius) {
		float[] a = field.clone();
		float[] b = new float[a.length];
		for (int pass = 0; pass < 2; pass++) {
			for (int z = 0; z < nz; z++) { // along x
				double sum = 0.0;
				for (int x = -radius; x <= radius; x++) {
					sum += a[z * nx + Math.max(0, Math.min(nx - 1, x))];
				}
				for (int x = 0; x < nx; x++) {
					b[z * nx + x] = (float) (sum / (2 * radius + 1));
					sum += a[z * nx + Math.min(nx - 1, x + radius + 1)] - a[z * nx + Math.max(0, x - radius)];
				}
			}
			for (int x = 0; x < nx; x++) { // along z
				double sum = 0.0;
				for (int z = -radius; z <= radius; z++) {
					sum += b[Math.max(0, Math.min(nz - 1, z)) * nx + x];
				}
				for (int z = 0; z < nz; z++) {
					a[z * nx + x] = (float) (sum / (2 * radius + 1));
					sum += b[Math.min(nz - 1, z + radius + 1) * nx + x] - b[Math.max(0, z - radius) * nx + x];
				}
			}
		}
		return a;
	}

	/**
	 * The rock under the landscape. The beds lie in a stack that is tilted over the whole window (a few degrees, in a direction that is
	 * the same everywhere, as a broad basin or a continental tilt is), folded in waves a few kilometres across near the belts of
	 * mountains (where the crust was squeezed: ridge-and-valley country), and gently warped elsewhere. How layered the rock is varies over
	 * regions about 9 km across, from massive rock (a shield: rounded, no benches) to thinly bedded sediment, and granite domes are
	 * scattered through it, more of them in and beside the belts.
	 */
	private void addStrata(double sigma) {
		long s = this.config.seed();
		// the dip of the beds, metres a block (0.35), how far the warp bends the outcrops (1 is 6 km of warp) and the size of its waves in blocks (16,000)
		double tiltRate = dial("tilt", 0.35);
		double bend = dial("bend", 1.0);
		double bendScale = dial("bendScale", 16_000.0);
		double phi = 2.0 * Math.PI * Noise.unit(s, 501, 0, 1);
		double psi = phi + 0.5 * Math.PI + 0.8 * (Noise.unit(s, 502, 0, 2) - 0.5);
		float[] belt = new float[this.nx * this.nz];
		for (int i = 0; i < belt.length; i++) {
			belt[i] = Math.min(1.0f, Math.max(this.young[i], this.old[i]));
		}
		float[] near = blur(belt, this.nx, this.nz, Math.max(4, (int) Math.round(1.3 * sigma / this.cell)));
		for (int z = 0; z < this.nz; z++) {
			for (int x = 0; x < this.nx; x++) {
				int i = z * this.nx + x;
				double wx = this.worldX(x);
				double wz = this.worldZ(z);
				double folds = Noise.smooth(0.03, 0.18, near[i]);
				// the tilt is warped over regions about 16 km across, so the dip changes from place to place (flat-lying where the warp cancels it,
				// steep where it adds, even turning over into a dome or a basin) and the outcrops of a bed sweep in curves, not parallel lines
				double tilt = tiltRate * (wx * Math.cos(phi) + wz * Math.sin(phi) + 6000.0 * bend * Noise.fbm(s + 509, wx / bendScale, wz / bendScale, 2));
				double across = wx * Math.cos(psi) + wz * Math.sin(psi) + 1100.0 * Noise.fbm(s + 505, wx / 7000.0, wz / 7000.0, 2);
				double fold = 220.0 * folds * Math.sin(2.0 * Math.PI * (across / 4200.0 + Noise.value(s + 503, wx / 6000.0, wz / 6000.0)));
				double sag = 60.0 * Noise.fbm(s + 507, wx / 3000.0, wz / 3000.0, 3);
				this.bedding[i] = (float) (tilt + fold + sag);
				if (this.continental[i] != 0) {
					this.layering[i] = (float) (0.15 + 1.1 * Noise.smooth(0.35, 0.65, Noise.value(s + 511, wx / 9000.0, wz / 9000.0)));
					double dome = Noise.smooth(0.80 - 0.15 * folds, 0.90 - 0.15 * folds, Noise.value(s + 521, wx / 4200.0, wz / 4200.0));
					this.pluton[i] = (float) (1.0 - 0.8 * dome);
				}
			}
		}
	}

	/** A tuning number that {@code -Depicterrain.earth.NAME} can change, read each time the landscape is made (so that variants can be compared in one run). */
	private static double dial(String name, double fallback) {
		String value = System.getProperty("epicterrain.earth." + name);
		return value == null ? fallback : Double.parseDouble(value);
	}

	/** Adds a bell of the given amplitude, elongated along (dx, dz), to a field. */
	void addGauss(float[] field, double cx, double cz, double along, double across, double dx, double dz, double amplitude) {
		double length = Math.hypot(dx, dz);
		if (length == 0.0) {
			dx = 1.0;
			dz = 0.0;
			length = 1.0;
		}
		dx /= length;
		dz /= length;
		double reach = 3.5 * Math.max(along, across);
		int x0i = Math.max(0, (int) StrictMath.floor((cx - reach - this.x0) / this.cell));
		int x1i = Math.min(this.nx - 1, (int) StrictMath.ceil((cx + reach - this.x0) / this.cell));
		int z0i = Math.max(0, (int) StrictMath.floor((cz - reach - this.z0) / this.cell));
		int z1i = Math.min(this.nz - 1, (int) StrictMath.ceil((cz + reach - this.z0) / this.cell));
		for (int z = z0i; z <= z1i; z++) {
			for (int x = x0i; x <= x1i; x++) {
				double px = this.worldX(x) - cx;
				double pz = this.worldZ(z) - cz;
				double u = (px * dx + pz * dz) / along;
				double v = (-px * dz + pz * dx) / across;
				double q = u * u + v * v;
				if (q < 12.0) {
					field[z * this.nx + x] += (float) (amplitude * StrictMath.exp(-0.5 * q));
				}
			}
		}
	}

	/** Mid-plate sources: hotspot swells and their volcano chains, basement domes, and block uplifts far inland of a subduction zone. */
	private void addIntraplate(Tectonics tec) {
		EarthConfig cfg = this.config;
		long s = cfg.seed();
		double p = cfg.plateBlocks();
		// the less of the relief is meant to be at boundaries, the more of it is mid-plate (1.0 at the default 75 / 25)
		double intra = (1.0 - cfg.boundaryShare()) / cfg.boundaryShare() * 3.0;
		double side = this.nx * this.cell;
		int i0 = (int) StrictMath.floor((this.x0 - 0.5 * p) / p) - 1;
		int i1 = (int) StrictMath.floor((this.x0 + side + 0.5 * p) / p) + 1;
		int j0 = (int) StrictMath.floor((this.z0 - 0.5 * p) / p) - 1;
		int j1 = (int) StrictMath.floor((this.z0 + side + 0.5 * p) / p) + 1;
		for (int i = i0; i <= i1; i++) {
			for (int j = j0; j <= j1; j++) {
				Plate plate = tec.plate(i, j);
				double speed = Math.hypot(plate.vx(), plate.vz());
				double dirX = plate.vx() / speed;
				double dirZ = plate.vz() / speed;
				if (Noise.unit(s, i, j, 6) < 0.35) { // a hotspot: a swell, and a chain of volcanoes along the way the plate has moved
					double hx = plate.x() + (Noise.unit(s, i, j, 7) - 0.5) * 0.4 * p;
					double hz = plate.z() + (Noise.unit(s, i, j, 9) - 0.5) * 0.4 * p;
					this.addGauss(this.mid, hx, hz, 2200.0, 2200.0, 1.0, 0.0, 0.30 * intra);
					for (int k = 0; k < 5; k++) {
						this.addGauss(this.construct, hx + dirX * 1700.0 * k, hz + dirZ * 1700.0 * k, 380.0, 380.0, 1.0, 0.0,
							2600.0 * StrictMath.pow(0.72, k) * Math.sqrt(intra));
					}
				}
				if (plate.continental() && Noise.unit(s, i, j, 8) < 0.4) { // a basement dome
					this.addGauss(this.mid, plate.x() + (Noise.unit(s, i, j, 10) - 0.5) * 0.4 * p, plate.z() + (Noise.unit(s, i, j, 11) - 0.5) * 0.4 * p,
						1500.0, 1500.0, 1.0, 0.0, 0.5 * intra);
				}
				if (plate.continental()) { // block uplifts a long way inland of a subduction zone that this plate rides over
					for (int di = -1; di <= 1; di++) {
						for (int dj = -1; dj <= 1; dj++) {
							if (di == 0 && dj == 0) {
								continue;
							}
							Plate other = tec.plate(i + di, j + dj);
							Contact contact = tec.contact(plate, other);
							if (contact.type() != Boundary.SUBDUCTION || !contact.firstOverrides()) {
								continue;
							}
							long key = (long) (di + 1) * 3 + (dj + 1);
							if (Noise.unit(s, i, j, 12 + key) >= 0.55) {
								continue;
							}
							double ux = plate.x() - other.x();
							double uz = plate.z() - other.z();
							double ul = Math.hypot(ux, uz);
							ux /= ul;
							uz /= ul;
							double offset = 7000.0 + 2500.0 * Noise.unit(s, i, j, 30 + key);
							this.addGauss(this.young, 0.5 * (plate.x() + other.x()) + ux * offset, 0.5 * (plate.z() + other.z()) + uz * offset,
								2800.0, 700.0, -uz, ux, 0.5 * intra);
						}
					}
				}
			}
		}
	}

	/** Volcano cones along the arc of every subduction zone and island arc. */
	private void addVolcanoes(Tectonics tec, double sigma) {
		long s = this.config.seed();
		double spacing = 1600.0;
		double side = this.nx * this.cell;
		int g0x = (int) StrictMath.floor(this.x0 / spacing);
		int g1x = (int) StrictMath.floor((this.x0 + side) / spacing);
		int g0z = (int) StrictMath.floor(this.z0 / spacing);
		int g1z = (int) StrictMath.floor((this.z0 + side) / spacing);
		PlateGrid.Hit hit = new PlateGrid.Hit();
		for (int gx = g0x; gx <= g1x; gx++) {
			for (int gz = g0z; gz <= g1z; gz++) {
				double px = (gx + Noise.unit(s, gx, gz, 20)) * spacing;
				double pz = (gz + Noise.unit(s, gx, gz, 21)) * spacing;
				tec.grid.lookup(px, pz, hit);
				Plate a = tec.plate(hit.i, hit.j);
				Plate b = tec.plate(hit.ni, hit.nj);
				Contact contact = tec.contact(a, b);
				double u = Noise.unit(s, gx, gz, 22);
				if (contact.type() == Boundary.SUBDUCTION && contact.firstOverrides() && Math.abs(hit.distance - 0.8 * sigma) < 0.9 * sigma && u < 0.45) {
					this.addGauss(this.construct, px, pz, 420.0, 420.0, 1.0, 0.0, 1200.0 + 1400.0 * Noise.unit(s, gx, gz, 23));
				} else if (contact.type() == Boundary.ISLAND_ARC && contact.firstOverrides() && Math.abs(hit.distance - 0.6 * sigma) < 0.9 * sigma && u < 0.6) {
					this.addGauss(this.construct, px, pz, 420.0, 420.0, 1.0, 0.0, 3200.0 + 1500.0 * Noise.unit(s, gx, gz, 23));
				}
			}
		}
	}
}
