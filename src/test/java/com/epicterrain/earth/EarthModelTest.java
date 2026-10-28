package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import com.epicterrain.earth.Tectonics.Boundary;
import com.epicterrain.earth.Tectonics.Contact;
import com.epicterrain.earth.Tectonics.Plate;

import org.junit.jupiter.api.Test;

/** The Earth-like terrain model: the tectonic skeleton, the simulation and the climate. */
class EarthModelTest {
	private static EarthConfig small(long seed) {
		// a window a third of a plate wide, coarse cells and few steps: quick, but the whole pipeline runs
		return EarthConfig.defaults(seed).withPlate(25_000.0, 0.5).withCell(64).withSteps(30);
	}

	@Test
	void theSkeletonIsDeterministicAndPlateSized() {
		EarthConfig cfg = EarthConfig.defaults(77L);
		Tectonics a = new Tectonics(cfg);
		Tectonics b = new Tectonics(cfg);
		PlateGrid.Hit ha = new PlateGrid.Hit();
		PlateGrid.Hit hb = new PlateGrid.Hit();
		java.util.Set<Long> plates = new java.util.HashSet<>();
		int step = 1_000;
		int span = 250_000; // 10 x 10 plates
		for (int x = 0; x < span; x += step) {
			for (int z = 0; z < span; z += step) {
				a.grid.lookup(x, z, ha);
				b.grid.lookup(x, z, hb);
				assertEquals(ha.i, hb.i);
				assertEquals(ha.j, hb.j);
				assertEquals(ha.distance, hb.distance, 0.0);
				assertTrue(ha.distance >= 0.0 && ha.distance < cfg.plateBlocks(), "a point is within a plate's width of its boundary: " + ha.distance);
				plates.add(((long) ha.i << 32) ^ (ha.j & 0xFFFFFFFFL));
			}
		}
		// ten plates across, ten down: about a hundred plates, give or take the edges and the warp
		assertTrue(plates.size() > 70 && plates.size() < 160, "plates in a 10 x 10 plate area: " + plates.size());
	}

	@Test
	void aboutTheRightShareOfPlatesAreContinental() {
		EarthConfig cfg = EarthConfig.defaults(5L);
		Tectonics tectonics = new Tectonics(cfg);
		int continental = 0;
		int total = 0;
		for (int i = -30; i < 30; i++) {
			for (int j = -30; j < 30; j++) {
				total++;
				continental += tectonics.plate(i, j).continental() ? 1 : 0;
			}
		}
		double share = (double) continental / total;
		assertEquals(cfg.continentalShare(), share, 0.04, "continental share of plates");
	}

	@Test
	void aBoundaryIsTheSameFromBothSides() {
		Tectonics tectonics = new Tectonics(EarthConfig.defaults(9L));
		java.util.EnumSet<Boundary> seen = java.util.EnumSet.noneOf(Boundary.class);
		for (int i = -8; i < 8; i++) {
			for (int j = -8; j < 8; j++) {
				Plate a = tectonics.plate(i, j);
				Plate b = tectonics.plate(i + 1, j);
				Contact ab = tectonics.contact(a, b);
				Contact ba = tectonics.contact(b, a);
				assertEquals(ab.type(), ba.type());
				assertEquals(ab.convergence(), ba.convergence(), 1.0e-9);
				if (ab.type() == Boundary.COLLISION || ab.type() == Boundary.ISLAND_ARC) {
					assertTrue(ab.firstOverrides() != ba.firstOverrides(), "exactly one plate rides over the other");
				}
				if (ab.type() == Boundary.SUBDUCTION) {
					assertEquals(a.continental(), ab.firstOverrides(), "the continent rides over the ocean floor");
				}
				seen.add(ab.type());
			}
		}
		assertTrue(seen.contains(Boundary.COLLISION) && seen.contains(Boundary.SUBDUCTION) && seen.contains(Boundary.TRANSFORM), "the boundary kinds that matter all occur: " + seen);
	}

	@Test
	void theWindowIsCentredOnAContinentalPlate() {
		Tectonics tectonics = new Tectonics(EarthConfig.defaults(4242L));
		double[] centre = tectonics.chooseCenter();
		PlateGrid.Hit hit = new PlateGrid.Hit();
		assertTrue(tectonics.plateAt(centre[0], centre[1], hit).continental());
	}

	@Test
	void theSimulationIsDeterministicAndSane() {
		Continent a = ContinentSimulator.run(small(11L));
		Continent b = ContinentSimulator.run(small(11L));
		assertEquals(Arrays.hashCode(a.height), Arrays.hashCode(b.height), "the same seed gives the same world");
		Continent other = ContinentSimulator.run(small(12L));
		assertNotEquals(Arrays.hashCode(a.height), Arrays.hashCode(other.height), "a different seed gives a different one");
		for (float h : a.height) {
			assertTrue(h > -6_000.0f && h < 12_000.0f, "a height out of range: " + h);
		}
		assertEquals(a.config.continentalWater(), a.metrics.continentalWaterShare, 0.02, "the water dial is met");
		assertTrue(a.metrics.seaShare > 0.0 && a.metrics.seaShare < 0.95);
	}

	@Test
	void waterRunsDownhillToTheSea() {
		Continent c = ContinentSimulator.run(small(21L));
		int n = c.nx * c.nz;
		double most = 0.0;
		for (int i = 0; i < n; i++) {
			if (c.height[i] >= 0.0f) {
				most = Math.max(most, c.area[i]);
			}
		}
		assertTrue(most > 100.0 * c.cellMetres * c.cellMetres, "some river drains a real catchment: " + most);
		// a lake is flat ground that water fills, never open sea
		for (int i = 0; i < n; i++) {
			if (c.lakeDepth[i] > 0.05f) {
				assertTrue(c.height[i] >= 0.0f, "a lake is on land");
			}
		}
	}

	@Test
	void everyLakeIsDeepEnoughForTheGameToShowIt() {
		Continent c = ContinentSimulator.run(small(21L));
		int lakeCells = 0;
		for (int i = 0; i < c.nx * c.nz; i++) {
			if (c.lakeDepth[i] > 0.0f) {
				lakeCells++;
				assertTrue(c.lakeDepth[i] >= EarthConfig.METRES_PER_BLOCK_UP - 1.0e-3f, "a lake shallower than a block: " + c.lakeDepth[i]);
				assertTrue(!c.river[i], "a river is not drawn through a lake");
			}
		}
		assertTrue(c.metrics.lakeFloodedShare >= (double) lakeCells / (c.nx * c.nz), "the flooded ground includes the lakes");
	}

	@Test
	void riversWanderInsteadOfRunningInStraightLines() {
		// Water on flat ground, or on a smooth slope, used to run in perfectly straight lines at 45 degrees for dozens of cells.
		for (long seed : new long[] { 4242L, 11L }) {
			Continent c = ContinentSimulator.run(EarthConfig.defaults(seed).withCell(64).withSteps(40));
			assertTrue(c.metrics.straightestRun <= 20, "seed " + seed + ": a river runs straight for " + c.metrics.straightestRun + " cells " + c.metrics.straightestAt);
		}
	}

	@Test
	void youngBeltsStandHigherThanTheOnesLeftToWearDown() {
		// across a few worlds, wherever both exist, the belts that are still rising are higher than the fossil ones on the whole (a hard bed
		// can keep one fossil belt about as high as a young one, so this is judged over all the worlds and not world by world)
		int compared = 0;
		double young = 0.0;
		double old = 0.0;
		for (long seed = 4242L; seed < 4246L; seed++) {
			EarthConfig cfg = EarthConfig.defaults(seed).withCell(64).withSteps(60);
			Continent c = ContinentSimulator.run(cfg);
			if (c.metrics.youngBlocks > 100.0 && c.metrics.oldBlocks > 20.0) {
				System.out.printf("[model-test] seed %d: fossil belts %.0f blocks, young %.0f%n", seed, c.metrics.oldBlocks, c.metrics.youngBlocks);
				assertTrue(c.metrics.oldBlocks < 1.15 * c.metrics.youngBlocks, "seed " + seed + ": fossil belts " + c.metrics.oldBlocks + " vs young " + c.metrics.youngBlocks);
				young += c.metrics.youngBlocks;
				old += c.metrics.oldBlocks;
				compared++;
			}
		}
		assertTrue(compared >= 1, "at least one of the worlds has both kinds of belt");
		assertTrue(old < young, "fossil belts are lower than young ones on the whole: " + old + " against " + young);
	}

	@Test
	void rainFallsOnTheWindwardSideOfARangeAndNotBehindIt() {
		int nx = 240;
		int nz = 40;
		float[] height = new float[nx * nz];
		for (int z = 0; z < nz; z++) {
			for (int x = 0; x < nx; x++) {
				double ridge = Math.max(0.0, 3_000.0 - Math.abs(x - 120) * 80.0); // a range 75 cells wide in the middle
				height[z * nx + x] = x < 15 ? -50.0f : (float) (100.0 + ridge);
			}
		}
		Climate.Result r = Climate.compute(EarthConfig.defaults(1L), nx, nz, 700.0, height, new boolean[nx * nz]);
		double windward = 0.0;
		double leeward = 0.0;
		for (int x = 90; x < 118; x++) {
			windward += r.rain()[20 * nx + x];
		}
		for (int x = 125; x < 153; x++) {
			leeward += r.rain()[20 * nx + x];
		}
		assertTrue(windward > 1.8 * leeward, "windward " + windward + " vs leeward " + leeward);
		// and the top of the range is cold
		assertTrue(r.temperature()[20 * nx + 120] < r.temperature()[20 * nx + 30] - 15.0f);
	}
}
