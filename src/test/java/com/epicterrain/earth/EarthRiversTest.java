package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import com.epicterrain.earth.Rivers.River;

import org.junit.jupiter.api.Test;

/** The rivers cut out of a simulated continent: the width rule, how the drainage tree is cut into rivers, and what the winding does. */
class EarthRiversTest {
	private static final Rivers.Dials DIALS = Rivers.Dials.defaults();
	private static final Continent CONTINENT = Seed4242.CONTINENT;
	private static final Detail DETAIL = Seed4242.DETAIL;
	private static final List<River> RIVERS = Seed4242.RIVERS;

	@Test
	void widthGrowsWithTheSquareRootOfTheCatchment() {
		double reference = ContinentSimulator.RIVER_BLOCKS2;
		assertEquals(3.0, Rivers.width(reference, DIALS), 1.0e-9, "the smallest river is 3 blocks wide");
		assertEquals(6.0, Rivers.width(4.0 * reference, DIALS), 1.0e-9, "four times the catchment, twice the width");
		assertEquals(3.0, Rivers.width(0.1 * reference, DIALS), 1.0e-9, "never narrower than the smallest");
		assertEquals(70.0, Rivers.width(2.18e8, DIALS), 0.2, "the biggest catchment of the default window, 218 million square blocks, is about 70 wide");
		assertEquals(5.0, Rivers.width(reference, new Rivers.Dials(5.0)), 1.0e-9, "the dial sets the smallest width");
		double previous = 0.0;
		for (double area = reference; area < 1.0e9; area *= 1.7) {
			double w = Rivers.width(area, DIALS);
			assertTrue(w > previous, "wider with every step in catchment");
			previous = w;
		}
	}

	@Test
	void everyRiverCellBelongsToExactlyOneRiver() {
		int[] owners = new int[CONTINENT.nx * CONTINENT.nz];
		for (River r : RIVERS) {
			// the last cell is where a river ends: water, or a cell of the bigger river it joins
			for (int k = 0; k < r.cells.length - 1; k++) {
				owners[r.cells[k]]++;
			}
		}
		int riverCells = 0;
		for (int i = 0; i < owners.length; i++) {
			if (CONTINENT.river[i]) {
				riverCells++;
				assertEquals(1, owners[i], "river cell " + i + " is on one river");
			} else {
				assertEquals(0, owners[i], "cell " + i + " is not a river's");
			}
		}
		assertTrue(riverCells > 10_000, "the default window has plenty of river: " + riverCells + " cells");
	}

	@Test
	void riversStartAtSourcesAndEndInTheWaterOrOnABiggerRiver() {
		int n = CONTINENT.nx * CONTINENT.nz;
		boolean[] hasRiverUpstream = new boolean[n];
		for (int i = 0; i < n; i++) {
			int r = CONTINENT.receiver[i];
			if (CONTINENT.river[i] && r != i && CONTINENT.river[r]) {
				hasRiverUpstream[r] = true;
			}
		}
		int joining = 0;
		for (River r : RIVERS) {
			assertTrue(!hasRiverUpstream[r.cells[0]], "river " + r.index + " starts at a source");
			int end = r.cells[r.cells.length - 1];
			if (r.joins < 0) {
				assertTrue(!CONTINENT.river[end] || CONTINENT.receiver[end] == end, "river " + r.index + " ends in the water");
			} else {
				joining++;
				River host = RIVERS.get(r.joins);
				boolean onHost = false;
				for (int k = 0; k < host.cells.length - 1; k++) {
					onHost |= host.cells[k] == end;
				}
				assertTrue(onHost, "river " + r.index + " ends on a cell of river " + host.index);
				assertTrue(CONTINENT.area[end] > CONTINENT.area[r.cells[r.cells.length - 2]], "the river it joins drains more");
				assertTrue(host.mouthWidth() >= r.mouthWidth() - 1.0e-9 || host.width[0] > 0.0);
			}
		}
		assertTrue(joining > RIVERS.size() / 2, "most rivers are tributaries: " + joining + " of " + RIVERS.size());
	}

	@Test
	void aRiverThatJoinsAnotherEndsOnItsFinishedLine() {
		int exact = 0;
		int joining = 0;
		for (River r : RIVERS) {
			if (r.joins < 0) {
				continue;
			}
			joining++;
			double[] end = r.line.get(r.line.size() - 1);
			double nearest = Double.MAX_VALUE;
			for (double[] node : RIVERS.get(r.joins).line) {
				nearest = Math.min(nearest, Math.hypot(node[0] - end[0], node[1] - end[1]));
			}
			exact += nearest < 1.0e-6 ? 1 : 0;
			assertTrue(nearest < 2.0 * Rivers.JOIN_CELLS * CONTINENT.cell, "river " + r.index + " ends " + nearest + " blocks from the river it joins");
		}
		assertTrue(exact >= 0.9 * joining, "nearly every tributary meets its river exactly: " + exact + " of " + joining);
	}

	@Test
	void widthNeverShrinksDownstreamAndNodesAreEvenlySpaced() {
		for (River r : RIVERS) {
			assertTrue(r.width[0] >= DIALS.minWidth() - 1.0e-9, "a river is at least the smallest width");
			for (int k = 1; k < r.line.size(); k++) {
				assertTrue(r.width[k] >= r.width[k - 1] - 1.0e-6, "river " + r.index + " does not narrow downstream at node " + k);
				double gap = Math.hypot(r.line.get(k)[0] - r.line.get(k - 1)[0], r.line.get(k)[1] - r.line.get(k - 1)[1]);
				// nodes are evenly spaced along the line (16 blocks of arc), but the distance between two of them is a chord: round a bend of more than about 100 degrees
				// in a narrow stream it can drop under a block (seed 4242 with the 1.1.0 rock layers: one such node in 652 rivers, a 115 degree bend in a river 5 blocks wide)
				assertTrue(gap > 0.5 && gap < 1.6 * Rivers.SPACING, "river " + r.index + " node " + k + " is " + gap + " blocks from the last");
			}
		}
	}

	@Test
	void theBiggestRiversAreAboutSeventyBlocksWide() {
		double widest = 0.0;
		for (River r : RIVERS) {
			widest = Math.max(widest, r.mouthWidth());
		}
		assertTrue(widest > 60.0 && widest < 80.0, "the widest river is about 70 blocks wide: " + widest);
	}

	@Test
	void windingLengthensTheRoutesButNotWildly() {
		double total = 0.0;
		double routed = 0.0;
		for (River r : RIVERS) {
			total += r.length;
			routed += r.routedLength;
			double[] a = r.line.get(0);
			double[] b = r.line.get(r.line.size() - 1);
			assertTrue(r.length >= Math.hypot(b[0] - a[0], b[1] - a[1]) - 1.0e-6);
		}
		System.out.printf("[rivers-test] %d rivers, %.0f blocks (%.0f along the cell chains, %+.0f%%)%n", RIVERS.size(), total, routed, 100.0 * (total / routed - 1.0));
		assertTrue(total > 1.05 * routed && total < 1.6 * routed, "the bends add a fifth or so to the length: " + total + " against " + routed);
	}

	@Test
	void wideRiversDoNotBendTighterThanTheyAreWide() {
		double tight = 0.0;
		double counted = 0.0;
		for (River r : RIVERS) {
			if (r.line.size() > 12 && r.mouthWidth() >= 10.0) {
				tight += Rivers.tightShare(r, 10.0) * r.line.size();
				counted += r.line.size();
			}
		}
		assertTrue(counted > 1_000.0, "enough wide river to judge: " + counted + " nodes");
		System.out.printf("[rivers-test] %.1f%% of the nodes of wide rivers bend tighter than their width%n", 100.0 * tight / counted);
		assertTrue(tight / counted < 0.10, "under a tenth of a wide river's nodes bend tighter than it is wide: " + tight / counted);
	}

	@Test
	void buildingTwiceGivesTheSameRivers() {
		List<River> again = Rivers.build(CONTINENT, DETAIL::smoothBlocks, DIALS);
		assertEquals(RIVERS.size(), again.size());
		for (int i = 0; i < RIVERS.size(); i++) {
			River a = RIVERS.get(i);
			River b = again.get(i);
			assertTrue(Arrays.equals(a.cells, b.cells));
			assertEquals(a.line.size(), b.line.size());
			for (int k = 0; k < a.line.size(); k++) {
				assertEquals(a.line.get(k)[0], b.line.get(k)[0], 0.0);
				assertEquals(a.line.get(k)[1], b.line.get(k)[1], 0.0);
			}
		}
	}
}
