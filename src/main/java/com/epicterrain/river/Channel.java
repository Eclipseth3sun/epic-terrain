package com.epicterrain.river;

/**
 * The shape a river gives the ground it crosses, as pure functions of where a column lies relative to the river.
 *
 * <p>Across the river, from the middle out:
 * <ul>
 *   <li><b>channel</b> (up to half the width from the line): a parabolic bed, deepest in the middle, with water up to the river's level
 *       (except where the ground has dropped more than {@link #CLIFF_GAP} blocks below the bed, which is left alone);</li>
 *   <li><b>bank</b> (beyond that): the ground may stand no higher than a wall rising from the water's edge at {@link #WALL_SLOPE}, so a
 *       river through a hill cuts a V-shaped gorge. The river is cut into the ground, never built up: the planner sets its water low
 *       enough for the banks it runs between, and only a small lip, at most {@link #MAX_LIFT} blocks high and {@link #LEVEE_REACH}
 *       blocks wide, is raised where the generated ground comes out a little lower than planned;</li>
 *   <li>beyond the river's {@code radius} nothing is touched at all.</li>
 * </ul>
 * Ground that is already under vanilla water (its top solid block at 61 or lower) is never raised: the river simply runs through it.
 */
public final class Channel {
	/** Gorge walls rise this many blocks per block outward from the water's edge. */
	public static final double WALL_SLOPE = 0.8;
	/** The lip that tops up a bank falls away this fast, and no further than {@link #LEVEE_REACH}. */
	public static final double LEVEE_SLOPE = 0.35;
	public static final double LEVEE_REACH = 3.0;
	/** The lip lifts the generated ground by at most this much: it patches planning error, it never builds a bank. */
	public static final int MAX_LIFT = 2;
	/** The lip stands level with the river's water this far (blocks) upstream, so it also holds water back on a falling reach. */
	public static final double RIM_LOOKBACK = 4.0;
	public static final double MIN_HALF_WIDTH = 1.5;
	/** The most a river reshapes the ground, from its line; keeps the lookup grid small. */
	public static final double MAX_RADIUS = 250.0;
	/** The highest top solid block that still has water over it at sea level. */
	public static final int WET_FLOOR = 61;
	/** The top block of the sea. */
	public static final int SEA_TOP = 62;
	/** Ground this far below the planned bed is a cliff: the river does not stand over it. */
	public static final int CLIFF_GAP = 3;
	public static final int NONE = Integer.MIN_VALUE;

	private Channel() {
	}

	public static double halfWidth(River.Node node) {
		return Math.max(MIN_HALF_WIDTH, node.width() / 2.0);
	}

	/** How far from the river's line it may reshape the ground: its gorge (from the planned cut) plus the rim. */
	public static double radius(River.Node node) {
		double gorge = Math.max(node.cut() + 2.0, 4.0) / WALL_SLOPE;
		return Math.min(MAX_RADIUS, halfWidth(node) + gorge + LEVEE_REACH);
	}

	/**
	 * What a column becomes.
	 *
	 * @param solidTop y of the highest solid block the column should have
	 * @param waterTop y of the highest water block, or {@link #NONE}; water fills from the solid top up to here
	 * @param inChannel the column lies in the river's bed
	 */
	public record Shape(int solidTop, int waterTop, boolean inChannel) {
		/** Is the column left exactly as it was? */
		public boolean untouched(int naturalFloor) {
			return this.solidTop == naturalFloor && this.waterTop == NONE;
		}
	}

	/** As {@link #shape(double, double, double, double, double, int)}, for a river whose water level is the same upstream. */
	public static Shape shape(double lateral, double halfWidth, double water, double depth, int naturalFloor) {
		return shape(lateral, halfWidth, water, water, depth, naturalFloor);
	}

	/**
	 * @param lateral    distance from the river's line to the column
	 * @param halfWidth  half the water's width there
	 * @param water      the river's water level there (the top face of its water)
	 * @param rimWater   the river's water level {@link #RIM_LOOKBACK} blocks upstream: the bank holds back at least this much
	 * @param depth      depth of the bed below the water in the middle
	 * @param naturalFloor y of the column's top solid block as generated
	 */
	public static Shape shape(double lateral, double halfWidth, double water, double rimWater, double depth, int naturalFloor) {
		int waterTop = (int) Math.floor(water) - 1;
		if (lateral <= halfWidth) {
			double across = lateral / halfWidth;
			int bed = (int) Math.floor(water - depth * (1.0 - across * across));
			if (waterTop > SEA_TOP && naturalFloor < bed - CLIFF_GAP) {
				// The ground has dropped away under the river (a cliff between two of its nodes): water here would stand as a wall, with
				// no way to fall, so the column is left as it is. The river stops at the lip and carries on at the foot; a waterfall
				// belongs in that gap.
				return new Shape(naturalFloor, NONE, false);
			}
			// the river never raises the floor of water that is already deeper than its own bed
			return new Shape(Math.min(naturalFloor, bed), waterTop, true);
		}
		if (naturalFloor <= WET_FLOOR) {
			return new Shape(naturalFloor, NONE, false); // already under vanilla water
		}
		double out = lateral - halfWidth;
		int cap = (int) Math.floor(water + 1.0 + WALL_SLOPE * out);
		int rim = out <= LEVEE_REACH ? (int) Math.floor(Math.max(water, rimWater) - LEVEE_SLOPE * out) : Integer.MIN_VALUE;
		int lip = Math.min(rim, naturalFloor + MAX_LIFT);
		return new Shape(Math.max(lip, Math.min(naturalFloor, cap)), NONE, false);
	}
}
