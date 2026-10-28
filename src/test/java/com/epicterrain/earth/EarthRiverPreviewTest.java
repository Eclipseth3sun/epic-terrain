package com.epicterrain.earth;

import static com.epicterrain.earth.EarthDetailPreviewTest.SIZE;
import static com.epicterrain.earth.EarthDetailPreviewTest.STEP;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.epicterrain.earth.Rivers.River;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the simulated rivers look like once they have a width: the whole network on the relief map, and close-ups (4 blocks to a pixel,
 * on the ground with {@link Detail}) of the biggest river near its mouth and mid-course, a medium river and a confluence; with the numbers
 * for how long, how wide and how tightly bent they are. Options: {@code -Depicterrain.earth.riverWidth} (blocks wide at the smallest river).
 */
@Tag("preview")
class EarthRiverPreviewTest {
	private static final Color BANK = new Color(0x123E6B);
	private static final Color WATER = new Color(0x3C8FD6);

	private static void draw(Graphics2D g, List<River> rivers, double x0, double z0, double scale, double min, boolean bankPass) {
		for (River r : rivers) {
			double[] first = r.line.get(0);
			double[] last = r.line.get(r.line.size() - 1);
			double reach = r.mouthWidth() * scale;
			boolean near = false;
			for (double[] p : r.line) {
				double px = (p[0] - x0) * scale;
				double pz = (p[1] - z0) * scale;
				if (px > -reach - 20 && px < SIZE + reach + 20 && pz > -reach - 20 && pz < SIZE + reach + 20) {
					near = true;
					break;
				}
			}
			if (!near || first == last) {
				continue;
			}
			for (int k = 1; k < r.line.size(); k++) {
				double[] a = r.line.get(k - 1);
				double[] b = r.line.get(k);
				double w = Math.max(min, 0.5 * (r.width[k - 1] + r.width[k]) * scale);
				g.setStroke(new BasicStroke((float) (bankPass ? w + 2.0 : w), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(bankPass ? BANK : WATER);
				g.draw(new Line2D.Double((a[0] - x0) * scale, (a[1] - z0) * scale, (b[0] - x0) * scale, (b[1] - z0) * scale));
			}
		}
	}

	private static BufferedImage window(Detail d, List<River> rivers, Continent c, double cx, double cz, String label) {
		double x0 = cx - SIZE * STEP / 2.0;
		double z0 = cz - SIZE * STEP / 2.0;
		BufferedImage img = EarthDetailPreviewTest.shaded(EarthDetailPreviewTest.heights(d, true, x0, z0));
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		draw(g, rivers, x0, z0, 1.0 / STEP, 1.0, true);
		draw(g, rivers, x0, z0, 1.0 / STEP, 1.0, false);
		// the chain of cells the simulation routed each river over, faint, to show what the smoothing and the bends did to it
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		g.setStroke(new BasicStroke(1.0f));
		g.setColor(new Color(0xFFE000));
		for (River r : rivers) {
			for (int k = 1; k < r.cells.length; k++) {
				int a = r.cells[k - 1];
				int b = r.cells[k];
				g.draw(new Line2D.Double((c.x0 + (a % c.nx) * c.cell - x0) / STEP, (c.z0 + (a / c.nx) * c.cell - z0) / STEP,
					(c.x0 + (b % c.nx) * c.cell - x0) / STEP, (c.z0 + (b / c.nx) * c.cell - z0) / STEP));
			}
		}
		g.setComposite(AlphaComposite.SrcOver);
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 14));
		g.drawString(label, 8, 20);
		g.drawString("2 km square; yellow: the cells the simulation routed it over", 8, SIZE - 8);
		g.dispose();
		return img;
	}

	@Test
	void render() throws Exception {
		EarthConfig cfg = EarthConfig.fromProperties();
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();
		Continent c = ContinentSimulator.run(cfg);
		Detail d = new Detail(c, Detail.Dials.fromProperties());
		Rivers.Dials dials = Rivers.Dials.fromProperties();
		long start = System.nanoTime();
		List<River> rivers = Rivers.build(c, d::smoothBlocks, dials);
		System.out.printf("[rivers] %d rivers built in %.1f s; the smallest is %.1f blocks wide%n", rivers.size(), (System.nanoTime() - start) / 1e9, dials.minWidth());

		double total = 0.0;
		double routed = 0.0;
		double[] edges = { 0.0, 5.0, 10.0, 20.0, 40.0, 1.0e9 };
		double[] byWidth = new double[edges.length - 1];
		River widest = null;
		River longest = null;
		int tight = 0;
		int tightCounted = 0;
		for (River r : rivers) {
			total += r.length;
			routed += r.routedLength;
			for (int k = 1; k < r.line.size(); k++) {
				double seg = Math.hypot(r.line.get(k)[0] - r.line.get(k - 1)[0], r.line.get(k)[1] - r.line.get(k - 1)[1]);
				double w = 0.5 * (r.width[k] + r.width[k - 1]);
				for (int b = 0; b < byWidth.length; b++) {
					if (w >= edges[b] && w < edges[b + 1]) {
						byWidth[b] += seg;
					}
				}
			}
			if (r.joins < 0 && (widest == null || r.mouthWidth() > widest.mouthWidth())) {
				widest = r;
			}
			if (longest == null || r.length > longest.length) {
				longest = r;
			}
			if (r.line.size() > 12 && r.mouthWidth() >= 10.0) {
				double share = Rivers.tightShare(r, 10.0);
				tight += share > 0.0 ? 1 : 0;
				tightCounted++;
			}
		}
		System.out.printf("[rivers] together %.0f blocks of river (%.0f along the cell chains: the smoothing and bends make it %+.0f%%)%n", total, routed, 100.0 * (total / routed - 1.0));
		String[] names = { "under 5", "5 to 10", "10 to 20", "20 to 40", "40 and over" };
		for (int b = 0; b < byWidth.length; b++) {
			System.out.printf("[rivers]   %-12s blocks wide: %8.0f blocks of river%n", names[b], byWidth[b]);
		}
		System.out.printf("[rivers] longest single river %.0f blocks; widest mouth %.1f blocks (river %d, %.0f blocks long); the cell-chain metric says the longest route is %.0f blocks%n",
			longest.length, widest.mouthWidth(), widest.index, widest.length, c.metrics.longestRiverBlocks);
		double allTight = 0.0;
		double wide = 0.0;
		for (River r : rivers) {
			if (r.line.size() > 12 && r.mouthWidth() >= 10.0) {
				allTight += Rivers.tightShare(r, 10.0) * r.line.size();
				wide += r.line.size();
			}
		}
		System.out.printf("[rivers] among the %d rivers 10 or more blocks wide at the mouth, %.1f%% of their nodes (where they are 10+ wide) bend tighter than their own width%n", tightCounted,
			wide == 0.0 ? 0.0 : 100.0 * allTight / wide);

		// whole network on the relief map, one pixel to a cell
		BufferedImage overview = EarthImages.relief(c);
		Graphics2D og = overview.createGraphics();
		og.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		draw(og, rivers, c.x0, c.z0, 1.0 / c.cell, 1.0, false);
		og.dispose();
		ImageIO.write(overview, "png", new File(out, "earth_" + cfg.seed() + "_c" + cfg.cellBlocks() + "_rivers_overview.png"));

		// four close-ups
		List<RiverSites.Site> places = RiverSites.pick(rivers);
		List<double[]> sites = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (RiverSites.Site site : places) {
			sites.add(new double[] { site.x(), site.z() });
			labels.add(site.label());
		}
		BufferedImage sheet = new BufferedImage(2 * SIZE, 2 * SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D sg = sheet.createGraphics();
		for (int s = 0; s < 4; s++) {
			sg.drawImage(window(d, rivers, c, sites.get(s)[0], sites.get(s)[1], labels.get(s)), (s % 2) * SIZE, (s / 2) * SIZE, null);
		}
		sg.dispose();
		File file = new File(out, "earth_" + cfg.seed() + "_c" + cfg.cellBlocks() + "_rivers_closeups.png");
		ImageIO.write(sheet, "png", file);
		System.out.println("[rivers] wrote " + file.getAbsolutePath());
	}
}
