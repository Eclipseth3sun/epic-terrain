package com.epicterrain.worldgen;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;
import com.epicterrain.earth.Wetlands;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Draws the biomes of a whole Earth world (the rarer ones picked out) and a close-up of its best marsh. Nothing happens unless {@code -Depicterrain.gapPreview=SEED} is
 * given; the images go to the preview folder as {@code biomes_SEED.png} and {@code marsh_SEED.png}.
 */
@Tag("preview")
class BiomeGapPreviewTest {
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);

	private static Color colour(String id, double ground) {
		return switch (id) {
			case "swamp" -> new Color(86, 96, 30);
			case "mangrove_swamp" -> new Color(150, 40, 60);
			case "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_savanna" -> new Color(110, 140, 190);
			case "stony_shore" -> new Color(70, 62, 58);
			case "stony_peaks" -> new Color(235, 130, 40);
			case "frozen_peaks", "jagged_peaks" -> new Color(255, 255, 255);
			case "snowy_slopes", "grove" -> new Color(225, 235, 245);
			case "deep_ocean", "deep_cold_ocean", "deep_lukewarm_ocean" -> new Color(14, 36, 110);
			case "deep_frozen_ocean" -> new Color(70, 100, 160);
			case "ocean", "cold_ocean", "lukewarm_ocean" -> new Color(50, 100, 200);
			case "frozen_ocean" -> new Color(150, 185, 225);
			case "warm_ocean" -> new Color(50, 170, 200);
			case "river", "frozen_river" -> new Color(100, 150, 240);
			case "beach", "snowy_beach" -> new Color(235, 225, 160);
			case "desert" -> new Color(228, 205, 130);
			case "badlands", "eroded_badlands", "wooded_badlands" -> new Color(190, 110, 60);
			case "jungle", "sparse_jungle", "bamboo_jungle" -> new Color(40, 125, 50);
			case "snowy_plains", "ice_spikes", "snowy_taiga" -> new Color(205, 220, 235);
			case "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga" -> new Color(70, 105, 90);
			case "savanna", "savanna_plateau" -> new Color(190, 180, 90);
			case "meadow", "cherry_grove" -> new Color(160, 210, 150);
			case "dark_forest", "pale_garden" -> new Color(40, 80, 40);
			case "mushroom_fields" -> new Color(160, 110, 160);
			default -> id.contains("forest") ? new Color(80, 140, 70) : id.contains("plains") ? new Color(150, 195, 110) : new Color(130, 160, 110);
		};
	}

	@Test
	void draw() throws Exception {
		String seeds = System.getProperty("epicterrain.gapPreview");
		if (seeds == null) {
			return;
		}
		for (String s : seeds.split(",")) {
			draw(Long.parseLong(s.trim()));
			EarthWorlds.clear();
		}
	}

	private static void draw(long seed) throws Exception {
		WorldgenHarness harness = WorldgenHarness.shared();
		WorldPreset preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
		EarthChunkGenerator generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
		RandomState state = harness.randomState(seed, generator.generatorSettings().value());
		generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		EarthWorld world = generator.world();
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		File dir = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		dir.mkdirs();
		double[] w = world.window();
		double[] out = new double[3];

		// the whole world, 64 blocks to a pixel, with a little of the ocean round it
		int step = 64;
		int half = 21_000;
		int n = 2 * half / step;
		BufferedImage biomes = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
		Map<String, Integer> counts = new TreeMap<>();
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int x = -half + i * step;
				int z = -half + j * step;
				world.sampleBlock(x, z, out);
				int y = (int) Math.ceil(63 + Math.max(out[0], -30.0)) + 4;
				String id = resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				counts.merge(id, 1, Integer::sum);
				Color c = colour(id, out[0]);
				if (out[0] > 0.0) {
					double shade = Math.max(-0.2, Math.min(0.25, (out[0] - 30.0) / 800.0));
					c = new Color(clamp(c.getRed() * (1 + shade)), clamp(c.getGreen() * (1 + shade)), clamp(c.getBlue() * (1 + shade)));
				}
				biomes.setRGB(i, j, c.getRGB());
			}
		}
		Graphics2D g = biomes.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font("SansSerif", Font.BOLD, 12));
		String[][] legend = { { "swamp", "swamp" }, { "mangrove_swamp", "mangrove swamp" }, { "windswept_hills", "windswept" }, { "stony_shore", "stony shore" }, { "stony_peaks", "stony peaks" },
			{ "frozen_peaks", "snowy peaks" }, { "deep_ocean", "deep ocean" }, { "ocean", "ocean" } };
		int ly = 10;
		g.setColor(new Color(0, 0, 0, 150));
		g.fillRect(6, 4, 130, 18 * legend.length + 10);
		for (String[] l : legend) {
			g.setColor(colour(l[0], 1));
			g.fillRect(12, ly + 2, 14, 12);
			g.setColor(Color.WHITE);
			g.drawString(l[1], 32, ly + 13);
			ly += 18;
		}
		g.setColor(Color.WHITE);
		g.drawString(String.format("seed %d, 64 blocks a pixel, spawn at the middle", seed), 8, n - 8);
		g.dispose();
		ImageIO.write(biomes, "png", new File(dir, "biomes_" + seed + ".png"));

		// the best marsh, 2 blocks to a pixel over 1,792 blocks: ground shaded, water blue, swamp tinted
		int[] best = null;
		double bestWeight = 0.0;
		for (int x = (int) w[0]; x < w[1]; x += 48) {
			for (int z = (int) w[2]; z < w[3]; z += 48) {
				double m = world.wetland(x, z);
				if (m > bestWeight) {
					bestWeight = m;
					best = new int[] { x, z };
				}
			}
		}
		if (best != null) {
			int size = 896;
			BufferedImage marsh = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
			for (int j = 0; j < size; j++) {
				for (int i = 0; i < size; i++) {
					int x = best[0] - size + 2 * i;
					int z = best[1] - size + 2 * j;
					world.sampleBlock(x, z, out);
					boolean river = out[2] != 0;
					boolean water = out[0] <= -1.0 || !Double.isNaN(out[1]);
					boolean swamp = Wetlands.isMarsh(world.wetland(x, z));
					Color c;
					if (water) {
						c = river ? new Color(70, 120, 220) : swamp ? new Color(60, 90, 90) : new Color(50, 100, 200);
					} else {
						double shade = Math.max(-0.25, Math.min(0.3, (out[0] - 6.0) / 60.0));
						Color base = swamp ? new Color(96, 108, 40) : new Color(140, 190, 100);
						c = new Color(clamp(base.getRed() * (1 + shade)), clamp(base.getGreen() * (1 + shade)), clamp(base.getBlue() * (1 + shade)));
					}
					marsh.setRGB(i, j, c.getRGB());
				}
			}
			Graphics2D mg = marsh.createGraphics();
			mg.setFont(new Font("SansSerif", Font.BOLD, 12));
			mg.setColor(Color.WHITE);
			mg.drawString(String.format("marsh at %d, %d: 1,792 blocks across, olive = swamp ground, grey-green = swamp water, bright = ordinary land and sea", best[0], best[1]), 8, size - 8);
			mg.dispose();
			ImageIO.write(marsh, "png", new File(dir, "marsh_" + seed + ".png"));
		}
		System.out.println("biome map written; surface cells by biome: " + counts);
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, v));
	}
}
