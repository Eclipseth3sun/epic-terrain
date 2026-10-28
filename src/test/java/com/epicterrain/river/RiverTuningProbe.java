package com.epicterrain.river;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.epicterrain.worldgen.RiverField;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the rivers do to the ground beside them and how much they wind, measured on the planned rivers of a real world, to tune
 * them without launching the game. Not run by default.
 */
@Tag("preview")
class RiverTuningProbe {
	private static final int[] BANDS = { 0, 100, 160, 240, 340, 10_000 };

	@Test
	void measure() {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		int percent = Integer.getInteger("epicterrain.amount", 47);
		RiverTestWorld world = RiverTestWorld.create(seed, percent);
		RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
		service.ensureReady(0, 0, true);
		List<River> rivers = service.finishedPlans().stream().flatMap(p -> p.rivers().stream()).toList();
		System.out.printf("[tune] seed %d, %d%%: %d rivers%n", seed, percent, rivers.size());

		// ---- how much ground the banks are raised, by how high up the river is
		List<List<Integer>> raised = new ArrayList<>();
		int[] columns = new int[BANDS.length - 1];
		for (int b = 0; b < BANDS.length - 1; b++) {
			raised.add(new ArrayList<>());
		}
		int[] bankLow = new int[BANDS.length - 1];
		int[] nodesIn = new int[BANDS.length - 1];
		for (River river : rivers) {
			List<River.Node> nodes = river.nodes();
			for (int i = 1; i < nodes.size() - 1; i += 2) {
				River.Node n = nodes.get(i);
				River.Node a = nodes.get(i - 1);
				River.Node c = nodes.get(i + 1);
				double tx = c.x() - a.x();
				double tz = c.z() - a.z();
				double len = Math.hypot(tx, tz);
				if (len <= 0) {
					continue;
				}
				double nx = -tz / len;
				double nz = tx / len;
				int band = band(n.ground());
				nodesIn[band]++;
				double half = Channel.halfWidth(n);
				boolean low = false;
				for (int side = -1; side <= 1; side += 2) {
					double bank = world.terrain.ground(n.x() + side * nx * (half + 1.5), n.z() + side * nz * (half + 1.5));
					if (bank < n.water()) {
						low = true;
					}
					for (double out = 0.5; out <= Channel.LEVEE_REACH; out += 1.0) {
						double lateral = half + out;
						int natural = (int) Math.floor(world.terrain.ground(n.x() + side * nx * lateral, n.z() + side * nz * lateral));
						Channel.Shape shape = Channel.shape(lateral, half, n.water(), n.water(), n.depth(), natural);
						columns[band]++;
						if (shape.solidTop() > natural) {
							raised.get(band).add(shape.solidTop() - natural);
						}
					}
				}
				bankLow[band] += low ? 1 : 0;
			}
		}
		for (int b = 0; b < BANDS.length - 1; b++) {
			List<Integer> r = raised.get(b);
			r.sort(Integer::compare);
			System.out.printf("[tune] ground %3d-%-5d: %6d bank columns, %5.1f%% raised (median %d, p90 %d, max %d); a bank lower than the water at %5.1f%% of %d nodes%n",
				BANDS[b], BANDS[b + 1], columns[b], columns[b] == 0 ? 0.0 : 100.0 * r.size() / columns[b],
				r.isEmpty() ? 0 : r.get(r.size() / 2), r.isEmpty() ? 0 : r.get((int) (r.size() * 0.9)), r.isEmpty() ? 0 : r.get(r.size() - 1),
				nodesIn[b] == 0 ? 0.0 : 100.0 * bankLow[b] / nodesIn[b], nodesIn[b]);
		}

		// ---- how much they wind: arc length over chord, for stretches of 600 blocks, by width
		double[] widthBands = { 0, 5, 10, 20, 40, 1000 };
		List<List<Double>> sinuosity = new ArrayList<>();
		for (int b = 0; b < widthBands.length - 1; b++) {
			sinuosity.add(new ArrayList<>());
		}
		for (River river : rivers) {
			List<River.Node> nodes = river.nodes();
			for (int i = 0; i + 18 < nodes.size(); i += 6) {
				River.Node from = nodes.get(i);
				River.Node to = nodes.get(i + 18);
				double arc = to.travelled() - from.travelled();
				double chord = Math.hypot(to.x() - from.x(), to.z() - from.z());
				double width = nodes.get(i + 9).width();
				for (int b = 0; b < widthBands.length - 1; b++) {
					if (width >= widthBands[b] && width < widthBands[b + 1]) {
						sinuosity.get(b).add(arc / Math.max(1.0, chord));
					}
				}
			}
		}
		for (int b = 0; b < widthBands.length - 1; b++) {
			List<Double> s = sinuosity.get(b);
			double[] v = s.stream().mapToDouble(Double::doubleValue).sorted().toArray();
			System.out.printf("[tune] width %4.0f-%-5.0f: %5d stretches of 576 blocks, arc/chord median %.3f, p90 %.3f%n", widthBands[b], widthBands[b + 1], v.length,
				v.length == 0 ? 0.0 : v[v.length / 2], v.length == 0 ? 0.0 : v[(int) (v.length * 0.9)]);
		}
		System.out.println("[tune] " + Arrays.toString(Arrays.copyOf(columns, columns.length)));

		// ---- how tight the bends are: radius of the circle through a node and the ones 3 either side, in river widths
		List<Double> radii = new ArrayList<>();
		for (River river : rivers) {
			List<River.Node> nodes = river.nodes();
			for (int i = 3; i + 3 < nodes.size(); i++) {
				int span = span(nodes, i);
				if (i < span || i + span >= nodes.size()) {
					continue;
				}
				River.Node p = nodes.get(i - span);
				River.Node q = nodes.get(i);
				River.Node r = nodes.get(i + span);
				double a = Math.hypot(q.x() - p.x(), q.z() - p.z());
				double b = Math.hypot(r.x() - q.x(), r.z() - q.z());
				double c = Math.hypot(r.x() - p.x(), r.z() - p.z());
				double area2 = Math.abs((q.x() - p.x()) * (r.z() - p.z()) - (q.z() - p.z()) * (r.x() - p.x()));
				if (q.width() >= 10.0 && area2 > 1.0e-6) {
					radii.add((a * b * c / (2.0 * area2)) / q.width());
				}
			}
		}
		// the grand rivers nearest the origin, to go and look at (a world made with this seed and slider percent has them here)
		List<River> grands = new ArrayList<>(rivers.stream().filter(r -> r.kind() == River.Kind.GRAND).toList());
		grands.sort(java.util.Comparator.comparingDouble(r -> Math.hypot(r.source().x(), r.source().z())));
		for (int k = 0; k < Math.min(8, grands.size()); k++) {
			River g = grands.get(k);
			River.Node mid = g.nodes().get(g.nodes().size() / 2);
			double chord = Math.hypot(g.mouth().x() - g.source().x(), g.mouth().z() - g.source().z());
			System.out.printf("[tune]   grand %s: source (%.0f, %.0f) y%.0f, middle (%.0f, %.0f) y%.0f %.0f wide, mouth (%.0f, %.0f) %.0f wide; %.1f km long, winding %.2f, %d falls%n",
				g.id(), g.source().x(), g.source().z(), g.source().ground(), mid.x(), mid.z(), mid.ground(), mid.width(), g.mouth().x(), g.mouth().z(), g.mouth().width(),
				g.length() / 1000.0, g.length() / Math.max(1.0, chord), g.falls().size());
		}

		// the worst few, to look at
		record Worst(String id, double travelled, double width, double radius, double x, double z, double headingChange) {
		}
		List<Worst> worst = new ArrayList<>();
		for (River river : rivers) {
			List<River.Node> nodes = river.nodes();
			for (int i = 3; i + 3 < nodes.size(); i++) {
				int span = span(nodes, i);
				if (i < span || i + span >= nodes.size()) {
					continue;
				}
				River.Node p = nodes.get(i - span);
				River.Node q = nodes.get(i);
				River.Node r = nodes.get(i + span);
				double a = Math.hypot(q.x() - p.x(), q.z() - p.z());
				double b = Math.hypot(r.x() - q.x(), r.z() - q.z());
				double c = Math.hypot(r.x() - p.x(), r.z() - p.z());
				double area2 = Math.abs((q.x() - p.x()) * (r.z() - p.z()) - (q.z() - p.z()) * (r.x() - p.x()));
				if (river.kind() == River.Kind.GRAND && q.width() >= 40.0 && area2 > 1.0e-6) {
					double h1 = Math.atan2(q.z() - p.z(), q.x() - p.x());
					double h2 = Math.atan2(r.z() - q.z(), r.x() - q.x());
					double turn = Math.abs(Math.toDegrees(h2 - h1));
					worst.add(new Worst(river.id(), q.travelled(), q.width(), (a * b * c / (2.0 * area2)) / q.width(), q.x(), q.z(), turn > 180 ? 360 - turn : turn));
				}
			}
		}
		worst.sort(java.util.Comparator.comparingDouble(Worst::radius));
		for (int k = 0; k < Math.min(8, worst.size()); k++) {
			Worst w = worst.get(k);
			System.out.printf("[tune]   worst %s at %.0f blocks, %.0f wide, (%.0f, %.0f): radius %.2f widths, turn %.0f degrees over 6 nodes%n", w.id(), w.travelled(), w.width(), w.x(), w.z(), w.radius(), w.headingChange());
		}

		if (!worst.isEmpty()) {
			Worst w = worst.get(0);
			River river = rivers.stream().filter(r -> r.id().equals(w.id())).findFirst().orElseThrow();
			int at = 0;
			for (int i = 0; i < river.nodes().size(); i++) {
				if (Math.abs(river.nodes().get(i).travelled() - w.travelled()) < 1.0) {
					at = i;
				}
			}
			System.out.printf("[tune]   %s has %d nodes, %.0f blocks, ends %s; the worst bend is at node %d%n", river.id(), river.nodes().size(), river.length(), river.ending(), at);
			for (int i = Math.max(0, at - 14); i <= Math.min(river.nodes().size() - 1, at + 14); i += 2) {
				River.Node nd = river.nodes().get(i);
				System.out.printf("[tune]     node %d: (%.0f, %.0f) width %.0f ground %.0f water %.0f%n", i, nd.x(), nd.z(), nd.width(), nd.ground(), nd.water());
			}
		}

		// where the tight ones are: by kind and by how far from the mouth
		for (River.Kind kind : River.Kind.values()) {
			for (int zone = 0; zone < 3; zone++) {
				List<Double> some = new ArrayList<>();
				for (River river : rivers) {
					if (river.kind() != kind) {
						continue;
					}
					List<River.Node> nodes = river.nodes();
					for (int i = 3; i + 3 < nodes.size(); i++) {
						int span = span(nodes, i);
						if (i < span || i + span >= nodes.size()) {
							continue;
						}
						River.Node p = nodes.get(i - span);
						River.Node q = nodes.get(i);
						River.Node r = nodes.get(i + span);
						double toMouth = river.length() - q.travelled();
						int z = toMouth < 800 ? 0 : q.travelled() < 800 ? 1 : 2; // 0 near the mouth, 1 near the source, 2 between
						double a = Math.hypot(q.x() - p.x(), q.z() - p.z());
						double b = Math.hypot(r.x() - q.x(), r.z() - q.z());
						double c = Math.hypot(r.x() - p.x(), r.z() - p.z());
						double area2 = Math.abs((q.x() - p.x()) * (r.z() - p.z()) - (q.z() - p.z()) * (r.x() - p.x()));
						if (z == zone && q.width() >= 10.0 && area2 > 1.0e-6) {
							some.add((a * b * c / (2.0 * area2)) / q.width());
						}
					}
				}
				double[] s = some.stream().mapToDouble(Double::doubleValue).sorted().toArray();
				if (s.length > 20) {
					System.out.printf("[tune]   %s %-12s: %5d nodes, radius p5 %.2f, median %.1f widths; under 1.5 at %.1f%%%n", kind, new String[] { "near mouth", "near source", "between" }[zone],
						s.length, s[s.length / 20], s[s.length / 2], 100.0 * some.stream().filter(v -> v < 1.5).count() / s.length);
				}
			}
		}
		double[] rw = radii.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		if (rw.length > 0) {
			System.out.printf("[tune] bend radius in river widths (rivers 10+ wide, %d nodes): min %.2f, p1 %.2f, p5 %.2f, median %.1f; under 1.5 widths at %.2f%% of nodes%n",
				rw.length, rw[0], rw[rw.length / 100], rw[rw.length / 20], rw[rw.length / 2], 100.0 * radii.stream().filter(v -> v < 1.5).count() / rw.length);
		}

		// ---- and where along the river they wind: the same stretches, by kind and by third of the way down
		String[] thirds = { "upper third ", "middle third", "lower third " };
		for (River.Kind kind : River.Kind.values()) {
			List<List<Double>> byThird = new ArrayList<>();
			for (int t = 0; t < 3; t++) {
				byThird.add(new ArrayList<>());
			}
			for (River river : rivers) {
				List<River.Node> nodes = river.nodes();
				if (river.kind() != kind || nodes.get(nodes.size() - 1).travelled() < 1500.0) {
					continue;
				}
				for (int i = 0; i + 18 < nodes.size(); i += 6) {
					River.Node from = nodes.get(i);
					River.Node to = nodes.get(i + 18);
					double chord = Math.hypot(to.x() - from.x(), to.z() - from.z());
					double along = nodes.get(i + 9).travelled() / nodes.get(nodes.size() - 1).travelled();
					byThird.get(Math.min(2, (int) (along * 3))).add((to.travelled() - from.travelled()) / Math.max(1.0, chord));
				}
			}
			for (int t = 0; t < 3; t++) {
				double[] v = byThird.get(t).stream().mapToDouble(Double::doubleValue).sorted().toArray();
				System.out.printf("[tune] %-9s %s: %5d stretches, arc/chord median %.3f, p90 %.3f%n", kind, thirds[t], v.length,
					v.length == 0 ? 0.0 : v[v.length / 2], v.length == 0 ? 0.0 : v[(int) (v.length * 0.9)]);
			}
		}
	}

	/** How many nodes make about one river width of line: the scale a bend has to be judged at. */
	private static int span(List<River.Node> nodes, int i) {
		double spacing = Math.max(1.0, (nodes.get(nodes.size() - 1).travelled() - nodes.get(0).travelled()) / (nodes.size() - 1));
		return (int) Math.max(3, Math.min(40, Math.round(nodes.get(i).width() / spacing)));
	}

	private static int band(double ground) {
		for (int b = 0; b < BANDS.length - 1; b++) {
			if (ground < BANDS[b + 1]) {
				return b;
			}
		}
		return BANDS.length - 2;
	}
}
