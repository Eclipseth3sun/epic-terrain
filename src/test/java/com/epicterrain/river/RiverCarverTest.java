package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The carved result must hold its water in, whatever the ground was like. */
class RiverCarverTest {
	private static final int MIN_Y = -64;
	private static final int HEIGHT = 480;
	private static final byte AIR = 0;
	private static final byte SOLID = 1;
	private static final byte WATER = 2;

	/** A rectangle of columns, each a stack of blocks, that the carver can read and write. */
	static final class Grid implements RiverCarver.Columns {
		final int x0;
		final int z0;
		final int width;
		final int depth;
		final byte[][] blocks;
		final int[] natural;

		Grid(int x0, int z0, int width, int depth, java.util.function.ToIntBiFunction<Integer, Integer> floor) {
			this.x0 = x0;
			this.z0 = z0;
			this.width = width;
			this.depth = depth;
			this.blocks = new byte[width * depth][HEIGHT];
			this.natural = new int[width * depth];
			for (int i = 0; i < width; i++) {
				for (int j = 0; j < depth; j++) {
					int f = floor.applyAsInt(x0 + i, z0 + j);
					this.natural[i * depth + j] = f;
					byte[] column = this.blocks[i * depth + j];
					for (int y = 0; y < HEIGHT; y++) {
						int worldY = y + MIN_Y;
						column[y] = worldY <= f ? SOLID : worldY <= 62 ? WATER : AIR; // the sea fills what the ground leaves under 63
					}
				}
			}
		}

		byte at(int x, int y, int z) {
			if (x < this.x0 || z < this.z0 || x >= this.x0 + this.width || z >= this.z0 + this.depth || y - MIN_Y < 0 || y - MIN_Y >= HEIGHT) {
				return SOLID; // outside the window: assume the neighbour is fine
			}
			return this.blocks[(x - this.x0) * this.depth + (z - this.z0)][y - MIN_Y];
		}

		@Override
		public int floor(int x, int z) {
			return this.natural[(x - this.x0) * this.depth + (z - this.z0)];
		}

		@Override
		public void set(int x, int y, int z, RiverCarver.Material material) {
			byte value = switch (material) {
				case AIR -> AIR;
				case WATER -> WATER;
				default -> SOLID;
			};
			this.blocks[(x - this.x0) * this.depth + (z - this.z0)][y - MIN_Y] = value;
		}

		@Override
		public void raise(int x, int z, int floor, int top) {
			for (int y = floor + 1; y <= top; y++) {
				this.blocks[(x - this.x0) * this.depth + (z - this.z0)][y - MIN_Y] = SOLID;
			}
		}

		@Override
		public boolean solid(int x, int y, int z) {
			return at(x, y, z) == SOLID;
		}

		@Override
		public boolean air(int x, int y, int z) {
			return at(x, y, z) == AIR;
		}

		/** Hollows a cave under every column: air from {@code below + thickness} down to {@code below} blocks under the generated floor. */
		void hollow(int below, int thickness) {
			for (int i = 0; i < this.width; i++) {
				for (int j = 0; j < this.depth; j++) {
					int f = this.natural[i * this.depth + j];
					for (int y = f - below - thickness + 1; y <= f - below; y++) {
						this.blocks[i * this.depth + j][y - MIN_Y] = AIR;
					}
				}
			}
		}

		int solidTop(int x, int z) {
			byte[] column = this.blocks[(x - this.x0) * this.depth + (z - this.z0)];
			for (int y = HEIGHT - 1; y >= 0; y--) {
				if (column[y] == SOLID) {
					return y + MIN_Y;
				}
			}
			return MIN_Y - 1;
		}
	}

	private static RiverService serviceOver(RiverPlannerTest.Synthetic world, double grandChance) {
		RiverPlanner planner = new RiverPlanner(99L, RiverConfig.DEFAULT.withGrandChance(grandChance).withMinWaterKm2(0.0), world, world);
		return new RiverService(planner, world, Runnable::run);
	}

	/** Carves every chunk of a window around a point and returns the window. */
	private static Grid carved(RiverService service, RiverPlannerTest.Synthetic world, int centerX, int centerZ, int chunks) {
		return carved(service, world, centerX, centerZ, chunks, false);
	}

	/** As above; with {@code caves} a cavern lies under the whole window, from 4 blocks under the ground to 30 under it. */
	private static Grid carved(RiverService service, RiverPlannerTest.Synthetic world, int centerX, int centerZ, int chunks, boolean caves) {
		int cx = Math.floorDiv(centerX, 16) - chunks;
		int cz = Math.floorDiv(centerZ, 16) - chunks;
		int size = (2 * chunks + 1) * 16;
		Grid grid = new Grid(cx * 16, cz * 16, size, size, (x, z) -> (int) Math.floor(world.ground(x, z)));
		if (caves) {
			grid.hollow(4, 26);
		}
		service.ensureReady(centerX, centerZ, true);
		for (int i = 0; i <= 2 * chunks; i++) {
			for (int j = 0; j <= 2 * chunks; j++) {
				RiverCarver.carve(service, cx + i, cz + j, grid);
			}
		}
		return grid;
	}

	@Test
	void waterIsAlwaysHeldIn() {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic();
		RiverService service = serviceOver(world, 0.0);
		service.ensureReady(0, 0, true);
		RangeRivers plan = service.finishedPlans().stream().filter(p -> p.range().equals(RiverPlannerTest.Synthetic.WALL)).findFirst().orElseThrow();
		assertFalse(plan.rivers().isEmpty());

		int checkedRivers = 0;
		for (River river : plan.rivers()) {
			// a window around the river's middle
			River.Node middle = river.nodes().get(river.nodes().size() / 2);
			Grid grid = carved(service, world, (int) middle.x(), (int) middle.z(), 5);
			int water = 0;
			for (int x = grid.x0 + 2; x < grid.x0 + grid.width - 2; x++) {
				for (int z = grid.z0 + 2; z < grid.z0 + grid.depth - 2; z++) {
					for (int y = MIN_Y + 1; y < MIN_Y + HEIGHT - 1; y++) {
						if (grid.at(x, y, z) != WATER) {
							continue;
						}
						water++;
						// water never floats over air
						assertTrue(grid.at(x, y - 1, z) != AIR, river.id() + ": water floats over air at " + x + "," + y + "," + z);
						// and is never open to the side where the neighbour column is lower than the water's own level: only the
						// water's own column of neighbours may be air above THEIR water, which is the river running downhill
						for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
							byte beside = grid.at(x + d[0], y, z + d[1]);
							RiverService.ColumnRiver neighbour = service.at(x + d[0] + 0.5, z + d[1] + 0.5);
							boolean neighbourInChannel = neighbour != null && neighbour.lateral() <= neighbour.halfWidth();
							if (beside == AIR && !neighbourInChannel) {
								// open air beside water is fine inside the channel (the river stepping down its own bed) and, outside it,
								// only if it ends in more water below (a fall into a pool); ending in ground is a spill
								int below = y - 1;
								while (grid.at(x + d[0], below, z + d[1]) == AIR && below > MIN_Y + 1) {
									below--;
								}
								assertEquals(WATER, grid.at(x + d[0], below, z + d[1]),
									river.id() + ": water spills onto the ground at " + x + "," + y + "," + z + " toward " + d[0] + "," + d[1]);
							}
						}
					}
				}
			}
			assertTrue(water > 0, river.id() + " should hold some water around its middle");
			checkedRivers++;
		}
		assertTrue(checkedRivers >= 2);
	}

	@Test
	void aGorgeThatCutsIntoCavesNeitherHangsTheRiverOverThemNorLeaksIntoThem() {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic();
		RiverService service = serviceOver(world, 0.0);
		service.ensureReady(0, 0, true);
		RangeRivers plan = service.finishedPlans().stream().filter(p -> p.range().equals(RiverPlannerTest.Synthetic.WALL)).findFirst().orElseThrow();

		int water = 0;
		int caveAir = 0;
		for (River river : plan.rivers()) {
			River.Node middle = river.nodes().get(river.nodes().size() / 2);
			Grid grid = carved(service, world, (int) middle.x(), (int) middle.z(), 5, true);
			for (int x = grid.x0 + 2; x < grid.x0 + grid.width - 2; x++) {
				for (int z = grid.z0 + 2; z < grid.z0 + grid.depth - 2; z++) {
					for (int y = MIN_Y + 1; y < MIN_Y + HEIGHT - RiverCarver.CAVE_ROOF - 1; y++) {
						byte block = grid.at(x, y, z);
						if (block == AIR && y < grid.floor(x, z) - 3) {
							caveAir++;
						}
						if (block != WATER || y <= Channel.SEA_TOP) {
							continue;
						}
						water++;
						// the river rests on rock: the bed and the blocks under it are solid, however hollow the ground was
						int bed = y - 1;
						while (grid.at(x, bed, z) == WATER) {
							bed--;
						}
						for (int k = 0; k < RiverCarver.BED_THICKNESS; k++) {
							assertEquals(SOLID, grid.at(x, bed - k, z), river.id() + ": the bed under " + x + "," + y + "," + z + " is hollow " + k + " blocks down");
						}
						// and no cave opens onto its side: air touching the water has no roof over it
						for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
							if (grid.at(x + d[0], y, z + d[1]) != AIR) {
								continue;
							}
							for (int above = 1; above <= RiverCarver.CAVE_ROOF; above++) {
								assertTrue(grid.at(x + d[0], y + above, z + d[1]) != SOLID,
									river.id() + ": a cave opens onto the water at " + x + "," + y + "," + z + " toward " + d[0] + "," + d[1]);
							}
						}
					}
				}
			}
		}
		assertTrue(water > 0, "the rivers should hold some water");
		assertTrue(caveAir > 0, "the caves should not all have been filled in");
	}

	@Test
	void theBedIsCutToTheProfileAndGorgeWallsStayWithinTheirSlope() {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic();
		RiverService service = serviceOver(world, 0.0);
		service.ensureReady(0, 0, true);
		RangeRivers plan = service.finishedPlans().get(0);
		River river = plan.rivers().get(0);
		River.Node node = river.nodes().get(river.nodes().size() / 3);
		Grid grid = carved(service, world, (int) node.x(), (int) node.z(), 4);

		RiverService.ColumnRiver at = service.at(node.x(), node.z());
		assertNotNull(at);
		assertTrue(at.lateral() < 1.5, "the middle of the river is on its line");
		int x = (int) Math.floor(node.x());
		int z = (int) Math.floor(node.z());
		double expectedBed = Math.floor(at.water() - at.depth() * (1.0 - Math.pow(at.lateral() / at.halfWidth(), 2)));
		assertEquals((int) Math.min(grid.natural[(x - grid.x0) * grid.depth + (z - grid.z0)], expectedBed), grid.solidTop(x, z) , 1, "the bed sits at the profile's depth under the water");

		// moving away from the water the ground may rise no faster than the wall slope (plus a block of rounding)
		for (int i = 4; i < grid.width - 4; i++) {
			for (int j = 4; j < grid.depth - 4; j++) {
				RiverService.ColumnRiver c = service.at(grid.x0 + i + 0.5, grid.z0 + j + 0.5);
				if (c == null || c.lateral() <= c.halfWidth()) {
					continue;
				}
				double allowed = Math.floor(c.water() + 1.0 + Channel.WALL_SLOPE * (c.lateral() - c.halfWidth()));
				int top = grid.solidTop(grid.x0 + i, grid.z0 + j);
				int natural = grid.natural[i * grid.depth + j];
				if (natural > allowed) {
					assertTrue(top <= allowed + 1, "gorge wall too steep at " + (grid.x0 + i) + "," + (grid.z0 + j) + ": " + top + " vs " + allowed);
				}
			}
		}
	}

	@Test
	void nothingChangesBeyondTheRiversReach() {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic();
		RiverService service = serviceOver(world, 0.0);
		service.ensureReady(0, 0, true);
		RangeRivers plan = service.finishedPlans().get(0);
		River river = plan.rivers().get(0);
		River.Node node = river.nodes().get(river.nodes().size() / 2);
		Grid grid = carved(service, world, (int) node.x(), (int) node.z(), 6);
		for (int i = 0; i < grid.width; i++) {
			for (int j = 0; j < grid.depth; j++) {
				RiverService.ColumnRiver c = service.at(grid.x0 + i + 0.5, grid.z0 + j + 0.5);
				if (c == null) {
					assertEquals(grid.natural[i * grid.depth + j], grid.solidTop(grid.x0 + i, grid.z0 + j), "a column no river reaches must be untouched");
				}
			}
		}
	}

	@Test
	void aRiverOverVanillaWaterLeavesTheLakeBedAlone() {
		// column math on its own: a channel column whose floor is already well below the river's bed keeps its floor
		Channel.Shape shape = Channel.shape(0.0, 4.0, 63.0, 3.0, 40);
		assertEquals(40, shape.solidTop(), "the floor of deeper water is not raised");
		assertEquals(62, shape.waterTop());
		// ground that is already under vanilla water beside the channel is never touched
		assertTrue(Channel.shape(9.0, 4.0, 63.0, 3.0, 50).untouched(50));
		// ground far from any channel is not touched either way: here the ground stays inside the allowed band
		assertEquals(70, Channel.shape(6.0, 4.0, 63.0, 3.0, 70).solidTop() >= 63 ? 70 : -1);
	}

	@Test
	void aBankTheGroundLeftALittleLowIsToppedUp() {
		// the ground beside the channel is a few blocks lower than the water's top: the lip lifts it, but only a little
		Channel.Shape bank = Channel.shape(5.0, 4.0, 70.0, 3.0, 66);
		assertEquals(66 + Channel.MAX_LIFT, bank.solidTop(), "the lip is capped at " + Channel.MAX_LIFT + " blocks");
		// a bank that is a little low gets its lift all the way to the water: one block under the lip's own height
		assertEquals(69, Channel.shape(4.5, 4.0, 70.0, 3.0, 68).solidTop());
		// and it fades back to the natural ground further out
		assertEquals(66, Channel.shape(4.0 + Channel.LEVEE_REACH + 3.0, 4.0, 70.0, 3.0, 66).solidTop());
	}

	@Test
	void waterDoesNotStandAsAWallOverACliff() {
		// planned bed at 97 in the middle (water 100, depth 3)
		assertEquals(97, Channel.shape(0.0, 4.0, 100.0, 3.0, 97).solidTop(), "ground at the bed: carved as usual");
		assertEquals(99, Channel.shape(0.0, 4.0, 100.0, 3.0, 97).waterTop());
		Channel.Shape pool = Channel.shape(0.0, 4.0, 100.0, 3.0, 95);
		assertEquals(99, pool.waterTop(), "ground two under the bed still holds a deeper pool");
		Channel.Shape cliff = Channel.shape(0.0, 4.0, 100.0, 3.0, 80);
		assertTrue(cliff.untouched(80), "ground far under the bed is a cliff: left alone, no wall of water");
		assertFalse(cliff.inChannel());
		// the sea is not a cliff: a river over the sea (water 63) leaves vanilla's own water as it is
		assertEquals(62, Channel.shape(0.0, 4.0, 63.0, 3.0, 30).waterTop());
	}

	@Test
	void theRiverIsNeverBuiltUp() {
		// however far below the water the ground falls away, the lip never lifts it by more than MAX_LIFT, and not at all far from the water
		for (int natural = 0; natural < 140; natural += 7) {
			for (double lateral = 4.1; lateral < 40.0; lateral += 0.9) {
				Channel.Shape shape = Channel.shape(lateral, 4.0, 140.0, 140.0, 6.0, natural);
				if (natural <= Channel.WET_FLOOR) {
					assertTrue(shape.untouched(natural), "ground under vanilla water is left alone");
				} else {
					assertTrue(shape.solidTop() <= natural + Channel.MAX_LIFT, "natural " + natural + " lateral " + lateral + " became " + shape.solidTop());
					if (lateral - 4.0 > Channel.LEVEE_REACH) {
						assertTrue(shape.solidTop() <= natural, "no lift beyond the lip's reach");
					}
				}
			}
		}
	}

	@Test
	void ourPlansCarveOnRealisticRiversEverywhere() {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic();
		RiverService service = serviceOver(world, 1.0);
		service.ensureReady(0, 0, true);
		List<RangeRivers> plans = service.finishedPlans();
		assertFalse(plans.isEmpty());
		for (RangeRivers plan : plans) {
			for (River river : plan.rivers()) {
				River.Node node = river.nodes().get(river.nodes().size() / 2);
				Grid grid = carved(service, world, (int) node.x(), (int) node.z(), 3);
				int changed = 0;
				for (int i = 0; i < grid.width; i++) {
					for (int j = 0; j < grid.depth; j++) {
						changed += grid.solidTop(grid.x0 + i, grid.z0 + j) != grid.natural[i * grid.depth + j] ? 1 : 0;
					}
				}
				assertTrue(changed > 0, river.id() + " should reshape the ground it crosses");
			}
		}
	}
}
