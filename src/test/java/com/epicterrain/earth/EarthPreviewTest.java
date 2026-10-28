package com.epicterrain.earth;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Simulates a continent and draws it: shaded relief, plates and their boundaries, uplift episodes, rainfall, biomes, drainage and a
 * cross-section, plus the numbers that say whether it behaves like a real one. Options are {@code -Depicterrain.earth.*} (see
 * {@link EarthConfig#fromProperties}); with {@code -Depicterrain.earth.compare=true} it also draws the same window with belts 2, 3 and 4 km wide.
 */
@Tag("preview")
class EarthPreviewTest {
	@Test
	void render() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		String name = "earth_" + cfg.seed() + "_c" + cfg.cellBlocks() + "_s" + cfg.steepness();

		Continent c = ContinentSimulator.run(cfg);
		System.out.println("[earth] " + c.timing);
		System.out.println("[earth] " + c.metrics.report());

		BufferedImage[] maps = { EarthImages.relief(c), EarthImages.plates(c), EarthImages.uplift(c), EarthImages.rain(c), EarthImages.biomes(c), EarthImages.drainage(c) };
		String[] names = { "relief", "plates", "uplift", "rain", "biomes", "drainage" };
		for (int i = 0; i < maps.length; i++) {
			ImageIO.write(maps[i], "png", new File(out, name + "_" + names[i] + ".png"));
		}
		ImageIO.write(EarthImages.section(c), "png", new File(out, name + "_section.png"));
		ImageIO.write(EarthImages.crop(c, maps[0], 220, 3), "png", new File(out, name + "_crop.png"));
		ImageIO.write(EarthImages.sheet(maps, 3, 640), "png", new File(out, name + "_sheet.png"));

		if (Boolean.getBoolean("epicterrain.earth.compare")) {
			BufferedImage[] reliefs = new BufferedImage[3];
			double[] belts = { 2_000.0, 3_000.0, 4_000.0 };
			for (int i = 0; i < belts.length; i++) {
				Continent other = ContinentSimulator.run(cfg.withBelt(belts[i]));
				System.out.printf("[earth] belt %.0f blocks wide: %s%n", belts[i], other.metrics.report());
				reliefs[i] = EarthImages.relief(other);
			}
			ImageIO.write(EarthImages.sheet(reliefs, 3, 700), "png", new File(out, name + "_belts.png"));
		}
		System.out.println("[earth] wrote " + out.getAbsolutePath() + File.separator + name + "_*.png");
	}
}
