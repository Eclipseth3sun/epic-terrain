package com.epicterrain.earth;

/**
 * The rolling land of the lowland: hills that come and go by region, so that one does not walk for miles over ground that changes by less than a few blocks.
 *
 * <p>The simulation makes broad plains with relief only where ranges stand, and in a world of 70% lowland that left whole biomes, one after the other, on ground as flat as
 * a table (measured against vanilla: its plains, forests, taiga and jungle have a height range of some 45 to 50 blocks within 512 blocks, an Earth world's 8 to 15, and one
 * could walk 5 km before the ground had changed by 20 blocks, against vanilla's 0.8). This lays the missing undulation over the lowland, by the character of the region:
 * <ul>
 *   <li>the <b>character</b> of a region, a noise some 3.6 km across mapped to 0 (flat) through 1 (hilly), so that flat country, rolling country and hill country follow one another
 *       over kilometres; it is also what the biome mapping reads, so that hilly country is where the table's hill biomes (and, in the hot, badlands) are;</li>
 *   <li>the <b>rain</b>: dry land is flatter than wet, and the driest flat land has low dunes;</li>
 *   <li>the <b>mesa</b>: dry hilly country is terraced, its hills flat-topped with steep sides;</li>
 *   <li>and no hills where there are already mountains, none at the shore, and none beside a river (the ground is level in a river's valley, so the hills part round it).</li>
 * </ul>
 * Pure, and a function of position and the simulation only.
 */
final class Hills {
	/** The height, in blocks, a hill of average hilly country stands above the ground between the hills, at the default dial. */
	static final double AMPLITUDE = 58.0;
	/** The size of the biggest hills, in blocks across (the others are half and a quarter of it), and how far their shape reaches (of the noise, which is 0.5 at the most of the time). */
	private static final double WAVE = 950.0;
	private static final double SHAPE = 3.0;
	/** The most of its height a point of land may lose to a hollow. */
	private static final double KEEP = 0.55;
	/** The height of the dunes, at most. */
	static final double DUNE_HEIGHT = 4.5;
	/** The height of one step of a mesa, which differs from one mesa country to the next so that the steps are not a regular stair. */
	static double terrace(long seed, double x, double z) {
		return 6.5 + 5.0 * (0.5 + 0.5 * Noise.fbm(seed + 611L, x / 2600.0, z / 2600.0, 1));
	}

	private Hills() {
	}

	/** 0 for flat country through 1 for hill country: a regional noise some 3.6 km across, so that each is a third or so of the land. */
	static double character(long seed, double x, double z) {
		return Noise.smooth(-0.26, 0.30, Noise.fbm(seed + 601L, x / 3600.0, z / 3600.0, 2));
	}

	/** 1 in the driest land, 0 in the wettest of a world that is mostly dry (the middle is about 500 mm a year). */
	static double aridity(double rainMm) {
		return 1.0 - Noise.smooth(250.0, 850.0, rainMm);
	}

	/**
	 * The hills of a place, in blocks above or below the simulated ground.
	 *
	 * @param baseBlocks   the simulated ground, blocks above the sea
	 * @param reliefMetres how far the ground stands above the lowland round it
	 * @param dial         the {@code hills} dial: 0 is none, 1 the default
	 */
	static double offset(long seed, double x, double z, double baseBlocks, double reliefMetres, double rainMm, double character, double dial) {
		double low = Noise.smooth(2.0, 30.0, baseBlocks);
		double mountain = 1.0 - Noise.smooth(120.0, 450.0, reliefMetres);
		double amplitude = AMPLITUDE * dial * low * mountain;
		if (amplitude <= 0.0) {
			return 0.0;
		}
		double arid = aridity(rainMm);
		amplitude *= (0.2 + 0.8 * character) * (0.6 + 0.4 * (1.0 - arid));
		// hills of three sizes, from 950 to 240 blocks across
		double shape = SHAPE * Noise.fbm(seed + 603L, x / WAVE, z / WAVE, 3);
		double h = amplitude * shape;
		// dry hilly country is mesa country: the hills are flat-topped steps
		double mesa = Noise.smooth(0.5, 0.9, character) * Noise.smooth(0.35, 0.85, arid);
		if (mesa > 0.0) {
			double step = terrace(seed, x, z);
			double u = (h + amplitude) / step;
			double f = Math.floor(u);
			double stepped = step * (f + Noise.smooth(0.65, 1.0, u - f)) - amplitude;
			h += (stepped - h) * mesa;
		}
		return Math.max(h, -KEEP * baseBlocks);
	}

	/** How much of a place is dunes, 0 to 1: dry, flat, inland lowland. */
	static double dunes(double baseBlocks, double reliefMetres, double rainMm, double character, double dial) {
		if (dial <= 0.0) {
			return 0.0;
		}
		return Noise.smooth(0.55, 0.95, aridity(rainMm)) * (1.0 - Noise.smooth(0.25, 0.6, character)) * Noise.smooth(8.0, 25.0, baseBlocks) * (1.0 - Noise.smooth(60.0, 200.0, reliefMetres));
	}

	/** The shape of the dunes at a block: long low ridges, about 100 blocks apart and 300 along, whose crests lie across a direction that changes from region to region. */
	static double duneRidge(long seed, double x, double z) {
		double angle = 0.9 + 1.4 * Noise.fbm(seed + 609L, x / 9000.0, z / 9000.0, 1);
		double c = StrictMath.cos(angle);
		double s = StrictMath.sin(angle);
		double along = x * c + z * s;
		double across = -x * s + z * c;
		double n = Noise.fbm(seed + 607L, across / 85.0, along / 300.0, 2);
		return 1.0 - 2.0 * Math.abs(n) * 1.4;
	}
}
