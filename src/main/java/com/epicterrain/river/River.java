package com.epicterrain.river;

import java.util.List;

/**
 * A planned river: a polyline from its source down to the water it ends in, with the water level, width and depth at every node.
 *
 * <p>Nothing here changes the world; it is the plan that terrain carving and water placement will follow. The water level
 * only ever falls downstream and never goes below sea level. Steep stretches are recorded as {@link Fall falls}.
 *
 * @param id     stable name, e.g. {@code 3,-2#1/t0} (range, then tributary index) or {@code 3,-2#1/g} (the range's grand river)
 * @param range  id of the range the river rises on
 * @param joins  id of the river this one ends in when {@code ending} is {@link Ending#RIVER}, otherwise empty
 * @param length length along the river in blocks
 */
public record River(String id, Kind kind, String range, List<Node> nodes, List<Fall> falls, Ending ending, String joins, double length) {
	public static final double SEA_LEVEL = 63.0;

	public enum Kind {
		/** Rises on a range flank and ends in the first water it meets. */
		TRIBUTARY,
		/** Rare, long river that runs from a range to a sizeable sea. */
		GRAND
	}

	public enum Ending {
		/** Ends in vanilla water: a vanilla river, lake or ocean. */
		VANILLA_WATER,
		/** A grand river that reached its target sea. */
		SEA,
		/** Ends in another planned river. */
		RIVER
	}

	/**
	 * @param water  surface level of the water at this node (never below 63, never rising downstream)
	 * @param width  width of the water surface in blocks
	 * @param depth  depth of the channel below the water surface in blocks
	 */
	public record Node(double x, double z, double travelled, double ground, double water, double width, double depth) {
		/** How far the ground stands above the water here, i.e. how deep a gorge the river must cut. */
		public double cut() {
			return Math.max(0.0, this.ground - this.water);
		}
	}

	/**
	 * A steep reach where the water drops fast: the place for a waterfall.
	 *
	 * @param first node index at the top of the drop
	 * @param last  node index at the bottom
	 * @param x     where the drop starts (the lip)
	 * @param drop  total fall of the water in blocks
	 * @param run   horizontal distance the drop covers in blocks
	 * @param width width of the river at the lip
	 */
	public record Fall(int first, int last, double x, double z, double drop, double run, double width, Style style) {
		public enum Style {
			/** Tall and slender, like Angel Falls. */
			PLUNGE,
			/** A stepped tumble of moderate width. */
			CASCADE,
			/** Very wide, like the falls in The Emperor's New Groove. */
			CURTAIN
		}
	}

	public Node source() {
		return this.nodes.get(0);
	}

	public Node mouth() {
		return this.nodes.get(this.nodes.size() - 1);
	}
}
