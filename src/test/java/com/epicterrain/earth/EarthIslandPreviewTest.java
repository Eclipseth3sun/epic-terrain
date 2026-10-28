package com.epicterrain.earth;

import static com.epicterrain.earth.EarthDetailPreviewTest.SIZE;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.Islands.Island;
import com.epicterrain.earth.Islands.Kind;
import com.epicterrain.earth.Tectonics.Contact;
import com.epicterrain.earth.Tectonics.Plate;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * The islands: a map of a stretch of the world (which plates are ocean, where the open ocean is wide enough, and every island on it), a
 * close-up of each kind with the biomes vanilla's table gives it, and numbers for how many there are, with mushroom islands counted
 * against vanilla's rate. Options: {@code -Depicterrain.earth.mushroom / volcanic / islandSize}.
 */
@Tag("preview")
class EarthIslandPreviewTest {
	private static final int MAP = 1600;
	private static final double MAP_STEP = 125.0; // blocks to a pixel: 200 km square

	static int kindColour(Kind kind) {
		return switch (kind) {
			case MUSHROOM -> 0xE040E0;
			case VOLCANO -> 0xE02020;
			case OLD_VOLCANO -> 0xF08020;
			case ATOLL -> 0x20D0C0;
			case FRAGMENT -> 0x906030;
		};
	}

	/** Every island of the catalogue that stands in a square, once. */
	static Map<Long, Island> islandsIn(Islands islands, double cx, double cz, double half) {
		Map<Long, Island> found = new LinkedHashMap<>();
		for (double z = cz - half; z <= cz + half; z += 4_000.0) {
			for (double x = cx - half; x <= cx + half; x += 4_000.0) {
				for (Island is : islands.near(x, z, 0.0)) {
					if (Math.abs(is.x() - cx) <= half && Math.abs(is.z() - cz) <= half) {
						found.putIfAbsent(is.key(), is);
					}
				}
			}
		}
		return found;
	}

	private static BufferedImage map(Tectonics tec, Islands islands, double cx, double cz, Map<Long, Island> found) {
		BufferedImage img = new BufferedImage(MAP, MAP, BufferedImage.TYPE_INT_RGB);
		PlateGrid.Hit hit = new PlateGrid.Hit();
		for (int pz = 0; pz < MAP; pz++) {
			for (int px = 0; px < MAP; px++) {
				double x = cx + (px - MAP / 2) * MAP_STEP;
				double z = cz + (pz - MAP / 2) * MAP_STEP;
				Plate plate = tec.plateAt(x, z, hit);
				int colour;
				if (plate.continental()) {
					colour = 0xB8CC88;
				} else {
					colour = 0x4F86C0;
					if (px % 4 == 0 && pz % 4 == 0 && islands.qualifies(x, z)) {
						colour = 0x2A5F9A;
					}
				}
				if (hit.distance < MAP_STEP) {
					Plate other = tec.plate(hit.ni, hit.nj);
					Contact contact = tec.contact(plate, other);
					colour = switch (contact.type()) {
						case ISLAND_ARC -> contact.firstOverrides() ? 0x802020 : 0x602060;
						case SUBDUCTION, COLLISION -> 0x705030;
						case SPREADING, RIFT -> 0x3A6A3A;
						default -> 0x606060;
					};
				}
				img.setRGB(px, pz, colour);
			}
		}
		// the qualifying water is only sampled every fourth pixel: spread it
		for (int pz = 0; pz < MAP; pz += 4) {
			for (int px = 0; px < MAP; px += 4) {
				int c = img.getRGB(px, pz) & 0xFFFFFF;
				if (c == 0x2A5F9A) {
					for (int dz = 0; dz < 4 && pz + dz < MAP; dz++) {
						for (int dx = 0; dx < 4 && px + dx < MAP; dx++) {
							if ((img.getRGB(px + dx, pz + dz) & 0xFFFFFF) == 0x4F86C0) {
								img.setRGB(px + dx, pz + dz, c);
							}
						}
					}
				}
			}
		}
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (Island is : found.values()) {
			double px = (is.x() - cx) / MAP_STEP + MAP / 2.0;
			double pz = (is.z() - cz) / MAP_STEP + MAP / 2.0;
			double r = Math.max(4.0, is.radius() / MAP_STEP * 1.6);
			g.setColor(new Color(kindColour(is.kind())));
			g.fill(new Ellipse2D.Double(px - r, pz - r, 2 * r, 2 * r));
			g.setColor(Color.BLACK);
			g.setStroke(new BasicStroke(1.0f));
			g.draw(new Ellipse2D.Double(px - r, pz - r, 2 * r, 2 * r));
		}
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 640, 112);
		g.setColor(Color.BLACK);
		g.setFont(new Font("SansSerif", Font.BOLD, 14));
		g.drawString(String.format("200 km square around (%.0f, %.0f). Light green: continental plates. Blue: oceanic plates; dark blue: open ocean 6 km", cx, cz), 8, 18);
		g.drawString("from any continent (where mushroom islands can stand). Boundary lines: red island arc, brown subduction/collision, green", 8, 36);
		g.drawString("rift or spreading, grey transform. Islands: magenta mushroom, red young volcano, orange old volcano, cyan atoll.", 8, 54);
		g.dispose();
		return img;
	}

	// ---- close-ups ---------------------------------------------------------------------------------------------------------

	private record Climates(MultiNoiseBiomeSource source, Climate.Sampler sampler, long seed) {
	}

	private static Climates climates(long seed) {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		RandomState state = h.randomState(seed, settings);
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		return new Climates((MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource(), state.createClimateSampler(SamplerContext.EMPTY_UNCACHED), seed);
	}

	/** Sea floor height of the ground a panel shows, in blocks above sea level, at a column. */
	private interface Ground {
		double at(double wx, double wz);
	}

	/** The name of the feature biome of a column, or null. */
	private interface Tagged {
		String at(double wx, double wz);
	}

	private static Tagged tagged(Islands islands) {
		return (wx, wz) -> {
			Islands.Highlight h = islands.highlight(wx, wz, Islands.OCEAN_FLOOR);
			return h == null ? null : h.biome.substring(h.biome.indexOf(':') + 1);
		};
	}

	private static BufferedImage panel(Climates cl, Island is, Ground ground, String label, Map<String, Double> biomeShare) {
		return panel(cl, is, ground, null, null, label, biomeShare);
	}

	private static BufferedImage panel(Climates cl, Island is, Ground ground, Ground water, Tagged tag, String label, Map<String, Double> biomeShare) {
		double half = is.elongation() * (is.radius() + 0.45 * Islands.width(is.kind(), is.radius())) + 300.0;
		return render(cl, is.x(), is.z(), half, is.kind() == Kind.MUSHROOM ? is : null, ground, water, tag, label, biomeShare);
	}

	/** A square of the world {@code 2 * half} across around a point, as shaded relief coloured by vanilla's biomes; {@code mushroom} is the island, if any, that is labelled. */
	private static BufferedImage render(Climates cl, double cx, double cz, double half, Island mushroom, Ground ground, Ground water, Tagged tag, String label, Map<String, Double> biomeShare) {
		double step = 2.0 * half / SIZE;
		double x0 = cx - half;
		double z0 = cz - half;
		double[] h = new double[SIZE * SIZE];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				h[z * SIZE + x] = ground.at(x0 + x * step, z0 + z * step);
			}
		}
		// distance to the sea, in blocks, for the coast bands
		float[] away = new float[SIZE * SIZE];
		for (int i = 0; i < away.length; i++) {
			away[i] = h[i] < 0.0 ? 0.0f : 1.0e9f;
		}
		for (int pass = 0; pass < 2; pass++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int i = z * SIZE + x;
					if (x > 0) away[i] = Math.min(away[i], away[i - 1] + 1.0f);
					if (z > 0) away[i] = Math.min(away[i], away[i - SIZE] + 1.0f);
					if (x > 0 && z > 0) away[i] = Math.min(away[i], away[i - SIZE - 1] + 1.4142f);
					if (x < SIZE - 1 && z > 0) away[i] = Math.min(away[i], away[i - SIZE + 1] + 1.4142f);
				}
			}
			for (int z = SIZE - 1; z >= 0; z--) {
				for (int x = SIZE - 1; x >= 0; x--) {
					int i = z * SIZE + x;
					if (x < SIZE - 1) away[i] = Math.min(away[i], away[i + 1] + 1.0f);
					if (z < SIZE - 1) away[i] = Math.min(away[i], away[i + SIZE] + 1.0f);
					if (x < SIZE - 1 && z < SIZE - 1) away[i] = Math.min(away[i], away[i + SIZE + 1] + 1.4142f);
					if (x > 0 && z < SIZE - 1) away[i] = Math.min(away[i], away[i + SIZE - 1] + 1.4142f);
				}
			}
		}
		BiomeClimate.Dials dials = BiomeClimate.Dials.defaults();
		BiomeClimate.Cell cell = new BiomeClimate.Cell();
		BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		int land = 0;
		Map<String, Integer> counts = new HashMap<>();
		// vanilla's temperature, humidity and weirdness vary over kilometres: one sample every 20 pixels, the nearest to each pixel
		int grid = (SIZE + 19) / 20 + 1;
		double[][] noise = new double[grid * grid][];
		for (int gz = 0; gz < grid; gz++) {
			for (int gx = 0; gx < grid; gx++) {
				Climate.TargetPoint p = cl.sampler().sample((int) Math.floor(x0 + gx * 20 * step) >> 2, 16, (int) Math.floor(z0 + gz * 20 * step) >> 2);
				noise[gz * grid + gx] = new double[] { Climate.unquantizeCoord(p.temperature()), Climate.unquantizeCoord(p.humidity()), Climate.unquantizeCoord(p.weirdness()) };
			}
		}
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				int i = z * SIZE + x;
				double wx = x0 + x * step;
				double wz = z0 + z * step;
				if (h[i] < 0.0) {
					img.setRGB(x, z, EarthImages.mix(0x62B6E8, 0x0F3A78, Math.min(1.0, -h[i] / 60.0)));
					continue;
				}
				int gx0 = Math.min(grid - 2, x / 20);
				int gz0 = Math.min(grid - 2, z / 20);
				double fx = x / 20.0 - gx0;
				double fz = z / 20.0 - gz0;
				double[] n = new double[3];
				for (int c = 0; c < 3; c++) {
					n[c] = (noise[gz0 * grid + gx0][c] * (1 - fx) + noise[gz0 * grid + gx0 + 1][c] * fx) * (1 - fz) + (noise[(gz0 + 1) * grid + gx0][c] * (1 - fx) + noise[(gz0 + 1) * grid + gx0 + 1][c] * fx) * fz;
				}
				double t0 = n[0];
				double h0 = n[1];
				double w0 = n[2];
				Islands.climate(h[i], away[i] * step, t0, h0, w0, wx, wz, cl.seed(), dials, cell);
				boolean labelled = mushroom != null && Math.hypot(wx - mushroom.x(), wz - mushroom.z()) <= 1.15 * mushroom.radius();
				if (labelled) {
					Islands.mushroomClimate(cell);
				}
				Climate.TargetPoint p = Climate.target((float) cell.temperature, (float) cell.humidity, (float) cell.continentalness, (float) cell.erosion, 0.0f, (float) cell.weirdness);
				String name = cl.source().getNoiseBiome(p).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				String feature = tag == null ? null : tag.at(wx, wz);
				if (feature != null) {
					name = feature;
				}
				counts.merge(name, 1, Integer::sum);
				land++;
				int xl = Math.max(0, x - 1);
				int xr = Math.min(SIZE - 1, x + 1);
				int zu = Math.max(0, z - 1);
				int zd = Math.min(SIZE - 1, z + 1);
				double dx = (h[z * SIZE + xr] - h[z * SIZE + xl]) / ((xr - xl) * step);
				double dz = (h[zd * SIZE + x] - h[zu * SIZE + x]) / ((zd - zu) * step);
				double dot = (dx * 0.5 + dz * 0.5 + 0.7071) / Math.sqrt(dx * dx + dz * dz + 1.0);
				double shade = Math.max(0.0, dot) / 0.7071;
				int base = EarthBiomePreviewTest.colourOf(name);
				double k = 0.55 + 0.6 * shade;
				int r = (int) Math.max(0, Math.min(255, ((base >> 16) & 255) * k));
				int g = (int) Math.max(0, Math.min(255, ((base >> 8) & 255) * k));
				int b = (int) Math.max(0, Math.min(255, (base & 255) * k));
				img.setRGB(x, z, (r << 16) | (g << 8) | b);
				if (water != null) {
					double level = water.at(wx, wz);
					if (!Double.isNaN(level)) {
						img.setRGB(x, z, EarthImages.mix(0x62B6E8, 0x143F7A, Math.min(1.0, (level - h[i]) / 30.0)));
					}
				}
			}
		}
		if (biomeShare != null && land > 0) {
			for (Map.Entry<String, Integer> e : counts.entrySet()) {
				biomeShare.put(e.getKey(), 100.0 * e.getValue() / land);
			}
		}
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 14));
		g.drawString(label, 8, 20);
		g.setFont(new Font("SansSerif", Font.PLAIN, 12));
		g.drawString(String.format("%.1f km square; ground up to %.0f blocks above sea", 2.0 * half / 1000.0, max(h)), 8, SIZE - 8);
		g.dispose();
		return img;
	}

	private static double max(double[] a) {
		double m = -1.0e9;
		for (double v : a) {
			m = Math.max(m, v);
		}
		return m;
	}

	private static String describe(Island is) {
		String what = switch (is.kind()) {
			case VOLCANO -> "young volcano";
			case OLD_VOLCANO -> "old, worn volcano";
			case ATOLL -> "atoll";
			case MUSHROOM -> "mushroom island";
			case FRAGMENT -> "fragment in a sea on a plate";
		};
		String how = switch (is.origin()) {
			case HOTSPOT -> String.format(", number %d of a hotspot chain of %d", is.chain() + 1, is.chainSize());
			case ARC -> ", on an island arc";
			case MYSTERY -> "";
			case SEA -> "";
		};
		return String.format("%s%s: radius %.0f, crest %.0f", what, how, is.radius(), is.crest());
	}

	// ---- the run -----------------------------------------------------------------------------------------------------------

	@Test
	void render() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		Tectonics tec = new Tectonics(cfg);
		Islands.Dials dials = Islands.Dials.fromProperties();
		Islands islands = new Islands(tec, dials);
		String name = "earth_" + cfg.seed();

		// ---- the map and the count
		long start = System.nanoTime();
		Map<Long, Island> found = islandsIn(islands, 0.0, 0.0, 100_000.0);
		EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
		for (Island is : found.values()) {
			counts.merge(is.kind(), 1, Integer::sum);
		}
		System.out.printf("[islands] seed %d, 200 km square: %d islands in %.1f s: %s%n", cfg.seed(), found.size(), (System.nanoTime() - start) / 1e9, counts);
		BufferedImage map = map(tec, islands, 0.0, 0.0, found);
		ImageIO.write(map, "png", new File(out, name + "_islands_map.png"));

		// ---- close-ups: one of each kind, the best of its kind (biggest crest, or biggest radius for atolls and mushrooms)
		Map<Kind, Island> pick = new EnumMap<>(Kind.class);
		Island arc = null;
		for (Island is : found.values()) {
			if (is.origin() == Islands.Origin.ARC && (arc == null || is.crest() > arc.crest()) && is.kind() == Kind.VOLCANO) {
				arc = is;
			}
			if (is.origin() == Islands.Origin.ARC) {
				continue;
			}
			Island best = pick.get(is.kind());
			double score = is.kind() == Kind.VOLCANO || is.kind() == Kind.OLD_VOLCANO ? is.crest() : is.radius();
			if (best == null || score > (best.kind() == Kind.VOLCANO || best.kind() == Kind.OLD_VOLCANO ? best.crest() : best.radius())) {
				pick.put(is.kind(), is);
			}
		}
		Climates cl = climates(cfg.seed());
		List<BufferedImage> panels = new ArrayList<>();
		List<Island> shown = new ArrayList<>();
		for (Kind kind : new Kind[] { Kind.VOLCANO, Kind.OLD_VOLCANO, Kind.ATOLL, Kind.MUSHROOM }) {
			if (pick.containsKey(kind)) {
				shown.add(pick.get(kind));
			}
		}
		if (arc != null) {
			shown.add(arc);
		}
		for (Island is : shown) {
			Map<String, Double> share = new LinkedHashMap<>();
			BufferedImage panel = panel(cl, is, (wx, wz) -> islands.height(wx, wz, Islands.OCEAN_FLOOR), (wx, wz) -> islands.craterWater(wx, wz, Islands.OCEAN_FLOOR), tagged(islands), describe(is), share);
			panels.add(panel);
			List<Map.Entry<String, Double>> top = new ArrayList<>(share.entrySet());
			top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
			StringBuilder line = new StringBuilder();
			for (int k = 0; k < Math.min(5, top.size()); k++) {
				line.append(String.format("%s %.0f%%, ", top.get(k).getKey(), top.get(k).getValue()));
			}
			System.out.printf("[islands]   %s; land biomes: %s%n", describe(is), line);
		}

		// ---- the longest hotspot chain, whole
		Map<String, List<Island>> chains = new LinkedHashMap<>();
		for (Island is : found.values()) {
			if (is.origin() == Islands.Origin.HOTSPOT) {
				chains.computeIfAbsent(is.dirX() + "," + is.dirZ(), k -> new ArrayList<>()).add(is);
			}
		}
		List<Island> longest = null;
		for (List<Island> chain : chains.values()) {
			if (longest == null || chain.size() > longest.size()) {
				longest = chain;
			}
		}
		if (longest != null) {
			double lowX = 1.0e18;
			double highX = -1.0e18;
			double lowZ = 1.0e18;
			double highZ = -1.0e18;
			for (Island is : longest) {
				lowX = Math.min(lowX, is.x());
				highX = Math.max(highX, is.x());
				lowZ = Math.min(lowZ, is.z());
				highZ = Math.max(highZ, is.z());
			}
			double half = Math.max(highX - lowX, highZ - lowZ) / 2.0 + 3_500.0;
			longest.sort((a, b) -> Integer.compare(a.chain(), b.chain()));
			StringBuilder ages = new StringBuilder();
			for (Island is : longest) {
				ages.append(is.kind() == Kind.VOLCANO ? "young volcano" : is.kind() == Kind.OLD_VOLCANO ? "old volcano" : "atoll").append(", ");
			}
			Map<String, Double> share = new LinkedHashMap<>();
			panels.add(render(cl, (lowX + highX) / 2.0, (lowZ + highZ) / 2.0, half, null, (wx, wz) -> islands.height(wx, wz, Islands.OCEAN_FLOOR), (wx, wz) -> islands.craterWater(wx, wz, Islands.OCEAN_FLOOR), tagged(islands),
				String.format("a hotspot chain of %d islands (%d shown), youngest first", longest.get(0).chainSize(), longest.size()), share));
			System.out.printf("[islands]   chain: %s%n", ages);
		}

		// ---- a fragment in a sea on a plate, from the simulated window
		Continent c = ContinentSimulator.run(cfg);
		List<Island> fragments = Islands.inWindow(c, dials);
		System.out.printf("[islands] window of seed %d: %d fragments in its seas%n", cfg.seed(), fragments.size());
		if (!fragments.isEmpty()) {
			Island f = fragments.get(0);
			int gx = (int) Math.round((f.x() - c.x0) / c.cell);
			int gz = (int) Math.round((f.z() - c.z0) / c.cell);
			double floor = Continent.blocksAbove(c.height[Math.max(0, Math.min(c.nz - 1, gz)) * c.nx + Math.max(0, Math.min(c.nx - 1, gx))]);
			Ground ground = (wx, wz) -> {
				int ix = Math.max(0, Math.min(c.nx - 1, (int) Math.round((wx - c.x0) / c.cell)));
				int iz = Math.max(0, Math.min(c.nz - 1, (int) Math.round((wz - c.z0) / c.cell)));
				return Math.max(Continent.blocksAbove(c.height[iz * c.nx + ix]), Islands.profile(f, wx, wz, floor));
			};
			Map<String, Double> share = new LinkedHashMap<>();
			panels.add(panel(cl, f, ground, describe(f), share));
			System.out.printf("[islands]   %s; land biomes: %s%n", describe(f), share);
		}
		int columns = 3;
		int rows = (panels.size() + columns - 1) / columns;
		BufferedImage sheet = new BufferedImage(columns * SIZE, rows * SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D sg = sheet.createGraphics();
		for (int k = 0; k < panels.size(); k++) {
			sg.drawImage(panels.get(k), (k % columns) * SIZE, (k / columns) * SIZE, null);
		}
		sg.dispose();
		ImageIO.write(sheet, "png", new File(out, name + "_islands_closeups.png"));
		System.out.println("[islands] wrote " + new File(out, name + "_islands_map.png").getAbsolutePath());
	}

	/** How often mushroom islands come, against vanilla: islands of a fifth of a square kilometre and more are 1 per 900 km2 of the whole world and 1 per 260 km2 of its ocean. */
	@Test
	void mushroomRate() {
		double total = 0.0;
		double qualifyingTotal = 0.0;
		int islandsTotal = 0;
		for (long seed : new long[] { 4242L, 11L, 7L }) {
			EarthConfig cfg = EarthConfig.defaults(seed);
			Tectonics tec = new Tectonics(cfg);
			Islands islands = new Islands(tec, Islands.Dials.fromProperties());
			double half = 150_000.0;
			int qualifying = 0;
			int oceanic = 0;
			int samples = 0;
			PlateGrid.Hit hit = new PlateGrid.Hit();
			for (double z = -half; z < half; z += 2_000.0) {
				for (double x = -half; x < half; x += 2_000.0) {
					samples++;
					oceanic += tec.plateAt(x, z, hit).continental() ? 0 : 1;
					qualifying += islands.qualifies(x, z) ? 1 : 0;
				}
			}
			Map<Long, Islands.Island> found = islandsIn(islands, 0.0, 0.0, half);
			int mushrooms = 0;
			EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
			for (Island is : found.values()) {
				mushrooms += is.kind() == Kind.MUSHROOM ? 1 : 0;
				counts.merge(is.kind(), 1, Integer::sum);
			}
			double area = 300.0 * 300.0;
			double qualifyingKm2 = area * qualifying / samples;
			System.out.printf("[rate] seed %d, 300 km square: oceanic crust %.1f%%, open ocean for mushroom islands %.0f km2 (%.1f%%); islands %s; mushroom islands %d: 1 per %.0f km2 of that ocean, 1 per %.0f km2 of the world (vanilla: 1 per 260 and 1 per 900)%n",
				seed, 100.0 * oceanic / samples, qualifyingKm2, 100.0 * qualifying / samples, counts, mushrooms, qualifyingKm2 / Math.max(1, mushrooms), area / Math.max(1, mushrooms));
			total += area;
			qualifyingTotal += qualifyingKm2;
			islandsTotal += mushrooms;
		}
		System.out.printf("[rate] all three: %d mushroom islands: 1 per %.0f km2 of open ocean, 1 per %.0f km2 of the world%n", islandsTotal, qualifyingTotal / islandsTotal, total / islandsTotal);
	}
}
