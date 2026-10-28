package com.epicterrain.earth;

import static com.epicterrain.earth.EarthDetailPreviewTest.SIZE;
import static com.epicterrain.earth.EarthDetailPreviewTest.STEP;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the rock layers do: the same continent with uniform rock and with layered rock (relief, the bed at the surface, and slope), numbers
 * for how much of the land is cliff and bench, and a close-up (4 blocks to a pixel) of the best place for benches with and without layers.
 * Options: {@code -Depicterrain.earth.rock} (the layered run's contrast, default 1).
 */
@Tag("preview")
class EarthRockPreviewTest {
	/** Blocks to a pixel in the close-up: 16, so 8 km square. */
	private static final double VIEW = 16.0;

	/** Slope between a cell and its neighbour, as an angle, for the steepest of the four. */
	static double steepest(Continent c, int x, int z) {
		double h = Continent.blocksAbove(c.height[z * c.nx + x]);
		double worst = 0.0;
		int[][] around = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		for (int[] o : around) {
			int xx = Math.max(0, Math.min(c.nx - 1, x + o[0]));
			int zz = Math.max(0, Math.min(c.nz - 1, z + o[1]));
			worst = Math.max(worst, Math.abs(Continent.blocksAbove(c.height[zz * c.nx + xx]) - h));
		}
		return Math.toDegrees(Math.atan(worst / c.cell));
	}

	/** A cell on level ground (every neighbour within 3 degrees) with ground at least 4 blocks lower within four cells: a bench or a mesa top. */
	static boolean bench(Continent c, int x, int z) {
		if (x < 5 || z < 5 || x > c.nx - 6 || z > c.nz - 6) {
			return false;
		}
		double h = Continent.blocksAbove(c.height[z * c.nx + x]);
		if (h < 8.0 || steepest(c, x, z) > 3.0) {
			return false;
		}
		for (int dz = -4; dz <= 4; dz++) {
			for (int dx = -4; dx <= 4; dx++) {
				if (h - Continent.blocksAbove(c.height[(z + dz) * c.nx + x + dx]) >= 4.0) {
					return true;
				}
			}
		}
		return false;
	}

	private static BufferedImage slopes(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				double t = Math.min(1.0, steepest(c, x, z) / 35.0);
				int v = (int) (255 * (1.0 - t));
				int land = c.height[z * c.nx + x] > 0.0f ? 0 : 1;
				img.setRGB(x, z, land == 1 ? 0x5070A0 : (v << 16) | (v << 8) | v);
			}
		}
		return img;
	}

	private static BufferedImage hardness(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < c.nx * c.nz; i++) {
			if (c.height[i] <= 0.0f) {
				img.setRGB(i % c.nx, i / c.nx, 0x5070A0);
				continue;
			}
			double h = Math.log(c.hardness[i]) / Math.log(2.5); // -1 for the hardest bed or dome, +1 for the softest
			int colour = h < 0.0 ? EarthImages.mix(0xF2E6B8, 0x6A2020, Math.min(1.0, -h)) : EarthImages.mix(0xF2E6B8, 0xF0B040, Math.min(1.0, h));
			img.setRGB(i % c.nx, i / c.nx, colour);
		}
		return img;
	}

	private static void label(BufferedImage img, String text) {
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 22));
		g.drawString(text, 12, 30);
		g.dispose();
	}

	@Test
	void render() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		Continent flat = ContinentSimulator.run(cfg.withRock(0.0));
		Continent layered = ContinentSimulator.run(cfg);
		String name = "earth_" + cfg.seed() + "_c" + cfg.cellBlocks();
		System.out.printf("[rock] simulated in %.0f s with uniform rock and %.0f s with layers (rock %.1f)%n", flat.millis / 1000.0, layered.millis / 1000.0, cfg.rock());

		for (int k = 0; k < 2; k++) {
			Continent c = k == 0 ? flat : layered;
			int land = 0;
			int over10 = 0;
			int over20 = 0;
			int over35 = 0;
			int benches = 0;
			double highest = 0.0;
			for (int z = 0; z < c.nz; z++) {
				for (int x = 0; x < c.nx; x++) {
					if (c.height[z * c.nx + x] <= 0.0f) {
						continue;
					}
					land++;
					double slope = steepest(c, x, z);
					over10 += slope > 10.0 ? 1 : 0;
					over20 += slope > 20.0 ? 1 : 0;
					over35 += slope > 35.0 ? 1 : 0;
					benches += bench(c, x, z) ? 1 : 0;
					highest = Math.max(highest, Continent.blocksAbove(c.height[z * c.nx + x]));
				}
			}
			System.out.printf("[rock] %s: land %d cells; between cells %.2f%% steeper than 10 deg, %.2f%% over 20, %.2f%% over 35; %.2f%% on benches or mesa tops; highest ground %.0f blocks up; lakes %d cells; river cells %d%n",
				k == 0 ? "uniform rock" : "layered rock ", land, 100.0 * over10 / land, 100.0 * over20 / land, 100.0 * over35 / land, 100.0 * benches / land, highest, countLake(c), countRiver(c));
		}

		BufferedImage reliefFlat = EarthImages.relief(flat);
		BufferedImage reliefLayered = EarthImages.relief(layered);
		label(reliefFlat, "uniform rock");
		label(reliefLayered, "layered rock");
		BufferedImage beds = hardness(layered);
		label(beds, "the bed at the surface: dark red hard, orange soft");
		BufferedImage slope = slopes(layered);
		label(slope, "slope between cells (white steep), layered rock");
		ImageIO.write(EarthImages.sheet(new BufferedImage[] { reliefFlat, reliefLayered, beds, slope }, 2, 900), "png", new File(out, name + "_rock.png"));

		// the place with the most scarps outside the young mountains (where the layers show most), with and without them
		int best = -1;
		int bestScore = -1;
		float[] hills = BiomeClimate.reliefAboveBase(layered);
		for (int z = 20; z < layered.nz - 20; z += 3) {
			for (int x = 20; x < layered.nx - 20; x += 3) {
				if (layered.young[z * layered.nx + x] >= 0.05f || hills[z * layered.nx + x] < 80.0f) {
					continue;
				}
				int score = 0;
				for (int dz = -8; dz <= 8; dz += 2) {
					for (int dx = -8; dx <= 8; dx += 2) {
						score += steepest(layered, x + dx, z + dz) > 10.0 && layered.height[(z + dz) * layered.nx + x + dx] > 0.0f ? 1 : 0;
					}
				}
				if (score > bestScore) {
					bestScore = score;
					best = z * layered.nx + x;
				}
			}
		}
		if (best >= 0) {
			double cx = layered.x0 + (best % layered.nx) * layered.cell;
			double cz = layered.z0 + (best / layered.nx) * layered.cell;
			BufferedImage pair = new BufferedImage(2 * SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = pair.createGraphics();
			for (int k = 0; k < 2; k++) {
				Continent c = k == 0 ? flat : layered;
				Detail d = new Detail(c, Detail.Dials.fromProperties());
				double[] h = new double[SIZE * SIZE];
				for (int z = 0; z < SIZE; z++) {
					for (int x = 0; x < SIZE; x++) {
						h[z * SIZE + x] = d.blocks(cx - SIZE * VIEW / 2.0 + x * VIEW, cz - SIZE * VIEW / 2.0 + z * VIEW);
					}
				}
				BufferedImage img = EarthDetailPreviewTest.shaded(h, VIEW);
				Graphics2D ig = img.createGraphics();
				ig.setColor(Color.WHITE);
				ig.setFont(new Font("SansSerif", Font.BOLD, 14));
				ig.drawString((k == 0 ? "uniform rock" : "layered rock") + String.format("  (centre %.0f, %.0f; 8 km square)", cx, cz), 8, 20);
				ig.dispose();
				g.drawImage(img, k * SIZE, 0, null);
			}
			g.dispose();
			ImageIO.write(pair, "png", new File(out, name + "_rock_closeup.png"));
		}
		System.out.println("[rock] wrote " + new File(out, name + "_rock.png").getAbsolutePath());
	}

	private static int countLake(Continent c) {
		int n = 0;
		for (float d : c.lakeDepth) {
			n += d > 0.0f ? 1 : 0;
		}
		return n;
	}

	private static int countRiver(Continent c) {
		int n = 0;
		for (boolean r : c.river) {
			n += r ? 1 : 0;
		}
		return n;
	}
}
