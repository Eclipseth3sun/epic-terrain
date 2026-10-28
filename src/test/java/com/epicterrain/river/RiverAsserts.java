package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/** The rules every planned river must obey, whatever the terrain. */
final class RiverAsserts {
	private static final double EPS = 1.0e-6;

	private RiverAsserts() {
	}

	static void assertValid(River river, RiverConfig config, List<River> allInRange) {
		String id = river.id();
		List<River.Node> nodes = river.nodes();
		assertTrue(nodes.size() >= 3, id + " needs at least three nodes");
		assertEquals(0.0, nodes.get(0).travelled(), EPS, id + " starts at distance 0");
		assertEquals(river.length(), nodes.get(nodes.size() - 1).travelled(), 1.0e-6, id + " length is the last node's distance");

		boolean flares = river.ending() != River.Ending.RIVER;
		RiverConfig.Profile profile = river.kind() == River.Kind.GRAND ? config.grand() : config.tributary();
		for (int i = 0; i < nodes.size(); i++) {
			River.Node node = nodes.get(i);
			boolean last = i == nodes.size() - 1;
			if (i > 0) {
				River.Node previous = nodes.get(i - 1);
				assertTrue(node.travelled() > previous.travelled(), id + " distance must grow at node " + i);
				assertTrue(node.water() <= previous.water() + EPS, id + " the water may never rise downstream (node " + i + ": " + previous.water() + " -> " + node.water() + ")");
				assertTrue(node.width() >= previous.width() - 1.0e-9, id + " width must not shrink downstream (node " + i + ")");
				assertTrue(node.depth() >= previous.depth() - 1.0e-9, id + " depth must not shrink downstream (node " + i + ")");
				double step = Math.hypot(node.x() - previous.x(), node.z() - previous.z());
				// the last node can be up to half a spacing further on, because a remainder shorter than that is folded into it
				assertTrue(step <= config.nodeSpacing() * 1.5 + EPS, id + " nodes are at most one and a half spacings apart: " + step);
			}
			assertTrue(node.water() >= River.SEA_LEVEL - EPS, id + " the water never goes below sea level at node " + i);
			// the water runs two blocks under the lowest ground so far, floored at sea level (a join's last node takes its parent's level)
			if (!(last && river.ending() == River.Ending.RIVER)) {
				double lowest = Double.MAX_VALUE;
				for (int j = 0; j <= i; j++) {
					lowest = Math.min(lowest, nodes.get(j).ground() - 2.0);
				}
				assertTrue(node.water() <= Math.max(River.SEA_LEVEL, lowest) + EPS, id + " the water must not run above the ground it flows over at node " + i);
			}
			assertTrue(node.width() > 0 && node.depth() > 0, id + " has a positive channel");
			assertEquals(profile.widthAt(node.travelled(), river.length(), flares), node.width(), 1.0e-9, id + " width follows the profile at node " + i);
			assertEquals(profile.depthAt(node.travelled(), river.length(), flares), node.depth(), 1.0e-9, id + " depth follows the profile at node " + i);
		}

		// the source stands high; the mouth is in water, or in another river
		assertTrue(river.source().ground() >= config.minSourceGround() - 1, id + " source must stand high: " + river.source().ground());
		switch (river.ending()) {
			case VANILLA_WATER -> assertTrue(river.mouth().ground() <= 62.5 + EPS, id + " ends in water: ground " + river.mouth().ground());
			case SEA -> assertTrue(river.mouth().ground() <= 63.5 + EPS, id + " ends at sea level: ground " + river.mouth().ground());
			case RIVER -> {
				assertFalse(river.joins().isEmpty(), id + " names the river it joins");
				River parent = allInRange.stream().filter(r -> r.id().equals(river.joins())).findFirst().orElse(null);
				assertTrue(parent != null, id + " joins " + river.joins() + ", which must be in the same range plan");
				boolean onIt = parent.nodes().stream().anyMatch(n -> Math.hypot(n.x() - river.mouth().x(), n.z() - river.mouth().z()) < 1.0);
				assertTrue(onIt, id + " ends on a node of " + river.joins());
				River.Node at = parent.nodes().stream().filter(n -> Math.hypot(n.x() - river.mouth().x(), n.z() - river.mouth().z()) < 1.0).findFirst().orElseThrow();
				assertTrue(river.mouth().water() >= at.water() - 3.5, id + " must not enter its parent below the parent's surface");
			}
		}

		// falls: inside the river, in order, and each a real drop
		int previousLast = -1;
		for (River.Fall fall : river.falls()) {
			assertTrue(fall.first() >= 0 && fall.last() < nodes.size() && fall.first() < fall.last(), id + " fall indices");
			assertTrue(fall.first() >= previousLast, id + " falls must not overlap");
			previousLast = fall.last();
			assertTrue(fall.drop() >= config.fallMinDrop() - EPS, id + " a fall drops at least " + config.fallMinDrop() + ": " + fall.drop());
			assertEquals(nodes.get(fall.first()).water() - nodes.get(fall.last()).water(), fall.drop(), 1.0e-6, id + " fall drop matches the water levels");
			assertTrue(fall.run() > 0 && fall.width() > 0, id + " fall geometry");
		}
	}
}
