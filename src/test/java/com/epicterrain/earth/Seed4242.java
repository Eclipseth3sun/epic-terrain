package com.epicterrain.earth;

import java.util.List;

import com.epicterrain.earth.Rivers.River;

/** The default window of seed 4242, simulated once for every test that wants a real network of lakes, mountains and rivers. */
final class Seed4242 {
	static final Continent CONTINENT = ContinentSimulator.run(EarthConfig.defaults(4242L));
	static final Detail DETAIL = new Detail(CONTINENT, Detail.Dials.defaults());
	static final List<River> RIVERS = Rivers.build(CONTINENT, DETAIL::smoothBlocks, Rivers.Dials.defaults());
	static final RiverValley VALLEY = new RiverValley(CONTINENT, RIVERS, DETAIL, RiverValley.Dials.defaults());

	private Seed4242() {
	}
}
