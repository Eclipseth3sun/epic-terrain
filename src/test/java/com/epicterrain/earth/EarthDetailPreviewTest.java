package com.epicterrain.earth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the ground looks like at the scale a player walks it: close-ups (4 blocks to a pixel) of a young massif, an old range and a plain,
 * the simulated ground smooth against the same with {@link Detail}; how steep it is, by relief band; and a 1-block profile.
 * Options: {@code -Depicterrain.earth.crag}.
 */
@Tag("preview")
class EarthDetailPreviewTest {
	static final int SIZE = 500; // pixels, 4 blocks each: a 2 km square
	static final double STEP = 4.0;

	static double[] heights(Detail d, boolean withDetail, double x0, double z0) {
		double[] h = new double[SIZE * SIZE];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				double wx = x0 + x * STEP;
				double wz = z0 + z * STEP;
				h[z * SIZE + x] = withDetail ? d.blocks(wx, wz) : d.smoothBlocks(wx, wz);
			}
		}
		return h;
	}

	static BufferedImage shaded(double[] h) {
		return shaded(h, STEP);
	}

	/** Shaded relief of a SIZE x SIZE grid of heights {@code step} blocks apart. */
	static BufferedImage shaded(double[] h, double step) {
		BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				int xl = Math.max(0, x - 1);
				int xr = Math.min(SIZE - 1, x + 1);
				int zu = Math.max(0, z - 1);
				int zd = Math.min(SIZE - 1, z + 1);
				double dx = (h[z * SIZE + xr] - h[z * SIZE + xl]) / ((xr - xl) * step);
				double dz = (h[zd * SIZE + x] - h[zu * SIZE + x]) / ((zd - zu) * step);
				// light from the north-west, 45 degrees up
				double nx = -dx;
				double nz = -dz;
				double len = Math.sqrt(nx * nx + nz * nz + 1.0);
				double dot = (nx * -0.5 + nz * -0.5 + 0.7071) / len;
				double shade = Math.max(0.0, dot) / 0.7071;
				int base = EarthImages.landColour(h[z * SIZE + x]);
				double k = 0.35 + 0.8 * shade;
				int r = (int) Math.max(0, Math.min(255, ((base >> 16) & 255) * k));
				int g = (int) Math.max(0, Math.min(255, ((base >> 8) & 255) * k));
				int b = (int) Math.max(0, Math.min(255, (base & 255) * k));
				img.setRGB(x, z, (r << 16) | (g << 8) | b);
			}
		}
		return img;
	}

	private static double slopeDegrees(Detail d, boolean withDetail, double wx, double wz) {
		double half = 8.0; // slope over 16 blocks, the baseline of the earlier measurements of the epic and vanilla ground
		double dx = (withDetail ? d.blocks(wx + half, wz) - d.blocks(wx - half, wz) : d.smoothBlocks(wx + half, wz) - d.smoothBlocks(wx - half, wz)) / (2.0 * half);
		double dz = (withDetail ? d.blocks(wx, wz + half) - d.blocks(wx, wz - half) : d.smoothBlocks(wx, wz + half) - d.smoothBlocks(wx, wz - half)) / (2.0 * half);
		return Math.toDegrees(Math.atan(Math.hypot(dx, dz)));
	}

	@Test
	void render() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		Continent c = ContinentSimulator.run(cfg);
		Detail.Dials dials = Detail.Dials.fromProperties();
		Detail d = new Detail(c, dials);
		float[] relief = BiomeClimate.reliefAboveBase(c);
		float[] coast = BiomeClimate.coastDistance(c);
		int n = c.nx * c.nz;

		// the three sites: the highest ground, the highest ground of an old belt, and a plain well inland
		int young = 0;
		int old = -1;
		int plain = -1;
		double plainBest = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			if (c.height[i] > c.height[young]) {
				young = i;
			}
			if (c.old[i] >= 0.4f && c.young[i] < 0.1f && c.height[i] > 0.0f && (old < 0 || c.height[i] > c.height[old])) {
				old = i;
			}
			double fromMiddle = Math.hypot(i % c.nx - c.nx / 2.0, i / c.nx - c.nz / 2.0);
			if (relief[i] < 40.0f && coast[i] > 2500.0f && c.height[i] > 0.0f && fromMiddle < plainBest) {
				plainBest = fromMiddle;
				plain = i;
			}
		}
		int[] sites = { young, old < 0 ? young : old, plain < 0 ? young : plain };
		String[] labels = { "young massif", "old range", "plain" };
		BufferedImage[] images = new BufferedImage[6];
		double[] firstWindow = null;
		for (int s = 0; s < 3; s++) {
			double cx = c.x0 + (sites[s] % c.nx) * c.cell;
			double cz = c.z0 + (sites[s] / c.nx) * c.cell;
			double x0 = cx - SIZE * STEP / 2.0;
			double z0 = cz - SIZE * STEP / 2.0;
			double[] smooth = heights(d, false, x0, z0);
			double[] detailed = heights(d, true, x0, z0);
			if (s == 0) {
				firstWindow = new double[] { x0, z0, cx, cz };
			}
			for (int k = 0; k < 2; k++) {
				BufferedImage img = shaded(k == 0 ? smooth : detailed);
				Graphics2D g = img.createGraphics();
				g.setColor(Color.WHITE);
				g.setFont(new Font("SansSerif", Font.BOLD, 14));
				g.drawString(labels[s] + (k == 0 ? ": simulated ground, smooth" : ": with fine detail") + String.format("  (centre %.0f, %.0f)", cx, cz), 8, 20);
				g.drawString("2 km square", 8, SIZE - 8);
				g.dispose();
				images[2 * s + k] = img;
			}
			double lo = Double.MAX_VALUE;
			double hi = -Double.MAX_VALUE;
			for (double v : detailed) {
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
			System.out.printf("[detail] %s: ground from y %.0f to y %.0f (a drop of %.0f blocks across 2 km)%n", labels[s], EarthConfig.SEA_LEVEL_Y + lo, EarthConfig.SEA_LEVEL_Y + hi, hi - lo);
		}
		BufferedImage sheet = new BufferedImage(2 * SIZE, 3 * SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D sg = sheet.createGraphics();
		for (int k = 0; k < 6; k++) {
			sg.drawImage(images[k], (k % 2) * SIZE, (k / 2) * SIZE, null);
		}
		sg.dispose();
		String name = "earth_" + cfg.seed() + "_c" + cfg.cellBlocks() + "_detail";
		ImageIO.write(sheet, "png", new File(out, name + "_closeups.png"));

		// a 1-block profile west to east through the young massif, smooth against detailed
		int length = 1500;
		int w = length;
		int hgt = 420;
		BufferedImage prof = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB);
		Graphics2D pg = prof.createGraphics();
		pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		pg.setColor(Color.WHITE);
		pg.fillRect(0, 0, w, hgt);
		double[] smoothLine = new double[length];
		double[] detailLine = new double[length];
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for (int i = 0; i < length; i++) {
			double wx = firstWindow[2] - length / 2.0 + i;
			smoothLine[i] = d.smoothBlocks(wx, firstWindow[3]);
			detailLine[i] = d.blocks(wx, firstWindow[3]);
			lo = Math.min(lo, Math.min(smoothLine[i], detailLine[i]));
			hi = Math.max(hi, Math.max(smoothLine[i], detailLine[i]));
		}
		double scale = (hgt - 50.0) / Math.max(1.0, hi - lo);
		pg.setStroke(new BasicStroke(1.2f));
		for (int pass = 0; pass < 2; pass++) {
			double[] line = pass == 0 ? smoothLine : detailLine;
			pg.setColor(pass == 0 ? new Color(0x2060C0) : new Color(0xC03020));
			for (int i = 1; i < length; i++) {
				pg.drawLine(i - 1, (int) (hgt - 25 - (line[i - 1] - lo) * scale), i, (int) (hgt - 25 - (line[i] - lo) * scale));
			}
		}
		pg.setColor(Color.BLACK);
		pg.setFont(new Font("SansSerif", Font.PLAIN, 13));
		pg.drawString(String.format("West to east through the young massif, 1.5 km, one block a pixel: blue is the simulated ground, red the ground with detail (y %.0f to %.0f)",
			EarthConfig.SEA_LEVEL_Y + lo, EarthConfig.SEA_LEVEL_Y + hi), 8, 16);
		pg.dispose();
		ImageIO.write(prof, "png", new File(out, name + "_profile.png"));

		// how steep, by relief band: slope over 16 blocks, at random land points
		String[] bands = { "lowland", "foothills", "montane", "subalpine", "peaks" };
		double[] edges = { 0.0, 300.0, 900.0, 1800.0, 3000.0, 1.0e9 };
		Random random = new Random(5);
		System.out.printf("[detail] crag %.1f. Slope over 16 blocks (the baseline used for vanilla and the old epic ranges), smooth ground then with detail:%n", dials.crag());
		for (int b = 0; b < bands.length; b++) {
			int taken = 0;
			double[] sum = new double[2];
			int[][] over = new int[2][3];
			int attempts = 0;
			while (taken < 4000 && attempts++ < 4_000_000) {
				int i = random.nextInt(n);
				if (c.height[i] <= 15.0f || relief[i] < edges[b] || relief[i] >= edges[b + 1]) {
					continue;
				}
				double wx = c.x0 + (i % c.nx + random.nextDouble() - 0.5) * c.cell;
				double wz = c.z0 + (i / c.nx + random.nextDouble() - 0.5) * c.cell;
				for (int k = 0; k < 2; k++) {
					double slope = slopeDegrees(d, k == 1, wx, wz);
					sum[k] += slope;
					over[k][0] += slope > 30.0 ? 1 : 0;
					over[k][1] += slope > 45.0 ? 1 : 0;
					over[k][2] += slope > 60.0 ? 1 : 0;
				}
				taken++;
			}
			if (taken == 0) {
				continue;
			}
			System.out.printf("[detail]   %-9s (%4d points): smooth mean %4.1f deg, over 30/45/60 deg %4.1f/%4.1f/%4.1f%%;  detailed mean %4.1f deg, over 30/45/60 deg %4.1f/%4.1f/%4.1f%%%n",
				bands[b], taken, sum[0] / taken, 100.0 * over[0][0] / taken, 100.0 * over[0][1] / taken, 100.0 * over[0][2] / taken,
				sum[1] / taken, 100.0 * over[1][0] / taken, 100.0 * over[1][1] / taken, 100.0 * over[1][2] / taken);
		}
		System.out.println("[detail] for comparison, measured earlier on the same 16-block baseline: vanilla peaks (ground 140+) mean 35.5 deg, over 45 deg 30%, over 60 deg 6%; vanilla hills mean 24.4 deg, over 45 deg 14%; the old epic summits mean 45.7 deg, over 45 deg 56%, over 60 deg 22%");
		System.out.println("[detail] wrote " + new File(out, name + "_closeups.png").getAbsolutePath());
	}
}
