package com.epicterrain.river;

/**
 * Tunable numbers for river planning. These are plain Java defaults for now; once rivers reach the generator they will move to
 * the worldgen script with the other tunables.
 *
 * @param grandChance        chance that a range has a grand river at all
 * @param seaMinKm2          smallest ocean body a grand river will run to, in square kilometres
 * @param grandReach         furthest a grand river will travel from its range to find that sea, in blocks
 * @param sourceFractionMin  sources sit where the range has risen to this fraction of its crest height or lower...
 * @param sourceFractionMax  ...picked between these two fractions
 * @param minCrestUplift     ranges (or stretches of range) lower than this get no sources
 * @param minSourceGround    sources must stand at least this high
 * @param minSourceSpacing   sources keep at least this far apart, in blocks
 * @param sourcesPerKm       tributary sources per kilometre of spine
 * @param digCharge          how much the router dislikes digging: extra cost per block of ground above the water
 * @param nodeSpacing        distance between nodes of the planned polyline, in blocks
 * @param fallMinDrop        smallest total drop that counts as a waterfall
 * @param minWaterKm2        a tributary only ends in vanilla water if that water body is at least this big, so it does not stop at
 *                           the first puddle; smaller ponds on the way are simply crossed
 * @param maxStray           no river strays further than this from its source, in blocks as the crow flies: tributaries, and (multiplied
 *                           by 1.4) the route of a grand river. This is what lets a chunk know exactly which ranges' plans it depends on.
 * @param maxCut             a river that would have to be dug deeper than this below the ground, anywhere, is dropped (it rose in a
 *                           basin ringed by ridges, say) rather than carved as a canyon
 */
public record RiverConfig(
	double grandChance, double seaMinKm2, double grandReach,
	double sourceFractionMin, double sourceFractionMax, double minCrestUplift, double minSourceGround, double minSourceSpacing,
	double sourcesPerKm, int minSources, int maxSources,
	double digCharge, double nodeSpacing, double fallMinDrop, double minWaterKm2, double maxCut, double maxStray,
	Profile tributary, Profile grand
) {
	public static final RiverConfig DEFAULT = new RiverConfig(
		0.05, 10.0, 20_000.0,
		0.55, 0.85, 80.0, 110.0, 500.0,
		0.6, 2, 6,
		0.2, 32.0, 12.0, 1.0, 80.0, 6000.0,
		Profile.TRIBUTARY, Profile.GRAND);

	public RiverConfig withGrandChance(double chance) {
		return new RiverConfig(chance, this.seaMinKm2, this.grandReach, this.sourceFractionMin, this.sourceFractionMax, this.minCrestUplift,
			this.minSourceGround, this.minSourceSpacing, this.sourcesPerKm, this.minSources, this.maxSources, this.digCharge,
			this.nodeSpacing, this.fallMinDrop, this.minWaterKm2, this.maxCut, this.maxStray, this.tributary, this.grand);
	}

	public RiverConfig withSeaMinKm2(double km2) {
		return new RiverConfig(this.grandChance, km2, this.grandReach, this.sourceFractionMin, this.sourceFractionMax, this.minCrestUplift,
			this.minSourceGround, this.minSourceSpacing, this.sourcesPerKm, this.minSources, this.maxSources, this.digCharge,
			this.nodeSpacing, this.fallMinDrop, this.minWaterKm2, this.maxCut, this.maxStray, this.tributary, this.grand);
	}

	public RiverConfig withGrandReach(double blocks) {
		return new RiverConfig(this.grandChance, this.seaMinKm2, blocks, this.sourceFractionMin, this.sourceFractionMax, this.minCrestUplift,
			this.minSourceGround, this.minSourceSpacing, this.sourcesPerKm, this.minSources, this.maxSources, this.digCharge,
			this.nodeSpacing, this.fallMinDrop, this.minWaterKm2, this.maxCut, this.maxStray, this.tributary, this.grand);
	}

	public RiverConfig withDigCharge(double charge) {
		return new RiverConfig(this.grandChance, this.seaMinKm2, this.grandReach, this.sourceFractionMin, this.sourceFractionMax, this.minCrestUplift,
			this.minSourceGround, this.minSourceSpacing, this.sourcesPerKm, this.minSources, this.maxSources, charge,
			this.nodeSpacing, this.fallMinDrop, this.minWaterKm2, this.maxCut, this.maxStray, this.tributary, this.grand);
	}

	public RiverConfig withMaxCut(double blocks) {
		return new RiverConfig(this.grandChance, this.seaMinKm2, this.grandReach, this.sourceFractionMin, this.sourceFractionMax, this.minCrestUplift,
			this.minSourceGround, this.minSourceSpacing, this.sourcesPerKm, this.minSources, this.maxSources, this.digCharge,
			this.nodeSpacing, this.fallMinDrop, this.minWaterKm2, blocks, this.maxStray, this.tributary, this.grand);
	}

	public RiverConfig withMinWaterKm2(double km2) {
		return new RiverConfig(this.grandChance, this.seaMinKm2, this.grandReach, this.sourceFractionMin, this.sourceFractionMax, this.minCrestUplift,
			this.minSourceGround, this.minSourceSpacing, this.sourcesPerKm, this.minSources, this.maxSources, this.digCharge,
			this.nodeSpacing, this.fallMinDrop, km2, this.maxCut, this.maxStray, this.tributary, this.grand);
	}

	/**
	 * How width and depth grow downstream. Width runs from {@code sourceWidth} to a mouth width that grows with the river's length;
	 * depth reaches most of its final value about halfway, so the lower river is wider rather than deeper. The last stretch flares.
	 */
	public record Profile(
		double sourceWidth, double mouthBase, double mouthPerKm, double mouthMax,
		double sourceDepth, double depthBase, double depthPerKm, double depthMax,
		double flareFactor, double flareFraction, double flareMaxLength
	) {
		public static final Profile TRIBUTARY = new Profile(2, 8, 6, 28, 1, 3, 1.2, 8, 2.0, 0.2, 300);
		public static final Profile GRAND = new Profile(4, 70, 4, 140, 2, 9, 0.4, 16, 2.0, 0.15, 800);

		public double mouthWidth(double lengthKm) {
			return Math.min(this.mouthMax, this.mouthBase + this.mouthPerKm * lengthKm);
		}

		public double finalDepth(double lengthKm) {
			return Math.min(this.depthMax, this.depthBase + this.depthPerKm * lengthKm);
		}

		/** Width at {@code travelled} blocks along a river of {@code length} blocks; the mouth flares only if {@code flares}. */
		public double widthAt(double travelled, double length, boolean flares) {
			double f = length <= 0 ? 1.0 : Math.min(1.0, travelled / length);
			double width = this.sourceWidth + (this.mouthWidth(length / 1000.0) - this.sourceWidth) * Math.pow(f, 0.85);
			if (flares) {
				width *= 1.0 + (this.flareFactor - 1.0) * this.flare(travelled, length);
			}
			return width;
		}

		/** Depth at {@code travelled} blocks along a river of {@code length} blocks. */
		public double depthAt(double travelled, double length, boolean flares) {
			double f = length <= 0 ? 1.0 : Math.min(1.0, travelled / length);
			double depth = this.sourceDepth + (this.finalDepth(length / 1000.0) - this.sourceDepth) * (1.0 - (1.0 - f) * (1.0 - f));
			if (flares) {
				depth *= 1.0 + 0.1 * this.flare(travelled, length);
			}
			return depth;
		}

		/** 0 upstream of the flare and rising smoothly to 1 at the mouth. */
		private double flare(double travelled, double length) {
			double flareLength = Math.min(this.flareMaxLength, this.flareFraction * length);
			if (flareLength <= 0) {
				return 0.0;
			}
			double t = Math.max(0.0, Math.min(1.0, 1.0 - (length - travelled) / flareLength));
			return t * t * (3.0 - 2.0 * t);
		}
	}
}
