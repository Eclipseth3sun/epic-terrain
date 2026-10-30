package com.epicterrain.worldgen;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;

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
 * Draws a stretch of an Earth world without the lowland's rolling hills and with them, side by side, as shaded relief tinted by biome. Nothing happens unless
 * {@code -Depicterrain.hillsPreview=SEED,X,Z} is given (the middle of the view, in blocks); the image goes to the preview folder as {@code hills_SEED.png}.
 */
@Tag("preview")
class HillsPreviewTest {
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);
	private static final int SIZE = 1000;
	private static final int BLOCKS_PER_PIXEL = 10;

	@Test
	void draw() throws Exception {
		String spec = System.getProperty("epicterrain.hillsPreview");
		if (spec == null) {
			return;
		}
		String[] parts = spec.split(",");
		long seed = Long.parseLong(parts[0].trim());
		int cx = Integer.parseInt(parts[1].trim());
		int cz = Integer.parseInt(parts[2].trim());
		BufferedImage both = new BufferedImage(2 * SIZE + 10, SIZE, BufferedImage.TYPE_INT_RGB);
		for (int side = 0; side < 2; side++) {
			System.setProperty("epicterrain.earth.hills", side == 0 ? "0" : "1");
			EarthWorlds.clear();
			BufferedImage one = render(seed, cx, cz, side == 0 ? "the lowland as the simulation made it (hills 0)" : "with the rolling hills (hills 1)");
			both.getGraphics().drawImage(one, side * (SIZE + 10), 0, null);
		}
		System.clearProperty("epicterrain.earth.hills");
		EarthWorlds.clear();
		File dir = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		dir.mkdirs();
		ImageIO.write(both, "png", new File(dir, "hills_" + seed + ".png"));
	}

	private static BufferedImage render(long seed, int cx, int cz, String title) {
		WorldgenHarness harness = WorldgenHarness.shared();
		WorldPreset preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
		EarthChunkGenerator generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
		RandomState state = harness.randomState(seed, generator.generatorSettings().value());
		generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		EarthWorld world = generator.world();
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		int n = SIZE + 2;
		double[][] ground = new double[n][n];
		boolean[][] water = new boolean[n][n];
		double[] out = new double[3];
		int x0 = cx - SIZE * BLOCKS_PER_PIXEL / 2;
		int z0 = cz - SIZE * BLOCKS_PER_PIXEL / 2;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				world.sampleBlock(x0 + (i - 1) * BLOCKS_PER_PIXEL, z0 + (j - 1) * BLOCKS_PER_PIXEL, out);
				ground[j][i] = out[0];
				water[j][i] = out[0] <= -1.0 || !Double.isNaN(out[1]);
			}
		}
		BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		double ve = 3.0;
		double[] light = { -0.5, 0.75, -0.45 };
		double ll = Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
		for (int j = 0; j < SIZE; j++) {
			for (int i = 0; i < SIZE; i++) {
				int x = x0 + i * BLOCKS_PER_PIXEL;
				int z = z0 + j * BLOCKS_PER_PIXEL;
				double g = ground[j + 1][i + 1];
				if (water[j + 1][i + 1]) {
					image.setRGB(i, j, new Color(40, 90, 190).getRGB());
					continue;
				}
				double dzdx = (ground[j + 1][i + 2] - ground[j + 1][i]) / (2.0 * BLOCKS_PER_PIXEL) * ve;
				double dzdz = (ground[j + 2][i + 1] - ground[j][i + 1]) / (2.0 * BLOCKS_PER_PIXEL) * ve;
				double nl = Math.sqrt(dzdx * dzdx + 1.0 + dzdz * dzdz);
				double shade = Math.max(0.0, (-dzdx * light[0] + light[1] - dzdz * light[2]) / (nl * ll));
				int y = (int) Math.ceil(63 + g) + 4;
				String biome = resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				Color base = BiomeGapPreviewTest.colour(biome, g);
				double k = 0.35 + 0.95 * shade;
				image.setRGB(i, j, new Color(clamp(base.getRed() * k), clamp(base.getGreen() * k), clamp(base.getBlue() * k)).getRGB());
			}
		}
		Graphics2D g2 = image.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setFont(new Font("SansSerif", Font.BOLD, 14));
		g2.setColor(new Color(0, 0, 0, 160));
		g2.fillRect(6, 6, 560, 24);
		g2.setColor(Color.WHITE);
		g2.drawString(String.format("%s: %d km across, around x %d, z %d", title, SIZE * BLOCKS_PER_PIXEL / 1000, cx, cz), 12, 23);
		g2.dispose();
		return image;
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, v));
	}
}
