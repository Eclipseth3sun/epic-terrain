package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.epicterrain.worldgen.RiverField;

import org.junit.jupiter.api.Test;

/** Carving real planned rivers into columns taken from real terrain. */
class RiverCarverWorldTest {
	private static final int MIN_Y = -64;
	private static final byte AIR = 0;
	private static final byte WATER = 2;

	@Test
	void realRiversHoldTheirWaterInAndLeaveTheRestAlone() {
		RiverTestWorld world = RiverTestWorld.create(4242L, 60);
		RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
		service.ensureReady(0, 0, true);
		List<River> rivers = new ArrayList<>(service.finishedPlans().stream().flatMap(p -> p.rivers().stream()).toList());
		rivers.sort(Comparator.comparing(River::id));
		assertTrue(rivers.size() > 20, "need rivers: " + rivers.size());

		List<String> spills = new ArrayList<>();
		int checked = 0;
		int waterColumns = 0;
		int changedColumns = 0;
		// a spread of rivers: tributaries high up and low, and any grand river there is
		for (int i = 0; i < rivers.size() && checked < 6; i += Math.max(1, rivers.size() / 6)) {
			River river = rivers.get(i);
			River.Node node = river.nodes().get(river.nodes().size() / 2);
			int chunks = 2;
			int cx = Math.floorDiv((int) node.x(), 16) - chunks;
			int cz = Math.floorDiv((int) node.z(), 16) - chunks;
			int size = (2 * chunks + 1) * 16;
			RiverCarverTest.Grid grid = new RiverCarverTest.Grid(cx * 16, cz * 16, size, size, (x, z) -> (int) Math.floor(world.terrain.ground(x, z)));
			for (int a = 0; a <= 2 * chunks; a++) {
				for (int b = 0; b <= 2 * chunks; b++) {
					RiverCarver.carve(service, cx + a, cz + b, grid);
				}
			}
			for (int x = grid.x0 + 2; x < grid.x0 + grid.width - 2; x++) {
				for (int z = grid.z0 + 2; z < grid.z0 + grid.depth - 2; z++) {
					RiverService.ColumnRiver c = service.at(x + 0.5, z + 0.5);
					int natural = grid.natural[(x - grid.x0) * grid.depth + (z - grid.z0)];
					int solidTop = grid.solidTop(x, z);
					if (c == null) {
						assertEquals(natural, solidTop, river.id() + ": a column no river reaches must be untouched at " + x + "," + z);
						continue;
					}
					if (solidTop != natural) {
						changedColumns++;
					}
					for (int y = MIN_Y + 1; y < 320; y++) {
						if (grid.at(x, y, z) != WATER) {
							continue;
						}
						waterColumns++;
						assertTrue(grid.at(x, y - 1, z) != AIR, river.id() + ": water floats over air at " + x + "," + y + "," + z);
						for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
							if (grid.at(x + d[0], y, z + d[1]) != AIR) {
								continue;
							}
							RiverService.ColumnRiver beside = service.at(x + d[0] + 0.5, z + d[1] + 0.5);
							if (beside != null && beside.lateral() <= beside.halfWidth()) {
								continue; // the river stepping down its own bed
							}
							int below = y - 1;
							while (grid.at(x + d[0], below, z + d[1]) == AIR && below > MIN_Y + 1) {
								below--;
							}
							if (grid.at(x + d[0], below, z + d[1]) != WATER) {
								spills.add(river.id() + " at " + x + "," + y + "," + z + " toward " + d[0] + "," + d[1]);
							}
						}
					}
				}
			}
			checked++;
		}
		System.out.printf("[rivers] %d real rivers carved into real terrain: %d columns reshaped, %d water columns, %d spills%n", checked, changedColumns, waterColumns, spills.size());
		System.out.printf("[rivers] %d open water faces in %d water columns: %s%n", spills.size(), waterColumns, spills.subList(0, Math.min(6, spills.size())));
		// A river is cut into the ground and its water set under its banks, so open water is rare: it is a river that runs along a sheer
		// drop at its side, where nothing short of a wall would hold it (the waterfall phase deals with those reaches).
		assertTrue(spills.size() <= waterColumns / 500, "water spills onto the ground: " + spills.size() + " of " + waterColumns + ", e.g. " + spills.subList(0, Math.min(8, spills.size())));
		assertTrue(checked >= 5);
		assertTrue(changedColumns > 300, "the rivers should reshape real ground: " + changedColumns);
		assertTrue(waterColumns > 100, "water columns: " + waterColumns);
	}
}
