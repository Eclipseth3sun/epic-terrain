package com.epicterrain.earth;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.epicterrain.WorldgenHarness;

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
 * Asks vanilla's own biome table what grows on a simulated continent: height cools the climate and steers the mountain zones
 * ({@link BiomeClimate}), vanilla's noise supplies the regional variety, and the real multi-noise biome source answers for every cell.
 * Draws the map with a legend and prints which biomes each height band got. Options: {@code -Depicterrain.earth.lapse / rainbias / variety}.
 */
@Tag("preview")
class EarthBiomePreviewTest {
	private static final Map<String, Integer> COLOURS = new HashMap<>();

	static {
		String[][] table = {
			{ "ocean", "1E5FA8" }, { "deep_ocean", "0F3A78" }, { "cold_ocean", "2A6EB0" }, { "deep_cold_ocean", "15457F" }, { "lukewarm_ocean", "2D8FC8" },
			{ "deep_lukewarm_ocean", "1B6AA0" }, { "warm_ocean", "38B0D0" }, { "deep_warm_ocean", "2790B0" }, { "frozen_ocean", "7FA8D8" }, { "deep_frozen_ocean", "5F88B8" },
			{ "beach", "E6D79A" }, { "snowy_beach", "EDEDE0" }, { "stony_shore", "8C8C84" }, { "river", "3F80D0" }, { "frozen_river", "A8C8F0" },
			{ "plains", "9CCB5A" }, { "sunflower_plains", "D6D850" }, { "snowy_plains", "F2F6FA" }, { "ice_spikes", "BFE4F4" },
			{ "forest", "3C8C3C" }, { "flower_forest", "B05AB0" }, { "birch_forest", "6FB060" }, { "old_growth_birch_forest", "4F9050" }, { "dark_forest", "205020" },
			{ "pale_garden", "A0A890" }, { "dappled_forest", "5FA070" },
			{ "taiga", "2F6B55" }, { "snowy_taiga", "7FA898" }, { "old_growth_pine_taiga", "1F5040" }, { "old_growth_spruce_taiga", "2A4A60" },
			{ "savanna", "C9B03C" }, { "savanna_plateau", "B09030" }, { "windswept_savanna", "9C8C50" }, { "desert", "E8D07C" },
			{ "badlands", "D4672B" }, { "eroded_badlands", "E08A50" }, { "wooded_badlands", "A8602A" },
			{ "jungle", "0F7030" }, { "sparse_jungle", "5AA83C" }, { "bamboo_jungle", "70C050" }, { "swamp", "5F7F50" }, { "mangrove_swamp", "4F7040" },
			{ "meadow", "8AD070" }, { "cherry_grove", "F5A8C8" }, { "grove", "C4D8D0" }, { "snowy_slopes", "E8F0F8" },
			{ "jagged_peaks", "A8A8B8" }, { "frozen_peaks", "D0E0F4" }, { "stony_peaks", "9A8F85" },
			{ "windswept_hills", "808878" }, { "windswept_forest", "587058" }, { "windswept_gravelly_hills", "909088" }, { "mushroom_fields", "A070A0" }, { "volcano", "6A2E26" }, { "atoll", "F2E4A8" }, { "crater_lake", "2FA8C8" }
		};
		for (String[] row : table) {
			COLOURS.put(row[0], Integer.parseInt(row[1], 16));
		}
	}

	static int colourOf(String name) {
		Integer known = COLOURS.get(name);
		if (known != null) {
			return known;
		}
		return Color.HSBtoRGB((name.hashCode() & 0xFFFF) / 65535.0f, 0.5f, 0.8f) & 0xFFFFFF;
	}

	private static int shaded(int colour, double shade) {
		double k = 0.62 + 0.5 * shade;
		int r = (int) Math.max(0, Math.min(255, ((colour >> 16) & 255) * k));
		int g = (int) Math.max(0, Math.min(255, ((colour >> 8) & 255) * k));
		int b = (int) Math.max(0, Math.min(255, (colour & 255) * k));
		return (r << 16) | (g << 8) | b;
	}

	private record Resolved(BiomeClimate climate, int[] biome) {
	}

	private static Resolved resolve(Continent c, BiomeClimate.VanillaNoise noise, BiomeClimate.Dials dials, MultiNoiseBiomeSource source, Map<String, Integer> index,
			List<String> names) {
		BiomeClimate bc = BiomeClimate.compute(c, noise, dials);
		int[] biome = new int[c.nx * c.nz];
		for (int i = 0; i < biome.length; i++) {
			Climate.TargetPoint p = Climate.target(bc.temperature[i], bc.humidity[i], bc.continentalness[i], bc.erosion[i], 0.0f, bc.weirdness[i]);
			String name = source.getNoiseBiome(p).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			Integer id = index.get(name);
			if (id == null) {
				id = names.size();
				index.put(name, id);
				names.add(name);
			}
			biome[i] = id;
		}
		return new Resolved(bc, biome);
	}

	private static BufferedImage map(Continent c, int[] biome, List<String> names) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				int i = z * c.nx + x;
				int colour = colourOf(names.get(biome[i]));
				img.setRGB(x, z, c.height[i] >= 0.0f ? shaded(colour, EarthImages.shade(c, x, z)) : colour);
			}
		}
		return img;
	}

	private static void report(String label, Continent c, Resolved r, List<String> names) {
		int n = c.nx * c.nz;
		int land = 0;
		int[] zoneCount = new int[BiomeClimate.ZONE_NAMES.length];
		List<Map<Integer, Integer>> perZone = new ArrayList<>();
		for (int z = 0; z < BiomeClimate.ZONE_NAMES.length; z++) {
			perZone.add(new HashMap<>());
		}
		for (int i = 0; i < n; i++) {
			int zone = r.climate().zone[i];
			zoneCount[zone]++;
			if (zone != BiomeClimate.OCEAN) {
				land++;
				perZone.get(zone).merge(r.biome()[i], 1, Integer::sum);
			}
		}
		StringBuilder shares = new StringBuilder("[biomes] " + label + ": share of land by relief band:");
		for (int z = 1; z < zoneCount.length; z++) {
			shares.append(String.format(" %s %.1f%%;", BiomeClimate.ZONE_NAMES[z], 100.0 * zoneCount[z] / Math.max(1, land)));
		}
		System.out.println(shares);
		// the biomes a mountain should not be without, over all the mountain bands together
		int mountainCells = 0;
		Map<String, Integer> named = new java.util.TreeMap<>();
		for (int z = 2; z < zoneCount.length; z++) {
			mountainCells += zoneCount[z];
			for (Map.Entry<Integer, Integer> e : perZone.get(z).entrySet()) {
				named.merge(names.get(e.getKey()), e.getValue(), Integer::sum);
			}
		}
		StringBuilder wanted = new StringBuilder(String.format("[biomes]   across all mountain bands (%d cells):", mountainCells));
		for (String key : new String[] { "cherry_grove", "meadow", "grove", "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga", "pale_garden", "snowy_slopes", "frozen_peaks", "jagged_peaks", "stony_peaks" }) {
			wanted.append(String.format(" %s %.1f%%;", key, 100.0 * named.getOrDefault(key, 0) / Math.max(1, mountainCells)));
		}
		System.out.println(wanted);
		for (int z = 2; z < zoneCount.length; z++) {
			List<Map.Entry<Integer, Integer>> top = new ArrayList<>(perZone.get(z).entrySet());
			top.sort((a, b) -> b.getValue() - a.getValue());
			StringBuilder line = new StringBuilder(String.format("[biomes]   %-9s (%d kinds):", BiomeClimate.ZONE_NAMES[z], top.size()));
			for (int k = 0; k < Math.min(8, top.size()); k++) {
				line.append(String.format(" %s %.0f%%;", names.get(top.get(k).getKey()), 100.0 * top.get(k).getValue() / Math.max(1, zoneCount[z])));
			}
			System.out.println(line);
		}
	}

	@Test
	void render() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		Continent c = ContinentSimulator.run(cfg);
		int n = c.nx * c.nz;

		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		RandomState state = h.randomState(cfg.seed(), settings);
		Climate.Sampler climate = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		BiomeClimate.VanillaNoise noise = (x, z, o) -> {
			Climate.TargetPoint p = climate.sample((int) Math.floor(x) >> 2, 16, (int) Math.floor(z) >> 2);
			o[0] = Climate.unquantizeCoord(p.temperature());
			o[1] = Climate.unquantizeCoord(p.humidity());
			o[2] = Climate.unquantizeCoord(p.weirdness());
		};
		BiomeClimate.Dials dials = BiomeClimate.Dials.fromProperties();
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		Map<String, Integer> index = new HashMap<>();
		List<String> names = new ArrayList<>();

		long t0 = System.nanoTime();
		Resolved r = resolve(c, noise, dials, source, index, names);
		System.out.printf("[biomes] %s; climate and biomes took %.1fs; dials: lapse %.2f, rain bias %.2f, variety %.2f%n", c.timing, (System.nanoTime() - t0) / 1e9,
			dials.lapse(), dials.rainBias(), dials.variety());
		report("default dials", c, r, names);

		Map<Integer, Integer> landCount = new HashMap<>();
		int land = 0;
		for (int i = 0; i < n; i++) {
			if (c.height[i] >= 0.0f) {
				land++;
				landCount.merge(r.biome()[i], 1, Integer::sum);
			}
		}
		int legend = 330;
		BufferedImage img = new BufferedImage(c.nx + legend, c.nz, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.drawImage(map(c, r.biome(), names), 0, 0, null);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillRect(c.nx, 0, legend, c.nz);
		g.setFont(new Font("SansSerif", Font.PLAIN, 13));
		List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(landCount.entrySet());
		ranked.sort((a, b) -> b.getValue() - a.getValue());
		g.setColor(Color.BLACK);
		g.drawString("Vanilla biomes on land (share of land)", c.nx + 14, 24);
		int y = 46;
		for (int k = 0; k < Math.min(30, ranked.size()); k++) {
			String name = names.get(ranked.get(k).getKey());
			g.setColor(new Color(colourOf(name)));
			g.fillRect(c.nx + 14, y - 12, 16, 14);
			g.setColor(Color.DARK_GRAY);
			g.drawRect(c.nx + 14, y - 12, 16, 14);
			g.setColor(Color.BLACK);
			g.drawString(String.format("%s  %.1f%%", name, 100.0 * ranked.get(k).getValue() / Math.max(1, land)), c.nx + 38, y);
			y += 21;
		}
		g.dispose();
		String name = "earth_" + cfg.seed() + "_c" + cfg.cellBlocks() + "_vanilla_biomes";
		ImageIO.write(img, "png", new File(out, name + ".png"));

		if (Boolean.getBoolean("epicterrain.earth.compare")) {
			// the same range with the variety dial at 0, 0.5 and 1
			int best = 0;
			for (int i = 0; i < n; i++) {
				if (c.height[i] > c.height[best]) {
					best = i;
				}
			}
			int half = 190;
			int cx = Math.max(half, Math.min(c.nx - half, best % c.nx));
			int cz = Math.max(half, Math.min(c.nz - half, best / c.nx));
			BufferedImage[] crops = new BufferedImage[3];
			double[] varieties = { 0.0, 0.5, 1.0 };
			for (int k = 0; k < varieties.length; k++) {
				Resolved v = resolve(c, noise, dials.withVariety(varieties[k]), source, index, names);
				report(String.format("variety %.1f", varieties[k]), c, v, names);
				BufferedImage full = map(c, v.biome(), names);
				BufferedImage crop = new BufferedImage(2 * half * 2, 2 * half * 2, BufferedImage.TYPE_INT_RGB);
				Graphics2D cg = crop.createGraphics();
				cg.drawImage(full.getSubimage(cx - half, cz - half, 2 * half, 2 * half), 0, 0, 2 * half * 2, 2 * half * 2, null);
				cg.setColor(Color.WHITE);
				cg.setFont(new Font("SansSerif", Font.BOLD, 16));
				cg.drawString(String.format("variety %.1f", varieties[k]), 10, 22);
				cg.dispose();
				crops[k] = crop;
			}
			BufferedImage sheet = new BufferedImage(3 * crops[0].getWidth(), crops[0].getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D sg = sheet.createGraphics();
			for (int k = 0; k < 3; k++) {
				sg.drawImage(crops[k], k * crops[0].getWidth(), 0, null);
			}
			sg.dispose();
			ImageIO.write(sheet, "png", new File(out, name + "_variety.png"));
		}
		System.out.println("[biomes] wrote " + new File(out, name + ".png").getAbsolutePath());
	}

	/** One connected patch of equal value: the value, its size in cells and its bounding box in cells. */
	private record Patch(int value, int cells, int minX, int maxX, int minZ, int maxZ) {
	}

	private static List<Patch> patches(int[] value, boolean[] include, int nx, int nz) {
		List<Patch> found = new ArrayList<>();
		boolean[] seen = new boolean[nx * nz];
		int[] queue = new int[nx * nz];
		for (int start = 0; start < seen.length; start++) {
			if (seen[start] || !include[start]) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			seen[start] = true;
			int minX = nx;
			int maxX = 0;
			int minZ = nz;
			int maxZ = 0;
			while (head < tail) {
				int i = queue[head++];
				int x = i % nx;
				int z = i / nx;
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
				int[] around = { x > 0 ? i - 1 : -1, x < nx - 1 ? i + 1 : -1, z > 0 ? i - nx : -1, z < nz - 1 ? i + nx : -1 };
				for (int m : around) {
					if (m >= 0 && !seen[m] && include[m] && value[m] == value[start]) {
						seen[m] = true;
						queue[tail++] = m;
					}
				}
			}
			found.add(new Patch(value[start], tail, minX, maxX, minZ, maxZ));
		}
		return found;
	}

	private static boolean isWater(String name) {
		return name.contains("ocean") || name.contains("river");
	}

	/** Prints how big the patches of each biome are, as the area of the median, the 90th-percentile and the largest patch, in square kilometres. */
	private static void patchReport(String label, List<Patch> patches, List<String> names, double cellKm2, int topCount) {
		Map<Integer, List<Integer>> byBiome = new HashMap<>();
		Map<Integer, Integer> total = new HashMap<>();
		for (Patch p : patches) {
			if (isWater(names.get(p.value()))) {
				continue;
			}
			byBiome.computeIfAbsent(p.value(), k -> new ArrayList<>()).add(p.cells());
			total.merge(p.value(), p.cells(), Integer::sum);
		}
		List<Integer> order = new ArrayList<>(total.keySet());
		order.sort((a, b) -> total.get(b) - total.get(a));
		System.out.println("[size] " + label + ": patches of each land biome (a patch is one connected stretch of a single biome)");
		double allMedian = 0.0;
		List<Integer> everything = new ArrayList<>();
		for (int k = 0; k < Math.min(topCount, order.size()); k++) {
			List<Integer> sizes = byBiome.get(order.get(k));
			sizes.sort(Integer::compare);
			double median = sizes.get(sizes.size() / 2) * cellKm2;
			double p90 = sizes.get((int) (sizes.size() * 0.9)) * cellKm2;
			double biggest = sizes.get(sizes.size() - 1) * cellKm2;
			// area weighted: the patch a visitor standing in this biome is most likely in
			double weighted = 0.0;
			double sum = 0.0;
			for (int cells : sizes) {
				weighted += (double) cells * cells;
				sum += cells;
			}
			double typicalKm2 = weighted / sum * cellKm2;
			System.out.printf("[size]   %-24s %4d patches, median %6.2f km2, 90%% under %6.2f, largest %7.2f; a visitor is in a patch of about %6.1f km2 (%.1f km across)%n",
				names.get(order.get(k)), sizes.size(), median, p90, biggest, typicalKm2, 2.0 * Math.sqrt(typicalKm2 / Math.PI));
		}
		for (List<Integer> sizes : byBiome.values()) {
			everything.addAll(sizes);
		}
		double weighted = 0.0;
		double sum = 0.0;
		for (int cells : everything) {
			weighted += (double) cells * cells;
			sum += cells;
		}
		allMedian = weighted / sum * cellKm2;
		System.out.printf("[size] %s: over all land biomes a visitor is in a patch of about %.1f km2 (%.1f km across)%n", label, allMedian, 2.0 * Math.sqrt(allMedian / Math.PI));
	}

	/** How far you walk, on average, between one biome and the next, along straight lines through the given cells (in kilometres). */
	private static double kmBetweenChanges(int[] biome, boolean[] include, List<String> names, int nx, int nz, double cellKm) {
		long steps = 0;
		long changes = 0;
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x + 1 < nx; x++) {
				int i = z * nx + x;
				if (include[i] && include[i + 1] && !isWater(names.get(biome[i])) && !isWater(names.get(biome[i + 1]))) {
					steps++;
					changes += biome[i] != biome[i + 1] ? 1 : 0;
				}
			}
		}
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z + 1 < nz; z++) {
				int i = z * nx + x;
				if (include[i] && include[i + nx] && !isWater(names.get(biome[i])) && !isWater(names.get(biome[i + nx]))) {
					steps++;
					changes += biome[i] != biome[i + nx] ? 1 : 0;
				}
			}
		}
		return changes == 0 ? 0.0 : steps * cellKm / changes;
	}

	@Test
	void sizes() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		Continent c = ContinentSimulator.run(cfg);
		int n = c.nx * c.nz;
		double cellKm = c.cell / 1000.0;
		double cellKm2 = cellKm * cellKm;

		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		RandomState state = h.randomState(cfg.seed(), settings);
		Climate.Sampler climate = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		BiomeClimate.VanillaNoise noise = (x, z, o) -> {
			Climate.TargetPoint p = climate.sample((int) Math.floor(x) >> 2, 16, (int) Math.floor(z) >> 2);
			o[0] = Climate.unquantizeCoord(p.temperature());
			o[1] = Climate.unquantizeCoord(p.humidity());
			o[2] = Climate.unquantizeCoord(p.weirdness());
		};
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		MultiNoiseBiomeSource source = (MultiNoiseBiomeSource) preset.overworld().orElseThrow().generator().getBiomeSource();
		Map<String, Integer> index = new HashMap<>();
		List<String> names = new ArrayList<>();
		Resolved r = resolve(c, noise, BiomeClimate.Dials.fromProperties(), source, index, names);

		double windowKm = c.nx * cellKm;
		boolean[] land = new boolean[n];
		boolean[] mountain = new boolean[n];
		boolean[] lake = new boolean[n];
		int landCells = 0;
		int mountainCells = 0;
		int[] one = new int[n];
		for (int i = 0; i < n; i++) {
			land[i] = c.height[i] >= 0.0f;
			mountain[i] = land[i] && r.climate().zone[i] >= BiomeClimate.MONTANE;
			lake[i] = c.lakeDepth[i] > 0.0f;
			landCells += land[i] ? 1 : 0;
			mountainCells += mountain[i] ? 1 : 0;
		}
		System.out.printf("[size] the simulated window is %.1f x %.1f km (%.0f km2); the plate at its middle is %.0f km across; blocks: %.0f x %.0f%n",
			windowKm, windowKm, windowKm * windowKm, cfg.plateBlocks() / 1000.0, windowKm * 1000.0, windowKm * 1000.0);
		List<Patch> landmasses = patches(one, land, c.nx, c.nz);
		landmasses.sort((a, b) -> b.cells() - a.cells());
		Patch main = landmasses.get(0);
		System.out.printf("[size] land: %.0f km2 (%.0f%% of the window) in %d landmasses; the main one is %.0f km2, %.1f km east-west by %.1f km north-south (bounding box)%n",
			landCells * cellKm2, 100.0 * landCells / n, landmasses.size(), main.cells() * cellKm2, (main.maxX() - main.minX() + 1) * cellKm, (main.maxZ() - main.minZ() + 1) * cellKm);
		List<Patch> ranges = patches(one, mountain, c.nx, c.nz);
		ranges.sort((a, b) -> b.cells() - a.cells());
		StringBuilder rangeText = new StringBuilder(String.format("[size] mountains (relief over 900 m): %.0f km2 in total; the largest massifs:", mountainCells * cellKm2));
		for (int k = 0; k < Math.min(4, ranges.size()); k++) {
			Patch p = ranges.get(k);
			rangeText.append(String.format(" %.0f km2 (%.1f x %.1f km box);", p.cells() * cellKm2, (p.maxX() - p.minX() + 1) * cellKm, (p.maxZ() - p.minZ() + 1) * cellKm));
		}
		System.out.println(rangeText);
		List<Patch> lakes = patches(one, lake, c.nx, c.nz);
		lakes.sort((a, b) -> b.cells() - a.cells());
		System.out.printf("[size] lakes: %d, the largest %.1f km2 (about %.1f km across)%n", lakes.size(), lakes.isEmpty() ? 0.0 : lakes.get(0).cells() * cellKm2,
			lakes.isEmpty() ? 0.0 : 2.0 * Math.sqrt(lakes.get(0).cells() * cellKm2 / Math.PI));
		int top = 0;
		for (int i = 0; i < n; i++) {
			top = Math.max(top, c.yBlocks(i));
		}
		System.out.printf("[size] highest ground y %d (%d blocks above sea); longest river %.1f km%n", top, top - EarthConfig.SEA_LEVEL_Y, c.metrics.longestRiverBlocks / 1000.0);
		double across = windowKm * 1000.0;
		System.out.printf("[size] crossing %.1f km: on foot %.0f min, sprinting %.0f min, by boat %.0f min, by elytra %.0f min%n", windowKm, across / 4.3 / 60, across / 5.6 / 60, across / 8.0 / 60, across / 30.0 / 60);

		boolean[] landOnly = new boolean[n];
		for (int i = 0; i < n; i++) {
			landOnly[i] = land[i];
		}
		patchReport("this continent", patches(r.biome(), landOnly, c.nx, c.nz), names, cellKm2, 14);
		System.out.printf("[size] walking in a straight line over this continent's land, the biome changes about every %.2f km (%.0f blocks, %.1f minutes on foot)%n",
			kmBetweenChanges(r.biome(), landOnly, names, c.nx, c.nz, cellKm), 1000.0 * kmBetweenChanges(r.biome(), landOnly, names, c.nx, c.nz, cellKm),
			1000.0 * kmBetweenChanges(r.biome(), landOnly, names, c.nx, c.nz, cellKm) / 4.3 / 60.0);
		System.out.printf("[size] the continent has %d different land biomes%n", java.util.stream.IntStream.range(0, n).filter(i -> land[i]).map(i -> r.biome()[i]).distinct().toArray().length);

		// the same ground under vanilla's own biome source, to compare: every cell, and the land biomes only
		int[] vanilla = new int[n];
		boolean[] all = new boolean[n];
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				int i = z * c.nx + x;
				int bx = (int) Math.floor(c.x0 + (x & ~1) * c.cell);
				int bz = (int) Math.floor(c.z0 + (z & ~1) * c.cell);
				String name = source.getNoiseBiome(climate.sample(bx >> 2, 16, bz >> 2)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				Integer id = index.get(name);
				if (id == null) {
					id = names.size();
					index.put(name, id);
					names.add(name);
				}
				vanilla[i] = id;
				all[i] = true;
			}
		}
		patchReport("vanilla's own source over the same square", patches(vanilla, all, c.nx, c.nz), names, cellKm2, 14);
		System.out.printf("[size] vanilla's own source over the same square: the biome changes about every %.2f km (%.0f blocks)%n",
			kmBetweenChanges(vanilla, all, names, c.nx, c.nz, cellKm), 1000.0 * kmBetweenChanges(vanilla, all, names, c.nx, c.nz, cellKm));
	}
}
