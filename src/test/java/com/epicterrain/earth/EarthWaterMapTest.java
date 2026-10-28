package com.epicterrain.earth;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How much of the plate skeleton is water and how much land. The same 200 km square as the island map (centred on 0, 0) is simulated whole: eight plates
 * across, cells of 128 blocks, sea level where the continental-water dial puts it. Water on continental plates is drawn light, water on oceanic plates dark,
 * and the plate boundaries are lines. The outer 14 km of the square is the simulation's own rim (it is held down to the sea at the edge), so the numbers leave it out.
 * Options: {@code -Depicterrain.earth.water} and the others of {@link EarthConfig#fromProperties}.
 */
@Tag("preview")
class EarthWaterMapTest {
	@Test
	void render() throws Exception {
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		System.setProperty("epicterrain.earth.cx", "0");
		System.setProperty("epicterrain.earth.cz", "0");
		EarthConfig fromOptions = EarthConfig.fromProperties();
		long[] seeds = Boolean.getBoolean("epicterrain.earth.oneSeed") ? new long[] { fromOptions.seed() } : new long[] { fromOptions.seed(), 11L, 7L };
		try {
			for (long seed : seeds) {
				EarthConfig cfg = fromOptions.withPlate(25_000.0, 8.0).withCell(128);
				cfg = new EarthConfig(seed, cfg.plateBlocks(), cfg.continentalShare(), cfg.continentalWater(), cfg.boundaryShare(), cfg.beltBlocks(), cfg.steepness(), cfg.cellBlocks(),
					cfg.windowPlates(), cfg.temperature(), cfg.steps(), cfg.rock());
				Continent c = ContinentSimulator.run(cfg);
				System.out.printf("[water] seed %d: %d x %d cells of %d blocks, %.0f km square; %s%n", seed, c.nx, c.nz, cfg.cellBlocks(), c.nx * c.cell / 1000.0, c.timing);
				int n = c.nx * c.nz;
				// the rim is the simulation's own: left out of the numbers
				long land = 0;
				long lake = 0;
				long sea = 0;
				long contLand = 0;
				long contLake = 0;
				long contSea = 0;
				long oceanLand = 0;
				long oceanSea = 0;
				for (int i = 0; i < n; i++) {
					if (c.rim[i] > 0.0f) {
						continue;
					}
					boolean continental = c.continental[i] != 0;
					if (c.height[i] <= 0.0f) {
						sea++;
						if (continental) {
							contSea++;
						} else {
							oceanSea++;
						}
					} else if (c.lakeDepth[i] > 0.0f) {
						lake++;
						contLake += continental ? 1 : 0;
					} else {
						land++;
						if (continental) {
							contLand++;
						} else {
							oceanLand++;
						}
					}
				}
				long counted = land + lake + sea;
				long cont = contLand + contLake + contSea;
				long ocean = oceanLand + oceanSea;
				System.out.printf("[water]   away from the rim: land %.1f%%, sea %.1f%%, lakes %.2f%% of the square%n", 100.0 * land / counted, 100.0 * sea / counted, 100.0 * lake / counted);
				System.out.printf("[water]   continental plates (%.1f%% of the square): land %.1f%%, sea %.1f%%, lakes %.2f%%; oceanic plates (%.1f%% of the square): water %.1f%%, land (islands and ridges) %.1f%%%n",
					100.0 * cont / counted, 100.0 * contLand / cont, 100.0 * contSea / cont, 100.0 * contLake / cont, 100.0 * ocean / counted, 100.0 * oceanSea / Math.max(1, ocean), 100.0 * oceanLand / Math.max(1, ocean));

				// seas on continental crust: how big, and are they joined to an oceanic plate's water
				boolean[] seen = new boolean[n];
				List<double[]> bodies = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					if (seen[i] || c.height[i] > 0.0f || c.continental[i] == 0 || c.rim[i] > 0.0f) {
						continue;
					}
					ArrayDeque<Integer> queue = new ArrayDeque<>();
					queue.add(i);
					seen[i] = true;
					int size = 0;
					boolean touchesOcean = false;
					while (!queue.isEmpty()) {
						int k = queue.poll();
						size++;
						int[] around = { k - 1, k + 1, k - c.nx, k + c.nx };
						for (int m : around) {
							if (m < 0 || m >= n || Math.abs(m % c.nx - k % c.nx) > 1) {
								continue;
							}
							if (c.height[m] <= 0.0f && c.continental[m] == 0) {
								touchesOcean = true;
							}
							if (!seen[m] && c.height[m] <= 0.0f && c.continental[m] != 0 && c.rim[m] <= 0.0f) {
								seen[m] = true;
								queue.add(m);
							}
						}
					}
					bodies.add(new double[] { size * c.cell * c.cell / 1.0e6, touchesOcean ? 1.0 : 0.0 });
				}
				bodies.sort((a, b) -> Double.compare(b[0], a[0]));
				double enclosed = 0.0;
				double open = 0.0;
				int enclosedCount = 0;
				for (double[] b : bodies) {
					if (b[1] == 1.0) {
						open += b[0];
					} else {
						enclosed += b[0];
						enclosedCount += b[0] >= 5.0 ? 1 : 0;
					}
				}
				StringBuilder biggest = new StringBuilder();
				for (int k = 0; k < Math.min(6, bodies.size()); k++) {
					biggest.append(String.format("%.0f km2%s, ", bodies.get(k)[0], bodies.get(k)[1] == 1.0 ? " (open to an ocean plate)" : " (enclosed)"));
				}
				System.out.printf("[water]   water on continental plates: %.0f km2 open to an oceanic plate, %.0f km2 in enclosed seas (%d of 5 km2 or more); biggest bodies: %s%n", open, enclosed, enclosedCount, biggest);

				// the picture
				BufferedImage img = EarthImages.relief(c);
				PlateGrid.Hit hit = new PlateGrid.Hit();
				for (int z = 0; z < c.nz; z++) {
					for (int x = 0; x < c.nx; x++) {
						int i = z * c.nx + x;
						if (c.height[i] <= 0.0f) {
							int colour = img.getRGB(x, z) & 0xFFFFFF;
							img.setRGB(x, z, c.continental[i] != 0 ? EarthImages.mix(colour, 0x9ED8F4, 0.55) : colour);
						}
						if (x % 1 == 0) {
							Tectonics.Plate plate = c.tectonics.plateAt(c.x0 + x * c.cell, c.z0 + z * c.cell, hit);
							if (hit.distance < c.cell * 0.6) {
								img.setRGB(x, z, EarthImages.mix(img.getRGB(x, z) & 0xFFFFFF, 0x202020, 0.7));
							}
						}
					}
				}
				Graphics2D g = img.createGraphics();
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, 700, 84);
				g.setColor(Color.BLACK);
				g.setFont(new Font("SansSerif", Font.BOLD, 14));
				g.drawString(String.format("Seed %d: the same 200 km square as the island map, simulated whole (cells of 128 blocks)", seed), 8, 18);
				g.drawString(String.format("Land %.0f%%, sea %.0f%%, lakes %.1f%% (outer 14 km, the simulation's rim, left out).", 100.0 * land / counted, 100.0 * sea / counted, 100.0 * lake / counted), 8, 38);
				g.drawString("Light blue: sea on a continental plate. Dark blue: oceanic plate. Dark lines: plate boundaries.", 8, 58);
				g.dispose();
				ImageIO.write(img, "png", new File(out, "earth_" + seed + "_water_map.png"));
			}
		} finally {
			System.clearProperty("epicterrain.earth.cx");
			System.clearProperty("epicterrain.earth.cz");
		}
		System.out.println("[water] wrote " + out.getAbsolutePath());
	}
}
