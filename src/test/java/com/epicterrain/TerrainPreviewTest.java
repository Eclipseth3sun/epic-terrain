package com.epicterrain;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.worldgen.ConfigSubstitution;
import com.epicterrain.worldgen.EpicAmount;
import com.epicterrain.worldgen.EpicChunkGenerator;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Renders top-down height and biome maps for a world preset into {@code build/preview}. Not part of the
 * normal test run; use {@code ./gradlew preview}.
 */
@Tag("preview")
class TerrainPreviewTest {
	private static final long SEED = Long.getLong("epicterrain.seed", 4242L);

	@Test
	void render() throws Exception {
		WorldgenHarness harness = WorldgenHarness.shared();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();

		String only = System.getProperty("epicterrain.only");
		int cx = Integer.getInteger("epicterrain.cx", 0);
		int cz = Integer.getInteger("epicterrain.cz", 0);
		for (Target target : new Target[] {
			new Target("vanilla", "minecraft:overworld", "minecraft:normal"),
			new Target("epic", "epicterrain:overworld", "epicterrain:epic")
		}) {
			if (only != null && !only.equals(target.name)) {
				continue;
			}
			Optional<NoiseGeneratorSettings> settings = harness.settings(target.settingsId);
			if (settings.isEmpty()) {
				System.out.println("[preview] skipping " + target.name + " (no settings " + target.settingsId + ")");
				continue;
			}
			// -Depicterrain.amount=NN previews the epic world with the slider at NN%.
			Integer amount = Integer.getInteger("epicterrain.amount");
			if (amount != null && target.name.equals("epic")) {
				settings = Optional.of(EpicChunkGenerator.derive(settings.get(), amount));
				System.out.println("[preview] epic world with the slider at " + amount + "%");
			}
			RandomState state = harness.randomState(SEED, settings.get());
			BiomeSource biomes = biomeSource(harness, target.presetId);

			// Wide overview (blocks per pixel) and a closer look at the origin region.
			// The wide view uses the cheap surface estimate; the close view scans the real density for exact terrain.
			int wide = Integer.getInteger("epicterrain.wpp", 32);
			renderView(out, target, settings.get(), state, biomes, cx, cz, wide, 512, false, "wide_" + cx + "_" + cz);
			int mid = Integer.getInteger("epicterrain.mpp", 12);
			renderView(out, target, settings.get(), state, biomes, cx, cz, mid, 256, Boolean.getBoolean("epicterrain.exact"), "mid");
			String fields = System.getProperty("epicterrain.fields");
			if (fields != null) {
				for (String field : fields.split(",")) {
					renderField(out, target, harness, state, cx, cz, field.trim());
				}
			}
			if (Boolean.getBoolean("epicterrain.slice")) {
				renderSlice(out, target, settings.get(), state, cx, cz, Integer.getInteger("epicterrain.sliceWidth", 8192), "slice");
			}
		}
	}

	/** Colour map of one 2D density function over the wide view (e.g. range/uplift, range/d, region/mask). */
	private void renderField(File out, Target target, WorldgenHarness harness, RandomState state, int cx, int cz, String id) throws Exception {
		Holder.Reference<DensityFunction> holder = harness.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id.contains(":") ? id : "epicterrain:" + id)));
		DensityFunction fn = holder.value();
		Integer amount = Integer.getInteger("epicterrain.amount");
		if (amount != null && target.name.equals("epic")) {
			fn = new ConfigSubstitution(EpicChunkGenerator.RANGE_OCCUPANCY,
				new net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction(EpicAmount.occupancy(amount)))
				.rewrite(new net.minecraft.world.level.levelgen.densityfunction.DensityFunctions.HolderHolder(holder));
		}
		DensityFunction function = fn;
		int size = 512;
		int bpp = Integer.getInteger("epicterrain.fieldBpp", 32);
		float[] values = new float[size * size];
		IntStream.range(0, size).parallel().forEach(py -> {
			for (int px = 0; px < size; px++) {
				values[py * size + px] = state.sampleBlockValueUncached(function, cx + (px - size / 2) * bpp, 0, cz + (py - size / 2) * bpp);
			}
		});
		float lo = Float.MAX_VALUE;
		float hi = -Float.MAX_VALUE;
		for (float v : values) {
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < values.length; i++) {
			float t = hi > lo ? (values[i] - lo) / (hi - lo) : 0f;
			img.setRGB(i % size, i / size, new Color(clamp(t * 2f), clamp(t * 2f - 0.6f), clamp(t * 4f - 3f) + 0.15f > 1f ? 1f : clamp(t * 4f - 3f) + 0.15f).getRGB());
		}
		File file = new File(out, target.name + "_field_" + id.replace('/', '_').replace(':', '_') + ".png");
		ImageIO.write(img, "png", file);
		System.out.printf("[preview] field %s: min=%.3f max=%.3f (%d blocks/px) -> %s%n", id, lo, hi, bpp, file);
	}

	/** Vertical west-east cross-section at z through the true final density: solid rock, water, and sky. */
	private void renderSlice(File out, Target target, NoiseGeneratorSettings settings, RandomState state, int centerX, int z, int widthBlocks, String label)
		throws Exception {
		int minY = settings.noiseSettings().minY();
		int maxY = minY + settings.noiseSettings().height();
		int cols = 768;
		int step = widthBlocks / cols;
		DensityFunction finalDensity = settings.noiseRouter().finalDensity();
		int sea = settings.seaLevel();
		BufferedImage img = new BufferedImage(cols, maxY - minY, BufferedImage.TYPE_INT_RGB);
		int x0 = centerX - widthBlocks / 2;
		IntStream.range(0, cols).parallel().forEach(c -> {
			int x = x0 + c * step;
			for (int y = minY; y < maxY; y++) {
				boolean solid = state.sampleBlockValueUncached(finalDensity, x, y, z) > 0;
				int row = maxY - 1 - y;
				Color color;
				if (solid) {
					color = y < 0 ? new Color(70, 70, 78) : y < 280 ? new Color(120, 118, 110) : new Color(185, 188, 196);
				} else if (y <= sea) {
					color = new Color(40, 90, 190);
				} else {
					float t = (y - minY) / (float) (maxY - minY);
					color = new Color((int) (150 + 60 * t), (int) (190 + 40 * t), (int) (235 + 15 * t));
				}
				img.setRGB(c, row, color.getRGB());
			}
		});
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(255, 255, 255, 90));
		for (int y = 0; y <= maxY; y += 100) {
			g.drawLine(0, maxY - 1 - y, cols, maxY - 1 - y);
			g.drawString("y" + y, 3, maxY - 3 - y);
		}
		g.dispose();
		File file = new File(out, target.name + "_" + label + "_x" + x0 + "_z" + z + ".png");
		ImageIO.write(img, "png", file);
		System.out.println("[preview] wrote slice " + file + " (" + step + " blocks/px horizontally, 1 block/px vertically)");
	}

	private record Target(String name, String settingsId, String presetId) {}

	private static BiomeSource biomeSource(WorldgenHarness harness, String presetId) {
		ResourceKey<WorldPreset> key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse(presetId));
		Optional<Holder.Reference<WorldPreset>> preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET).get(key);
		if (preset.isEmpty()) {
			return null;
		}
		LevelStem overworld = preset.get().value().overworld().orElseThrow();
		return overworld.generator().getBiomeSource();
	}

	private void renderView(File out, Target target, NoiseGeneratorSettings settings, RandomState state, BiomeSource biomes,
		int centerX, int centerZ, int blocksPerPixel, int size, boolean exact, String label) throws Exception {
		int minY = -64;
		int maxY = minY + settings.noiseSettings().height();
		DensityFunction finalDensity = settings.noiseRouter().finalDensity();
		DensityFunction surfaceLevel = settings.noiseRouter().chunkSurfaceLevel();
		Climate.Sampler climate = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		BiomeResolver resolver = biomes == null ? null : biomes.createResolver(climate);

		float[] height = new float[size * size];
		String[] biomeAt = new String[size * size];
		int originX = centerX - size * blocksPerPixel / 2;
		int originZ = centerZ - size * blocksPerPixel / 2;
		long t0 = System.currentTimeMillis();

		IntStream.range(0, size).parallel().forEach(py -> {
			for (int px = 0; px < size; px++) {
				int x = originX + px * blocksPerPixel;
				int z = originZ + py * blocksPerPixel;
				// Start the scan just above the cheap surface estimate, then walk down to the real surface.
				float estimate = state.sampleBlockValueUncached(surfaceLevel, x, 0, z);
				int y = (int) estimate;
				if (exact) {
					y = (int) Math.min(maxY - 1, estimate + 96);
					while (y > minY && state.sampleBlockValueUncached(finalDensity, x, y, z) <= 0) {
						y -= 2;
					}
				}
				height[py * size + px] = y;
				if (biomes != null) {
					Holder<Biome> biome = resolver.getNoiseBiome(x >> 2, Math.max(y, 63) >> 2, z >> 2);
					biomeAt[py * size + px] = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				}
			}
		});

		int sea = settings.seaLevel();
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		BufferedImage biomeImg = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				float h = height[py * size + px];
				float west = height[py * size + Math.max(0, px - 1)];
				float north = height[Math.max(0, py - 1) * size + px];
				float shade = (float) ((west - h) + (north - h)) / (blocksPerPixel * 1.2f);
				img.setRGB(px, py, hypsometric(h, sea, shade).getRGB());
				String b = biomeAt[py * size + px];
				if (b != null) {
					counts.computeIfAbsent(b, k -> new AtomicInteger()).incrementAndGet();
					biomeImg.setRGB(px, py, biomeColor(b, h, sea).getRGB());
				}
			}
		}
		File heightFile = new File(out, target.name + "_" + label + "_height.png");
		ImageIO.write(img, "png", heightFile);
		if (biomes != null) {
			ImageIO.write(biomeImg, "png", new File(out, target.name + "_" + label + "_biomes.png"));
		}

		int best = 0;
		for (int i = 1; i < height.length; i++) {
			if (height[i] > height[best]) {
				best = i;
			}
		}
		System.out.printf("[preview]   highest pixel: y=%.0f at x=%d z=%d%n", height[best],
			originX + (best % size) * blocksPerPixel, originZ + (best / size) * blocksPerPixel);

		float[] sorted = height.clone();
		Arrays.sort(sorted);
		long landAbove256 = IntStream.range(0, height.length).filter(i -> height[i] > 256).count();
		System.out.printf("[preview] %s/%s: %d blocks/px, %d x %d blocks, %.1fs%n",
			target.name, label, blocksPerPixel, size * blocksPerPixel, size * blocksPerPixel, (System.currentTimeMillis() - t0) / 1000.0);
		System.out.printf("[preview]   height min=%.0f p10=%.0f p50=%.0f p90=%.0f p99=%.0f max=%.0f; above y256: %.2f%%%n",
			sorted[0], sorted[(int) (sorted.length * 0.10)], sorted[sorted.length / 2], sorted[(int) (sorted.length * 0.90)],
			sorted[(int) (sorted.length * 0.99)], sorted[sorted.length - 1], 100.0 * landAbove256 / height.length);
		Map<String, Integer> top = new TreeMap<>();
		counts.forEach((k, v) -> top.put(k, v.get()));
		top.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(14)
			.forEach(e -> System.out.printf("[preview]   biome %-28s %5.1f%%%n", e.getKey(), 100.0 * e.getValue() / (size * size)));
		System.out.println("[preview]   wrote " + heightFile);
	}

	/** Blue for water by depth, then green -> tan -> brown -> grey -> white by altitude, lit from the north-west. */
	private static Color hypsometric(float h, int sea, float shade) {
		float r;
		float g;
		float b;
		if (h < sea) {
			float depth = Math.min(1f, (sea - h) / 60f);
			r = 0.10f - 0.06f * depth;
			g = 0.35f - 0.20f * depth;
			b = 0.70f - 0.25f * depth;
		} else {
			float[][] stops = {
				{ 0f, 0.20f, 0.50f, 0.20f },
				{ 90f, 0.45f, 0.65f, 0.30f },
				{ 160f, 0.75f, 0.70f, 0.40f },
				{ 240f, 0.55f, 0.42f, 0.30f },
				{ 340f, 0.55f, 0.55f, 0.55f },
				{ 430f, 0.85f, 0.85f, 0.88f },
				{ 512f, 1.00f, 1.00f, 1.00f }
			};
			float rel = h - sea;
			int i = 0;
			while (i < stops.length - 2 && rel > stops[i + 1][0]) {
				i++;
			}
			float t = Math.max(0f, Math.min(1f, (rel - stops[i][0]) / (stops[i + 1][0] - stops[i][0])));
			r = stops[i][1] + (stops[i + 1][1] - stops[i][1]) * t;
			g = stops[i][2] + (stops[i + 1][2] - stops[i][2]) * t;
			b = stops[i][3] + (stops[i + 1][3] - stops[i][3]) * t;
		}
		float light = 1f + Math.max(-0.45f, Math.min(0.45f, shade * 0.9f));
		return new Color(clamp(r * light), clamp(g * light), clamp(b * light));
	}

	private static float clamp(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static Color biomeColor(String biome, float h, int sea) {
		if (biome.contains("ocean")) {
			return new Color(30, 60, 160);
		}
		if (biome.contains("river")) {
			return new Color(60, 120, 220);
		}
		if (biome.startsWith("foothills_")) {
			return new Color(255, 150, 0);
		}
		if (biome.equals("epic_range_peaks")) {
			return new Color(0, 255, 255);
		}
		if (biome.equals("epic_wall_peaks")) {
			return new Color(255, 0, 200);
		}
		if (biome.contains("frozen_peaks") || biome.contains("snowy_slopes")) {
			return new Color(245, 248, 255);
		}
		if (biome.contains("jagged_peaks")) {
			return new Color(200, 205, 215);
		}
		if (biome.contains("stony_peaks") || biome.contains("stony_shore")) {
			return new Color(140, 140, 140);
		}
		if (biome.contains("grove")) {
			return new Color(120, 170, 150);
		}
		if (biome.contains("meadow")) {
			return new Color(150, 210, 110);
		}
		if (biome.contains("windswept")) {
			return new Color(110, 130, 100);
		}
		int hash = biome.hashCode();
		return new Color(60 + (hash & 0x7F), 60 + ((hash >> 7) & 0x9F), 40 + ((hash >> 14) & 0x7F));
	}
}
