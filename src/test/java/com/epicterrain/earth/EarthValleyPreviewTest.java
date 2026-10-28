package com.epicterrain.earth;

import static com.epicterrain.earth.EarthDetailPreviewTest.SIZE;
import static com.epicterrain.earth.EarthDetailPreviewTest.STEP;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import com.epicterrain.earth.Rivers.River;
import com.epicterrain.earth.RiverSites.Site;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The rivers worked into the ground ({@link RiverValley}): close-ups (4 blocks to a pixel) at the four river sites with water wherever the
 * ground is under the river's level, cross-sections of three rivers before and after, the long profile of the biggest, and numbers for how
 * the water falls, how deep it cuts, and how often its level steps. Options: {@code -Depicterrain.earth.riverDepth},
 * {@code -Depicterrain.earth.floodplain}, {@code -Depicterrain.earth.riverWidth}.
 */
@Tag("preview")
class EarthValleyPreviewTest {
	private static final Color SHALLOW = new Color(0x62B6E8);
	private static final Color DEEP = new Color(0x143F7A);

	private static BufferedImage window(RiverValley v, double cx, double cz, String label) {
		double x0 = cx - SIZE * STEP / 2.0;
		double z0 = cz - SIZE * STEP / 2.0;
		double[] h = new double[SIZE * SIZE];
		double[] w = new double[SIZE * SIZE];
		double[] out = new double[2];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				v.column(x0 + x * STEP, z0 + z * STEP, out);
				h[z * SIZE + x] = out[0];
				w[z * SIZE + x] = out[1];
			}
		}
		BufferedImage img = EarthDetailPreviewTest.shaded(h);
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				double level = w[z * SIZE + x];
				if (Double.isNaN(level) && h[z * SIZE + x] < 0.0) {
					level = 0.0;
				}
				if (!Double.isNaN(level)) {
					double depth = level - h[z * SIZE + x];
					img.setRGB(x, z, EarthImages.mix(SHALLOW.getRGB(), DEEP.getRGB(), Math.min(1.0, depth / 9.0)));
				}
			}
		}
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 14));
		g.drawString(label, 8, 20);
		g.drawString("2 km square; water is wherever the ground is under the river's level", 8, SIZE - 8);
		g.dispose();
		return img;
	}

	/** Ground before (grey) and after (black) along a line across the river, with the water in blue. */
	private static BufferedImage section(RiverValley v, Detail d, Site site, int width, int height) {
		River r = site.river();
		int k = site.node();
		int a = Math.max(0, k - 1);
		int b = Math.min(r.line.size() - 1, k + 1);
		double tx = r.line.get(b)[0] - r.line.get(a)[0];
		double tz = r.line.get(b)[1] - r.line.get(a)[1];
		double t = Math.hypot(tx, tz);
		double nx = -tz / t;
		double nz = tx / t;
		double reach = v.reach(r.index).reach[k];
		double span = Math.min(reach + 20.0, 2.0 * reach);
		int samples = (int) (2 * span) + 1;
		double[] before = new double[samples];
		double[] after = new double[samples];
		double[] level = new double[samples];
		double[] out = new double[2];
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for (int i = 0; i < samples; i++) {
			double lateral = -span + i;
			double wx = r.line.get(k)[0] + nx * lateral;
			double wz = r.line.get(k)[1] + nz * lateral;
			before[i] = d.blocks(wx, wz);
			v.column(wx, wz, out);
			after[i] = out[0];
			level[i] = out[1];
			lo = Math.min(lo, Math.min(before[i], after[i]));
			hi = Math.max(hi, Math.max(before[i], after[i]));
		}
		hi = Math.min(hi, lo + 60.0);
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, width, height);
		double sx = (width - 20.0) / samples;
		double sy = (height - 60.0) / Math.max(4.0, hi - lo);
		for (int i = 0; i < samples; i++) {
			if (!Double.isNaN(level[i])) {
				int top = (int) (height - 25 - (level[i] - lo) * sy);
				int bottom = (int) (height - 25 - (after[i] - lo) * sy);
				g.setColor(new Color(0x7CC0EE));
				g.fillRect((int) (10 + i * sx), top, (int) Math.ceil(sx) + 1, Math.max(1, bottom - top));
			}
		}
		g.setStroke(new BasicStroke(1.4f));
		for (int pass = 0; pass < 2; pass++) {
			double[] line = pass == 0 ? before : after;
			g.setColor(pass == 0 ? new Color(0xA0A0A0) : Color.BLACK);
			for (int i = 1; i < samples; i++) {
				g.drawLine((int) (10 + (i - 1) * sx), (int) (height - 25 - (Math.min(line[i - 1], hi) - lo) * sy), (int) (10 + i * sx), (int) (height - 25 - (Math.min(line[i], hi) - lo) * sy));
			}
		}
		g.setColor(Color.BLACK);
		g.setFont(new Font("SansSerif", Font.PLAIN, 12));
		g.drawString(String.format("%.0f blocks wide: ground before (grey) and with the valley (black), water level %.1f above sea; across %.0f blocks, %.0f high", r.width[k], v.waterAt(r.index, k), 2 * span, hi - lo), 8, 14);
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
		List<River> rivers = Rivers.build(c, d::smoothBlocks, Rivers.Dials.fromProperties());
		long start = System.nanoTime();
		RiverValley v = new RiverValley(c, rivers, d, RiverValley.Dials.fromProperties());
		System.out.printf("[valley] %d river valleys built in %.1f s%n", v.rivers(), (System.nanoTime() - start) / 1e9);

		// numbers: how the water falls, how deep the cut, how often it steps
		int rising = 0;
		int nodes = 0;
		double[] cutBy = new double[5];
		int[] cutNodes = new int[5];
		double[] stepsLength = new double[5];
		int[] steps = new int[5];
		double[] edges = { 0.0, 5.0, 10.0, 20.0, 40.0, 1.0e9 };
		int gorge = 0;
		double maxCut = 0.0;
		for (River r : rivers) {
			int klass = 0;
			while (klass < 4 && r.mouthWidth() >= edges[klass + 1]) {
				klass++;
			}
			long previous = Long.MIN_VALUE;
			for (int k = 0; k < r.line.size(); k++) {
				double level = v.waterAt(r.index, k);
				if (k > 0 && level > v.waterAt(r.index, k - 1) + 1.0e-9) {
					rising++;
				}
				nodes++;
				double cut = d.smoothBlocks(r.line.get(k)[0], r.line.get(k)[1]) - level;
				cutBy[klass] += cut;
				cutNodes[klass]++;
				maxCut = Math.max(maxCut, cut);
				gorge += cut > 8.0 ? 1 : 0;
				long rounded = Math.round(level);
				if (previous != Long.MIN_VALUE && rounded != previous) {
					steps[klass]++;
				}
				previous = rounded;
			}
			stepsLength[klass] += r.length;
		}
		String[] names = { "under 5", "5 to 10", "10 to 20", "20 to 40", "40 and over" };
		System.out.printf("[valley] %d of %d nodes where the water rises downstream (should be 0); the smooth ground is at most %.1f blocks above the water at a node, over 8 blocks above at %.1f%% of nodes%n", rising, nodes, maxCut, 100.0 * gorge / nodes);
		for (int i = 0; i < 5; i++) {
			System.out.printf("[valley]   rivers %-12s wide at the end: ground above the water %.1f blocks on average; the level (rounded to a block) steps once every %.0f blocks%n", names[i],
				cutNodes[i] == 0 ? 0.0 : cutBy[i] / cutNodes[i], steps[i] == 0 ? 0.0 : stepsLength[i] / steps[i]);
		}

		List<Site> sites = RiverSites.pick(rivers);
		BufferedImage sheet = new BufferedImage(2 * SIZE, 2 * SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D sg = sheet.createGraphics();
		for (int s = 0; s < 4; s++) {
			sg.drawImage(window(v, sites.get(s).x(), sites.get(s).z(), sites.get(s).label()), (s % 2) * SIZE, (s / 2) * SIZE, null);
		}
		sg.dispose();
		String name = "earth_" + cfg.seed() + "_c" + cfg.cellBlocks();
		ImageIO.write(sheet, "png", new File(out, name + "_valleys_closeups.png"));

		// cross-sections and a long profile
		BufferedImage figure = new BufferedImage(1500, 3 * 260 + 300, BufferedImage.TYPE_INT_RGB);
		Graphics2D fg = figure.createGraphics();
		fg.setColor(Color.WHITE);
		fg.fillRect(0, 0, figure.getWidth(), figure.getHeight());
		River small = null;
		for (River r : rivers) {
			if (r.length >= 1_500.0 && r.mouthWidth() >= 5.0 && r.mouthWidth() <= 8.0 && (small == null || r.length > small.length)) {
				small = r;
			}
		}
		Site[] cuts = { sites.get(1), sites.get(2), small == null ? sites.get(2) : new Site(0, 0, "", small, small.line.size() / 2) };
		for (int i = 0; i < 3; i++) {
			fg.drawImage(section(v, d, cuts[i], 1500, 260), 0, i * 260, null);
		}
		River big = sites.get(0).river();
		int n = big.line.size();
		double[] ground = new double[n];
		double[] water = new double[n];
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for (int k = 0; k < n; k++) {
			ground[k] = d.smoothBlocks(big.line.get(k)[0], big.line.get(k)[1]);
			water[k] = v.waterAt(big.index, k);
			lo = Math.min(lo, Math.min(ground[k], water[k]));
			hi = Math.max(hi, Math.max(ground[k], water[k]));
		}
		int top = 3 * 260;
		fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		fg.setColor(Color.BLACK);
		fg.setFont(new Font("SansSerif", Font.PLAIN, 12));
		fg.drawString(String.format("Long profile of the widest long river (%.0f km, %.0f blocks wide at its end): ground along its line (grey) and water level (blue), %.0f to %.0f blocks above sea", big.length / 1000.0, big.mouthWidth(), lo, hi), 8, top + 14);
		fg.setStroke(new BasicStroke(1.4f));
		double sx = 1480.0 / n;
		double sy = 250.0 / Math.max(4.0, hi - lo);
		for (int pass = 0; pass < 2; pass++) {
			double[] line = pass == 0 ? ground : water;
			fg.setColor(pass == 0 ? new Color(0xA0A0A0) : new Color(0x2070C0));
			for (int k = 1; k < n; k++) {
				fg.drawLine((int) (10 + (k - 1) * sx), (int) (top + 280 - (line[k - 1] - lo) * sy), (int) (10 + k * sx), (int) (top + 280 - (line[k] - lo) * sy));
			}
		}
		fg.dispose();
		File file = new File(out, name + "_valleys_sections.png");
		ImageIO.write(figure, "png", file);
		System.out.println("[valley] wrote " + new File(out, name + "_valleys_closeups.png").getAbsolutePath() + " and " + file.getAbsolutePath());
	}
}
