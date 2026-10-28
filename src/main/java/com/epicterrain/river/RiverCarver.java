package com.epicterrain.river;

import java.util.Arrays;

/**
 * Cuts the planned rivers into the ground of one chunk, column by column.
 *
 * <p>Each column is decided on its own from the river plan and the column's generated top block, so neighbouring chunks agree at their
 * borders without ever looking at each other. The world is reached through {@link Columns}, so the same code is tested on plain arrays
 * and run on real chunks.
 *
 * <p>A gorge can cut into the caves under it. The river's water must not end up hanging over those, so the bed is given a floor of stone
 * ({@link #BED_THICKNESS} blocks) and any cave opening that faces the water is plugged ({@link #sealCaves}).
 */
public final class RiverCarver {
	public enum Material {
		AIR, WATER, GRAVEL, STONE
	}

	/** The part of a world the carver needs. */
	public interface Columns {
		/** y of the highest solid block of the column, ignoring water. */
		int floor(int x, int z);

		void set(int x, int y, int z, Material material);

		/**
		 * Builds the column up from its floor to {@code top} out of its own ground: the surface block ends up on top, and what lay under
		 * the surface fills below it, so the lifted ground looks like the ground around it.
		 */
		void raise(int x, int z, int floor, int top);

		/** Is this a solid block, one that holds water up and back? (Air, water and plants are not.) */
		boolean solid(int x, int y, int z);

		/** Is this block plain open air? */
		boolean air(int x, int y, int z);
	}

	/** The bed of a river stands on this many solid blocks (the gravel counts as one), so it never floats over a cave. */
	public static final int BED_THICKNESS = 4;
	/** Cave air beside the water is plugged if there is solid ground this far above it (or less): a cave, not the open sky. */
	public static final int CAVE_ROOF = 24;
	private static final int NONE = Channel.NONE;

	private RiverCarver() {
	}

	/** Carves chunk ({@code chunkX}, {@code chunkZ}); returns how many columns were changed. */
	public static int carve(RiverService service, int chunkX, int chunkZ, Columns columns) {
		int changed = 0;
		// for each column of the chunk, the y of its bed and of the top of its water, where it holds river water
		int[] bed = new int[256];
		int[] water = new int[256];
		Arrays.fill(water, NONE);
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int x = chunkX * 16 + dx;
				int z = chunkZ * 16 + dz;
				RiverService.ColumnRiver river = service.at(x + 0.5, z + 0.5);
				if (river != null) {
					Channel.Shape shape = reshape(river, x, z, columns);
					if (shape != null) {
						changed++;
						if (shape.inChannel() && shape.waterTop() != NONE) {
							bed[dx * 16 + dz] = shape.solidTop();
							water[dx * 16 + dz] = shape.waterTop();
						}
					}
				}
			}
		}
		sealCaves(chunkX, chunkZ, bed, water, columns);
		return changed;
	}

	/** Reshapes one column; returns 1 if it changed, else 0. */
	public static int carveColumn(RiverService.ColumnRiver river, int x, int z, Columns columns) {
		return reshape(river, x, z, columns) == null ? 0 : 1;
	}

	/** Reshapes one column; returns the shape it was given, or null if it was left as it was. */
	private static Channel.Shape reshape(RiverService.ColumnRiver river, int x, int z, Columns columns) {
		int floor = columns.floor(x, z);
		Channel.Shape shape = Channel.shape(river.lateral(), river.halfWidth(), river.water(), river.rimWater(), river.depth(), floor);
		if (shape.untouched(floor)) {
			return null;
		}
		int top = shape.solidTop();
		int waterTop = shape.waterTop();
		if (top > floor) {
			columns.raise(x, z, floor, top); // a small lip that tops up a bank the generated ground left a little low
			return shape;
		}
		// cut down to the new top and fill the channel with water
		int highest = Math.max(floor, waterTop);
		for (int y = top + 1; y <= highest; y++) {
			columns.set(x, y, z, waterTop != NONE && y <= waterTop ? Material.WATER : Material.AIR);
		}
		if (shape.inChannel() && top < floor) {
			columns.set(x, top, z, Material.GRAVEL);
		}
		if (shape.inChannel() && waterTop > Channel.SEA_TOP) {
			// a river up in the hills rests on rock: where the ground under the bed is hollow (a cave the gorge has cut into), fill it
			for (int y = top - 1; y > top - BED_THICKNESS; y--) {
				if (!columns.solid(x, y, z)) {
					columns.set(x, y, z, Material.STONE);
				}
			}
		}
		return shape;
	}

	/**
	 * Plugs the cave air that touches the river's water from the side: a gorge that cuts a cave open beside the channel would otherwise
	 * leave a face of water with nothing holding it. Only air under a roof is plugged; air open to the sky beside the water is a lower
	 * bank or a cliff, which is for the planner and for waterfalls to deal with. Reaches into the chunks next door, which is allowed
	 * and harmless: it only ever turns air that touches water into stone.
	 */
	private static void sealCaves(int chunkX, int chunkZ, int[] bed, int[] water, Columns columns) {
		int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int waterTop = water[dx * 16 + dz];
				if (waterTop == NONE) {
					continue;
				}
				for (int[] side : sides) {
					int sx = dx + side[0];
					int sz = dz + side[1];
					if (sx >= 0 && sx < 16 && sz >= 0 && sz < 16 && water[sx * 16 + sz] != NONE) {
						continue; // river water on this side too
					}
					int x = chunkX * 16 + sx;
					int z = chunkZ * 16 + sz;
					for (int y = bed[dx * 16 + dz] + 1; y <= waterTop; y++) {
						if (columns.air(x, y, z) && roofed(columns, x, y, z)) {
							columns.set(x, y, z, Material.STONE);
						}
					}
				}
			}
		}
	}

	private static boolean roofed(Columns columns, int x, int y, int z) {
		for (int above = y + 1; above <= y + CAVE_ROOF; above++) {
			if (columns.solid(x, above, z)) {
				return true;
			}
		}
		return false;
	}
}
