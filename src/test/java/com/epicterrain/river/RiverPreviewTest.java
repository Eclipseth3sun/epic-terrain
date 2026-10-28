package com.epicterrain.river;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import com.epicterrain.worldgen.RangeShape;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Draws the planned rivers over the terrain, plus a long profile (ground and water level against distance) of the longest grand
 * river and the longest tributary. Not part of the normal test run.
 *
 * <pre>./gradlew preview --tests com.epicterrain.river.RiverPreviewTest -Depicterrain.seed=4242 -Depicterrain.amount=50
 *     -Depicterrain.cx=0 -Depicterrain.cz=0 -Depicterrain.wpp=24 [-Depicterrain.grand=0.05] [-Depicterrain.sea=10]</pre>
 *
 * <p>Map legend: cyan lines are tributaries and magenta lines grand rivers (thickness grows with the planned width), green dots
 * are sources, white dots mouths in vanilla water, yellow dots rivers ending in another river, red rings waterfalls (bigger means
 * a taller drop), a dark blue wash marks ocean bodies big enough to be a grand river's destination, and the orange (foothill) or
 * purple (wall) dashed lines are the range spines.
 */
@Tag("preview")
class RiverPreviewTest {
	@Test
	void renderRivers() throws Exception {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		int percent = Integer.getInteger("epicterrain.amount", 50);
		int cx = Integer.getInteger("epicterrain.cx", 0);
		int cz = Integer.getInteger("epicterrain.cz", 0);
		int wpp = Integer.getInteger("epicterrain.wpp", 24);
		int size = Integer.getInteger("epicterrain.size", 1024);
		int margin = Integer.getInteger("epicterrain.margin", 36_000);
		double grand = Double.parseDouble(System.getProperty("epicterrain.grand", "0.05"));
		double sea = Double.parseDouble(System.getProperty("epicterrain.sea", "10"));
		File out = new File(System.getProperty("epicterrain.previewDir", "build/preview"));
		out.mkdirs();

		long t0 = System.currentTimeMillis();
		RiverTestWorld world = RiverTestWorld.create(seed, percent);
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(grand).withSeaMinKm2(sea);
		RiverPlanner planner = new RiverPlanner(seed, config, world.terrain, world.catalog);

		int half = size * wpp / 2;
		int x0 = cx - half;
		int z0 = cz - half;

		// plan every range whose cell could send a river into the view
		int cell = world.catalog.cellSize();
		int cellMin = Math.floorDiv(x0 - margin, cell);
		int cellMax = Math.floorDiv(cx + half + margin, cell);
		int cellZMin = Math.floorDiv(z0 - margin, cell);
		int cellZMax = Math.floorDiv(cz + half + margin, cell);
		List<RangeShape> shapes = new ArrayList<>();
		for (int i = cellMin; i <= cellMax; i++) {
			for (int j = cellZMin; j <= cellZMax; j++) {
				shapes.addAll(world.catalog.shapesInCell(i, j));
			}
		}
		System.out.printf("[rivers] seed %d, slider %d%%, view %d km square at (%d, %d): planning %d ranges (grand chance %.0f%%, sea >= %.0f km2)%n",
			seed, percent, size * wpp / 1000, cx, cz, shapes.size(), grand * 100, sea);
		List<RangeRivers> plans = shapes.parallelStream().map(planner::plan).toList();
		long planned = System.currentTimeMillis();

		// ---- statistics ---------------------------------------------------------------------------------------------------
		List<River> rivers = plans.stream().flatMap(p -> p.rivers().stream()).toList();
		List<River> tributaries = rivers.stream().filter(r -> r.kind() == River.Kind.TRIBUTARY).toList();
		List<River> grands = rivers.stream().filter(r -> r.kind() == River.Kind.GRAND).toList();
		int sourcesTried = plans.stream().mapToInt(RangeRivers::sourcesTried).sum();
		int sourcesFailed = plans.stream().mapToInt(RangeRivers::sourcesFailed).sum();
		long rolled = plans.stream().filter(RangeRivers::grandRolled).count();
		System.out.printf("[rivers] %d ranges: %d tributaries (%d of %d sources routed), %d grand rivers (%d ranges rolled one)%n",
			plans.size(), tributaries.size(), sourcesTried - sourcesFailed, sourcesTried, grands.size(), rolled);
		System.out.printf("[rivers] tributary endings: %d in vanilla water, %d in another river%n",
			tributaries.stream().filter(r -> r.ending() == River.Ending.VANILLA_WATER).count(),
			tributaries.stream().filter(r -> r.ending() == River.Ending.RIVER).count());
		report("tributary length (km)", tributaries.stream().mapToDouble(r -> r.length() / 1000).sorted().toArray());
		report("grand river length (km)", grands.stream().mapToDouble(r -> r.length() / 1000).sorted().toArray());
		report("source ground y", rivers.stream().mapToDouble(r -> r.source().ground()).sorted().toArray());
		report("total fall of the water, source to mouth (blocks)", rivers.stream().mapToDouble(r -> r.source().water() - r.mouth().water()).sorted().toArray());
		List<River.Fall> falls = rivers.stream().flatMap(r -> r.falls().stream()).toList();
		report("waterfall drops (blocks)", falls.stream().mapToDouble(River.Fall::drop).sorted().toArray());
		System.out.printf("[rivers] waterfalls: %d (%d plunges, %d cascades, %d curtains); per river median %d%n", falls.size(),
			falls.stream().filter(f -> f.style() == River.Fall.Style.PLUNGE).count(),
			falls.stream().filter(f -> f.style() == River.Fall.Style.CASCADE).count(),
			falls.stream().filter(f -> f.style() == River.Fall.Style.CURTAIN).count(),
			rivers.isEmpty() ? 0 : rivers.stream().mapToInt(r -> r.falls().size()).sorted().toArray()[rivers.size() / 2]);
		report("deepest cut below ground per river (blocks)", rivers.stream().mapToDouble(r -> r.nodes().stream().mapToDouble(River.Node::cut).max().orElse(0)).sorted().toArray());
		for (River.Kind kind : River.Kind.values()) {
			double[] cuts = rivers.stream().filter(r -> r.kind() == kind).flatMap(r -> r.nodes().stream()).mapToDouble(River.Node::cut).sorted().toArray();
			if (cuts.length > 0) {
				System.out.printf("[rivers] %s rivers, cut below the ground along their whole length: median %.0f, p90 %.0f; deeper than 5/15/30 blocks along %.0f/%.0f/%.0f%% of the length%n",
					kind, cuts[cuts.length / 2], cuts[cuts.length * 9 / 10],
					100.0 * java.util.Arrays.stream(cuts).filter(c -> c > 5).count() / cuts.length,
					100.0 * java.util.Arrays.stream(cuts).filter(c -> c > 15).count() / cuts.length,
					100.0 * java.util.Arrays.stream(cuts).filter(c -> c > 30).count() / cuts.length);
			}
		}
		for (River g : grands) {
			System.out.printf("[rivers]   grand %s: %.1f km, source y %.0f -> sea, %d falls (%.0f blocks), mouth width %.0f%n", g.id(), g.length() / 1000,
				g.source().ground(), g.falls().size(), g.falls().stream().mapToDouble(River.Fall::drop).sum(), g.mouth().width());
		}

		// ---- the map -----------------------------------------------------------------------------------------------------
		float[] ground = new float[size * size];
		float[] uplift = new float[size * size];
		IntStream.range(0, size).parallel().forEach(py -> {
			for (int px = 0; px < size; px++) {
				double x = x0 + (double) px * wpp;
				double z = z0 + (double) py * wpp;
				ground[py * size + px] = (float) world.terrain.ground(x, z);
				uplift[py * size + px] = (float) world.terrain.uplift(x, z);
			}
		});
		SeaMap seas = SeaMap.build(world.terrain, cx, cz, half + margin, sea);

		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				image.setRGB(px, py, shade(ground, uplift, size, px, py, wpp, seas.distanceAt(x0 + (double) px * wpp, z0 + (double) py * wpp) == 0.0));
			}
		}
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// range spines
		for (RangeShape shape : shapes) {
			Path2D path = new Path2D.Double();
			for (int i = 0; i <= 40; i++) {
				double[] spine = shape.spine(i / 40.0);
				double[] at = world.catalog.toWorld(spine[0], spine[1]);
				double px = (at[0] - x0) / wpp;
				double py = (at[1] - z0) / wpp;
				if (i == 0) {
					path.moveTo(px, py);
				} else {
					path.lineTo(px, py);
				}
			}
			g.setColor(shape.wall() ? new Color(190, 90, 255, 150) : new Color(255, 160, 40, 150));
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 6f, 5f }, 0f));
			g.draw(path);
		}

		// rivers, big ones last so they sit on top
		for (River river : rivers.stream().sorted(Comparator.comparingDouble(River::length)).toList()) {
			boolean isGrand = river.kind() == River.Kind.GRAND;
			g.setColor(isGrand ? new Color(255, 60, 220) : new Color(70, 230, 255));
			for (int i = 0; i < river.nodes().size() - 1; i++) {
				River.Node a = river.nodes().get(i);
				River.Node b = river.nodes().get(i + 1);
				float w = Math.max(isGrand ? 2.4f : 1.6f, (float) ((a.width() + b.width()) / 2.0 / wpp) * 3f);
				g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(new Line2D.Double((a.x() - x0) / wpp, (a.z() - z0) / wpp, (b.x() - x0) / wpp, (b.z() - z0) / wpp));
			}
		}
		for (River river : rivers) {
			dot(g, river.source(), x0, z0, wpp, new Color(60, 255, 90), 3.0);
			dot(g, river.mouth(), x0, z0, wpp, river.ending() == River.Ending.RIVER ? new Color(255, 230, 40) : Color.WHITE, 3.0);
			for (River.Fall fall : river.falls()) {
				double r = 3.0 + Math.min(10.0, fall.drop() / 25.0);
				double px = (fall.x() - x0) / wpp;
				double py = (fall.z() - z0) / wpp;
				g.setColor(new Color(255, 40, 40));
				g.setStroke(new BasicStroke(1.5f));
				g.draw(new Ellipse2D.Double(px - r, py - r, 2 * r, 2 * r));
			}
		}
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRect(0, 0, 640, 54);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		g.drawString(String.format("seed %d, slider %d%%, centre (%d, %d), %d blocks/px, %d km view", seed, percent, cx, cz, wpp, size * wpp / 1000), 8, 18);
		g.drawString(String.format("%d tributaries (cyan), %d grand rivers (magenta), %d falls (red rings); grand chance %.0f%%, sea >= %.0f km2",
			tributaries.size(), grands.size(), falls.size(), grand * 100, sea), 8, 36);
		g.dispose();
		File file = new File(out, String.format("rivers_%d_%d_x%d_z%d.png", seed, percent, cx, cz));
		ImageIO.write(image, "png", file);
		System.out.println("[rivers] wrote " + file);

		// ---- long profiles -----------------------------------------------------------------------------------------------
		grands.stream().max(Comparator.comparingDouble(River::length)).ifPresent(r -> profile(r, out, seed));
		tributaries.stream().max(Comparator.comparingDouble(River::length)).ifPresent(r -> profile(r, out, seed));
		System.out.printf("[rivers] planned in %.1fs, rendered in %.1fs%n", (planned - t0) / 1000.0, (System.currentTimeMillis() - planned) / 1000.0);
	}

	private static void report(String label, double[] sorted) {
		if (sorted.length == 0) {
			System.out.printf("[rivers] %s: none%n", label);
			return;
		}
		System.out.printf("[rivers] %s: n=%d, p10 %.1f, median %.1f, p90 %.1f, max %.1f%n", label, sorted.length,
			sorted[sorted.length / 10], sorted[sorted.length / 2], sorted[sorted.length * 9 / 10], sorted[sorted.length - 1]);
	}

	private static void dot(Graphics2D g, River.Node node, int x0, int z0, int wpp, Color color, double radius) {
		double px = (node.x() - x0) / wpp;
		double py = (node.z() - z0) / wpp;
		g.setColor(color);
		g.fill(new Ellipse2D.Double(px - radius, py - radius, 2 * radius, 2 * radius));
	}

	private static int shade(float[] ground, float[] uplift, int size, int px, int py, int wpp, boolean targetSea) {
		float h = ground[py * size + px];
		int xl = Math.max(0, px - 1);
		int xr = Math.min(size - 1, px + 1);
		int zu = Math.max(0, py - 1);
		int zd = Math.min(size - 1, py + 1);
		double dx = (ground[py * size + xr] - ground[py * size + xl]) / ((xr - xl) * (double) wpp) * 3.0;
		double dz = (ground[zd * size + px] - ground[zu * size + px]) / ((zd - zu) * (double) wpp) * 3.0;
		double len = Math.sqrt(dx * dx + dz * dz + 1.0);
		double light = Math.max(0.0, (-dx * -0.5 + 0.7 + -dz * -0.5) / len / 0.99);
		double[] rgb;
		if (h < 62.5f) {
			double depth = Math.min(1.0, (62.5 - h) / 40.0);
			rgb = new double[] { 90 - 70 * depth, 140 - 90 * depth, 200 - 80 * depth };
			if (targetSea) {
				rgb[0] *= 0.55;
				rgb[1] *= 0.55;
				rgb[2] *= 0.8;
			}
		} else {
			double t = Math.max(0.0, Math.min(1.0, (h - 62.0) / 420.0));
			if (t < 0.12) {
				rgb = mixColor(new double[] { 95, 145, 75 }, new double[] { 165, 145, 95 }, t / 0.12);
			} else if (t < 0.4) {
				rgb = mixColor(new double[] { 165, 145, 95 }, new double[] { 150, 150, 150 }, (t - 0.12) / 0.28);
			} else {
				rgb = mixColor(new double[] { 150, 150, 150 }, new double[] { 252, 252, 252 }, (t - 0.4) / 0.6);
			}
			if (uplift[py * size + px] > 3f) {
				rgb = mixColor(rgb, new double[] { 255, 150, 50 }, 0.10);
			}
			double k = 0.5 + 0.65 * light;
			rgb = new double[] { rgb[0] * k, rgb[1] * k, rgb[2] * k };
		}
		return (clamp(rgb[0]) << 16) | (clamp(rgb[1]) << 8) | clamp(rgb[2]);
	}

	private static double[] mixColor(double[] a, double[] b, double t) {
		return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	/** Ground and water level along a river, with its falls marked. */
	private static void profile(River river, File out, long seed) {
		try {
			int w = 1100;
			int h = 440;
			int left = 60;
			int right = 20;
			int top = 40;
			int bottom = 40;
			double length = river.length();
			double min = 40;
			double max = 100;
			for (River.Node n : river.nodes()) {
				max = Math.max(max, Math.max(n.ground(), n.water()));
			}
			max = Math.ceil((max + 20) / 20) * 20;
			BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(245, 245, 240));
			g.fillRect(0, 0, w, h);
			double xs = (w - left - right) / length;
			double ys = (h - top - bottom) / (max - min);
			Path2D ground = new Path2D.Double();
			ground.moveTo(left, h - bottom);
			for (River.Node n : river.nodes()) {
				ground.lineTo(left + n.travelled() * xs, h - bottom - (n.ground() - min) * ys);
			}
			ground.lineTo(left + length * xs, h - bottom);
			ground.closePath();
			g.setColor(new Color(170, 150, 120));
			g.fill(ground);
			// sea level
			g.setColor(new Color(120, 160, 220));
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] { 4f, 4f }, 0f));
			g.draw(new Line2D.Double(left, h - bottom - (63 - min) * ys, w - right, h - bottom - (63 - min) * ys));
			// water
			g.setColor(new Color(20, 90, 200));
			g.setStroke(new BasicStroke(2.5f));
			Path2D water = new Path2D.Double();
			boolean first = true;
			for (River.Node n : river.nodes()) {
				double px = left + n.travelled() * xs;
				double py = h - bottom - (n.water() - min) * ys;
				if (first) {
					water.moveTo(px, py);
					first = false;
				} else {
					water.lineTo(px, py);
				}
			}
			g.draw(water);
			g.setColor(new Color(220, 30, 30));
			for (River.Fall fall : river.falls()) {
				River.Node a = river.nodes().get(fall.first());
				River.Node b = river.nodes().get(fall.last());
				g.setStroke(new BasicStroke(4f));
				g.draw(new Line2D.Double(left + a.travelled() * xs, h - bottom - (a.water() - min) * ys, left + b.travelled() * xs, h - bottom - (b.water() - min) * ys));
			}
			g.setColor(Color.BLACK);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
			River.Node mid = river.nodes().get(river.nodes().size() / 2);
			g.drawString(String.format("%s (%s, ends in %s): %.1f km, source y %.0f, %d falls totalling %.0f blocks",
				river.id(), river.kind(), river.ending(), length / 1000, river.source().ground(), river.falls().size(),
				river.falls().stream().mapToDouble(River.Fall::drop).sum()), 8, 18);
			g.drawString(String.format("width %.0f / %.0f / %.0f (source / middle / mouth), depth %.1f / %.1f / %.1f; water brown=ground, blue=water level, red=falls, dashed=sea level 63",
				river.source().width(), mid.width(), river.mouth().width(), river.source().depth(), mid.depth(), river.mouth().depth()), 8, 34);
			for (int y = (int) min; y <= max; y += 40) {
				g.drawString(String.valueOf(y), 8, (int) (h - bottom - (y - min) * ys) + 4);
			}
			for (int k = 0; k <= 5; k++) {
				g.drawString(String.format("%.1f km", length / 1000 * k / 5), (int) (left + length * xs * k / 5) - 14, h - 14);
			}
			g.dispose();
			File file = new File(out, String.format("profile_%d_%s.png", seed, river.id().replaceAll("[^A-Za-z0-9_-]", "_")));
			ImageIO.write(image, "png", file);
			System.out.println("[rivers] wrote " + file);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
