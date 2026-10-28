package com.epicterrain.river;

import java.util.List;

import com.epicterrain.worldgen.RangeShape;

/**
 * Everything planned for one range.
 *
 * @param grandRolled  the range won the (small) chance of a grand river
 * @param sourcesTried how many tributary sources were tried
 * @param sourcesFailed sources that found no route or a route too short to keep
 */
public record RangeRivers(RangeShape range, List<River> rivers, boolean grandRolled, int sourcesTried, int sourcesFailed) {
	public boolean hasGrand() {
		return this.rivers.stream().anyMatch(r -> r.kind() == River.Kind.GRAND);
	}
}
