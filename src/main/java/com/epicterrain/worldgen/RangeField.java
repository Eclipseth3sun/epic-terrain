package com.epicterrain.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.epicterrain.EpicTerrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;

/**
 * Where epic mountain ranges are, as real objects rather than noise ridges.
 *
 * <p>The world is cut into square cells. Each cell has a few range "slots"; a slot holds a range with probability
 * {@code occupancy} (the world-creation slider), and only if the cell's centre is on land. A range is a line, an arc, or a
 * bowl (an arc that nearly closes), either a <em>foothill</em> range (broad rolling skirt) or a <em>wall</em> range
 * (steep sides, almost no skirt). Everything is derived from a hash of the world seed and the cell, so it is deterministic
 * and needs no state.
 *
 * <p>Every range has a hard maximum width, so away from all ranges the output is exactly zero and the world is exactly
 * vanilla: there is no faint noise leaking out of a range.
 *
 * <p>The same function is registered three ways ({@link Output}); each is 2D and returns the same value at every y.
 */
public record RangeField(
	Output output,
	DensityFunction occupancy,
	DensityFunction land,
	DensityFunction warpX,
	DensityFunction warpZ,
	DensityFunction peakNoise,
	DensityFunction detailNoise,
	DensityFunction hillNoise,
	Layout layout,
	Profile profile
) implements DensityFunction {
	public static final MapCodec<RangeField> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Output.CODEC.fieldOf("output").forGetter(RangeField::output),
			DensityFunction.CODEC.fieldOf("occupancy").forGetter(RangeField::occupancy),
			DensityFunction.CODEC.fieldOf("land").forGetter(RangeField::land),
			DensityFunction.CODEC.fieldOf("warp_x").forGetter(RangeField::warpX),
			DensityFunction.CODEC.fieldOf("warp_z").forGetter(RangeField::warpZ),
			DensityFunction.CODEC.fieldOf("peak_noise").forGetter(RangeField::peakNoise),
			DensityFunction.CODEC.fieldOf("detail_noise").forGetter(RangeField::detailNoise),
			DensityFunction.CODEC.fieldOf("hill_noise").forGetter(RangeField::hillNoise),
			Layout.CODEC.optionalFieldOf("layout", Layout.DEFAULT).forGetter(RangeField::layout),
			Profile.CODEC.optionalFieldOf("profile", Profile.DEFAULT).forGetter(RangeField::profile)
		).apply(i, RangeField::new));

	private static final double TWO_PI = Math.PI * 2.0;
	private static final long GOLDEN = 0x9E3779B97F4A7C15L;

	public enum Output {
		/** Height of the range above the vanilla base, in blocks (0 outside every range). */
		UPLIFT,
		/** 0 for a foothill range, 1 for a wall range (0 outside every range). */
		KIND,
		/** Distance to the nearest range's spine in blocks; negative outside every range. For debugging. */
		DISTANCE;

		static final Codec<Output> CODEC = Codec.STRING.comapFlatMap(name -> {
			try {
				return DataResult.success(valueOf(name.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				return DataResult.error(() -> "unknown range_field output: " + name);
			}
		}, output -> output.name().toLowerCase(Locale.ROOT));
	}

	public record FloatRange(float min, float max) {
		static final Codec<FloatRange> CODEC = Codec.FLOAT.listOf(2, 2).xmap(l -> new FloatRange(l.get(0), l.get(1)), r -> List.of(r.min, r.max));

		float lerp(double t) {
			return (float) (this.min + (this.max - this.min) * t);
		}
	}

	/** How ranges are placed and shaped in plan view. */
	public record Layout(
		int cellSize, int slots, float lineShare, float arcShare, float wallShare,
		FloatRange lineLength, FloatRange arcRadius, FloatRange arcSweepDegrees, FloatRange bowlSweepDegrees, float taper
	) {
		static final Layout DEFAULT = new Layout(6144, 4, 0.35f, 0.45f, 0.45f,
			new FloatRange(3500, 7500), new FloatRange(1600, 2800), new FloatRange(70, 200), new FloatRange(230, 330), 0.14f);

		static final Codec<Layout> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.intRange(512, 65536).optionalFieldOf("cell_size", DEFAULT.cellSize).forGetter(Layout::cellSize),
				Codec.intRange(1, 4).optionalFieldOf("slots", DEFAULT.slots).forGetter(Layout::slots),
				Codec.floatRange(0, 1).optionalFieldOf("line_share", DEFAULT.lineShare).forGetter(Layout::lineShare),
				Codec.floatRange(0, 1).optionalFieldOf("arc_share", DEFAULT.arcShare).forGetter(Layout::arcShare),
				Codec.floatRange(0, 1).optionalFieldOf("wall_share", DEFAULT.wallShare).forGetter(Layout::wallShare),
				FloatRange.CODEC.optionalFieldOf("line_length", DEFAULT.lineLength).forGetter(Layout::lineLength),
				FloatRange.CODEC.optionalFieldOf("arc_radius", DEFAULT.arcRadius).forGetter(Layout::arcRadius),
				FloatRange.CODEC.optionalFieldOf("arc_sweep", DEFAULT.arcSweepDegrees).forGetter(Layout::arcSweepDegrees),
				FloatRange.CODEC.optionalFieldOf("bowl_sweep", DEFAULT.bowlSweepDegrees).forGetter(Layout::bowlSweepDegrees),
				Codec.floatRange(0.01f, 0.5f).optionalFieldOf("taper", DEFAULT.taper).forGetter(Layout::taper)
			).apply(i, Layout::new));
	}

	/** Cross-section: how tall a range is and how its height falls off with distance from the spine, in blocks. */
	public record Profile(
		float peakBase, float peakVariation, float detailFloor,
		FloatRange foothillCore, FloatRange foothillSkirt, float foothillSkirtHeight,
		FloatRange wallCore, FloatRange wallSkirt, float wallSkirtHeight,
		FloatRange foothillLand, FloatRange wallLand
	) {
		static final Profile DEFAULT = new Profile(205, 60, 0.6f,
			new FloatRange(60, 650), new FloatRange(0, 1100), 80,
			new FloatRange(150, 480), new FloatRange(0, 520), 22,
			new FloatRange(-0.55f, -0.25f), new FloatRange(-0.55f, -0.25f));

		static final Codec<Profile> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("peak_base", DEFAULT.peakBase).forGetter(Profile::peakBase),
				Codec.FLOAT.optionalFieldOf("peak_variation", DEFAULT.peakVariation).forGetter(Profile::peakVariation),
				Codec.floatRange(0, 1).optionalFieldOf("detail_floor", DEFAULT.detailFloor).forGetter(Profile::detailFloor),
				FloatRange.CODEC.optionalFieldOf("foothill_core", DEFAULT.foothillCore).forGetter(Profile::foothillCore),
				FloatRange.CODEC.optionalFieldOf("foothill_skirt", DEFAULT.foothillSkirt).forGetter(Profile::foothillSkirt),
				Codec.FLOAT.optionalFieldOf("foothill_skirt_height", DEFAULT.foothillSkirtHeight).forGetter(Profile::foothillSkirtHeight),
				FloatRange.CODEC.optionalFieldOf("wall_core", DEFAULT.wallCore).forGetter(Profile::wallCore),
				FloatRange.CODEC.optionalFieldOf("wall_skirt", DEFAULT.wallSkirt).forGetter(Profile::wallSkirt),
				Codec.FLOAT.optionalFieldOf("wall_skirt_height", DEFAULT.wallSkirtHeight).forGetter(Profile::wallSkirtHeight),
				FloatRange.CODEC.optionalFieldOf("foothill_land", DEFAULT.foothillLand).forGetter(Profile::foothillLand),
				FloatRange.CODEC.optionalFieldOf("wall_land", DEFAULT.wallLand).forGetter(Profile::wallLand)
			).apply(i, Profile::new));

		/** The furthest a range reaches from its spine: beyond this the field is exactly zero. */
		float maxReach() {
			return Math.max(this.foothillSkirt.max, this.wallSkirt.max);
		}
	}

	@Override
	public DensitySampler compileSampler(DensityFunction.CompileContext context) {
		long salt = context.createRandom(EpicTerrain.id("range_field")).nextLong();
		return new Sampler(this, salt,
			this.occupancy.compileSampler(context), this.land.compileSampler(context),
			this.warpX.compileSampler(context), this.warpZ.compileSampler(context),
			this.peakNoise.compileSampler(context), this.detailNoise.compileSampler(context), this.hillNoise.compileSampler(context));
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		DensityFunction occupancy = rule.rewrite(this.occupancy);
		DensityFunction land = rule.rewrite(this.land);
		DensityFunction warpX = rule.rewrite(this.warpX);
		DensityFunction warpZ = rule.rewrite(this.warpZ);
		DensityFunction peak = rule.rewrite(this.peakNoise);
		DensityFunction detail = rule.rewrite(this.detailNoise);
		DensityFunction hill = rule.rewrite(this.hillNoise);
		if (occupancy == this.occupancy && land == this.land && warpX == this.warpX && warpZ == this.warpZ
			&& peak == this.peakNoise && detail == this.detailNoise && hill == this.hillNoise) {
			return this;
		}
		return new RangeField(this.output, occupancy, land, warpX, warpZ, peak, detail, hill, this.layout, this.profile);
	}

	@Override
	public Interval range() {
		return switch (this.output) {
			case UPLIFT -> Interval.of(0.0f, 1000.0f);
			case KIND -> Interval.of(0.0f, 1.0f);
			case DISTANCE -> Interval.of(-1.0f, 100000.0f);
		};
	}

	@Override
	public int domainAxes() {
		return 5; // x and z only: the value never depends on y
	}

	@Override
	public MapCodec<RangeField> codec() {
		return CODEC;
	}

	/** The first range field found anywhere inside {@code root} (following references to registered functions). */
	public static Optional<RangeField> find(DensityFunction root) {
		Finder finder = new Finder();
		finder.rewrite(root);
		return Optional.ofNullable(finder.found);
	}

	/**
	 * The ranges of the world {@code state} generates, as geometry. {@code root} is any density function that contains the
	 * range field, such as the final density of the noise settings in use; the answer matches the terrain exactly because it
	 * comes from the very sampler (and so the very seed salt and occupancy) that shapes it.
	 */
	public static RangeCatalog catalog(RandomState state, DensityFunction root) {
		RangeField field = find(root).orElseThrow(() -> new IllegalArgumentException("no range_field inside " + root));
		// The compiler wraps a function that does not depend on y in a slice sampler; peel that off to reach ours.
		DensitySampler sampler = state.getSampler(field);
		while (sampler instanceof SliceFunction.YSampler slice) {
			sampler = slice.input();
		}
		if (sampler instanceof RangeCatalog catalog) {
			return catalog;
		}
		throw new IllegalStateException("a range_field compiled to " + sampler.getClass().getName() + ", which is not a range catalog");
	}

	private static final class Finder implements DfRewriteRule {
		private final Set<DensityFunction> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		private RangeField found;

		@Override
		public DensityFunction rewrite(DensityFunction function) {
			if (this.found != null || !this.seen.add(function)) {
				return function;
			}
			if (function instanceof RangeField field) {
				this.found = field;
			} else if (function instanceof DensityFunctions.HolderHolder(Holder<DensityFunction> holder)) {
				this.rewrite(holder.value());
			} else {
				function.rewriteChildren(this);
			}
			return function;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static final class Sampler implements DensitySampler, RangeCatalog {
		private final RangeField field;
		private final Layout layout;
		private final Profile profile;
		private final long salt;
		private final DensitySampler occupancy;
		private final DensitySampler land;
		private final DensitySampler warpX;
		private final DensitySampler warpZ;
		private final DensitySampler peakNoise;
		private final DensitySampler detailNoise;
		private final DensitySampler hillNoise;

		Sampler(RangeField field, long salt, DensitySampler occupancy, DensitySampler land, DensitySampler warpX, DensitySampler warpZ,
			DensitySampler peakNoise, DensitySampler detailNoise, DensitySampler hillNoise) {
			this.field = field;
			this.layout = field.layout;
			this.profile = field.profile;
			this.salt = salt;
			this.occupancy = occupancy;
			this.land = land;
			this.warpX = warpX;
			this.warpZ = warpZ;
			this.peakNoise = peakNoise;
			this.detailNoise = detailNoise;
			this.hillNoise = hillNoise;
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			int index = 0;
			for (int z = 0; z < volume.sizeZ(); z++) {
				for (int x = 0; x < volume.sizeX(); x++) {
					float value = this.sampleValue(context, volume.blockX(x), 0, volume.blockZ(z));
					for (int y = 0; y < volume.sizeY(); y++) {
						outputBuffer.set(index++, value);
					}
				}
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			float occupancy = this.occupancy.sampleValue(context, blockX, 0, blockZ);
			if (occupancy <= 0.0f) {
				return this.field.output == Output.DISTANCE ? -1.0f : 0.0f;
			}

			// Ranges live in a gently warped copy of the world so lines and arcs wobble instead of being ruler-straight.
			double qx = blockX + this.warpX.sampleValue(context, blockX, 0, blockZ);
			double qz = blockZ + this.warpZ.sampleValue(context, blockX, 0, blockZ);
			int cell = this.layout.cellSize;
			int cellX = Math.floorDiv((int) Math.floor(qx), cell);
			int cellZ = Math.floorDiv((int) Math.floor(qz), cell);

			float bestUplift = 0.0f;
			float bestKind = 0.0f;
			float bestDistance = -1.0f;
			// Noise and land are only looked up if some range is close enough to matter.
			boolean haveNoise = false;
			float peak = 0.0f;
			float detail = 0.0f;
			float hill = 0.0f;
			float ground = 0.0f;
			double reach = this.profile.maxReach();

			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int slot = 0; slot < this.layout.slots; slot++) {
						long h = mix(this.salt ^ mix((long) (cellX + dx) * GOLDEN + (long) (cellZ + dz) * 0xC2B2AE3D27D4EB4FL + (long) slot * 0x165667B19E3779F9L));
						if (unit(h) >= occupancy) {
							continue;
						}
						h = mix(h + GOLDEN);
						double centerX = (double) (cellX + dx) * cell + unit(h) * cell;
						h = mix(h + GOLDEN);
						double centerZ = (double) (cellZ + dz) * cell + unit(h) * cell;
						h = mix(h + GOLDEN);
						double shapeRoll = unit(h);
						h = mix(h + GOLDEN);
						boolean wall = unit(h) < this.layout.wallShare;
						h = mix(h + GOLDEN);
						double angle = unit(h) * TWO_PI;
						h = mix(h + GOLDEN);
						double sizeRoll = unit(h);
						h = mix(h + GOLDEN);
						double sweepRoll = unit(h);
						h = mix(h + GOLDEN);
						double heightRoll = unit(h);

						// A range only exists if its centre is on land.
						if (this.land.sampleValue(context, (int) centerX, 0, (int) centerZ) < -0.12f) {
							continue;
						}

						double d;
						double t;
						if (shapeRoll < this.layout.lineShare) {
							// Straight segment through the centre.
							double halfLength = this.layout.lineLength.lerp(sizeRoll) / 2.0;
							double ux = Math.cos(angle);
							double uz = Math.sin(angle);
							double along = (qx - centerX) * ux + (qz - centerZ) * uz;
							double clamped = Math.max(-halfLength, Math.min(halfLength, along));
							double nx = centerX + ux * clamped;
							double nz = centerZ + uz * clamped;
							d = Math.hypot(qx - nx, qz - nz);
							t = (clamped + halfLength) / (2.0 * halfLength);
						} else {
							boolean bowl = shapeRoll >= this.layout.lineShare + this.layout.arcShare;
							double radius = this.layout.arcRadius.lerp(sizeRoll);
							double sweep = Math.toRadians((bowl ? this.layout.bowlSweepDegrees : this.layout.arcSweepDegrees).lerp(sweepRoll));
							double vx = qx - centerX;
							double vz = qz - centerZ;
							double r = Math.hypot(vx, vz);
							double rel = Math.atan2(vz, vx) - angle;
							rel -= TWO_PI * Math.floor(rel / TWO_PI);
							if (rel <= sweep) {
								d = Math.abs(r - radius);
								t = rel / sweep;
							} else {
								double e0x = centerX + radius * Math.cos(angle);
								double e0z = centerZ + radius * Math.sin(angle);
								double e1x = centerX + radius * Math.cos(angle + sweep);
								double e1z = centerZ + radius * Math.sin(angle + sweep);
								double d0 = Math.hypot(qx - e0x, qz - e0z);
								double d1 = Math.hypot(qx - e1x, qz - e1z);
								d = Math.min(d0, d1);
								t = d0 <= d1 ? 0.0 : 1.0;
							}
						}
						if (d >= reach) {
							continue;
						}

						if (!haveNoise) {
							haveNoise = true;
							peak = this.profile.peakBase + this.profile.peakVariation * Math.max(-1.0f, Math.min(1.0f, 3.0f * this.peakNoise.sampleValue(context, blockX, 0, blockZ)));
							float detailValue = Math.max(0.0f, Math.min(1.0f, this.detailNoise.sampleValue(context, blockX, 0, blockZ)));
							detail = this.profile.detailFloor + (1.0f - this.profile.detailFloor) * detailValue;
							hill = Math.max(0.0f, Math.min(1.0f, this.hillNoise.sampleValue(context, blockX, 0, blockZ)));
							ground = this.land.sampleValue(context, blockX, 0, blockZ);
						}

						double uplift;
						if (wall) {
							double core = 1.0 - smoothstep(d, this.profile.wallCore);
							double skirt = 1.0 - smoothstep(d, this.profile.wallSkirt);
							uplift = peak * detail * Math.pow(core, 0.9) + this.profile.wallSkirtHeight * skirt;
							uplift *= smoothstep(ground, this.profile.wallLand);
						} else {
							double core = 1.0 - smoothstep(d, this.profile.foothillCore);
							double skirt = 1.0 - smoothstep(d, this.profile.foothillSkirt);
							uplift = peak * detail * Math.pow(core, 1.3) + this.profile.foothillSkirtHeight * Math.pow(skirt, 1.3) * hill;
							uplift *= smoothstep(ground, this.profile.foothillLand);
						}
						double taper = this.layout.taper;
						uplift *= smoothstep(t, 0.0, taper) * smoothstep(1.0 - t, 0.0, taper);
						uplift *= 0.85 + 0.15 * heightRoll;

						if (uplift > bestUplift) {
							bestUplift = (float) uplift;
							bestKind = wall ? 1.0f : 0.0f;
							bestDistance = (float) d;
						}
					}
				}
			}

			return switch (this.field.output) {
				case UPLIFT -> bestUplift;
				case KIND -> bestKind;
				case DISTANCE -> bestDistance;
			};
		}

		// ---- RangeCatalog: the same placement as sampleValue, kept apart so the hot loop above stays allocation-free --------

		@Override
		public int cellSize() {
			return this.layout.cellSize;
		}

		@Override
		public long worldKey() {
			float occupancy = this.occupancy.sampleValue(SamplerContext.EMPTY_UNCACHED, 0, 0, 0);
			return mix(this.salt ^ mix(Float.floatToIntBits(occupancy) ^ mix((long) this.layout.hashCode() * 31 + this.profile.hashCode())));
		}

		@Override
		public double[] warp(double x, double z) {
			return new double[] {
				this.warpX.sampleValue(SamplerContext.EMPTY_UNCACHED, (int) Math.floor(x), 0, (int) Math.floor(z)),
				this.warpZ.sampleValue(SamplerContext.EMPTY_UNCACHED, (int) Math.floor(x), 0, (int) Math.floor(z))
			};
		}

		@Override
		public List<RangeShape> shapesInCell(int cellX, int cellZ) {
			int cell = this.layout.cellSize;
			List<RangeShape> shapes = new ArrayList<>();
			float occupancy = this.occupancy.sampleValue(SamplerContext.EMPTY_UNCACHED, cellX * cell + cell / 2, 0, cellZ * cell + cell / 2);
			if (occupancy <= 0.0f) {
				return shapes;
			}
			for (int slot = 0; slot < this.layout.slots; slot++) {
				RangeShape shape = slotShape(this.salt, this.layout, cellX, cellZ, slot, occupancy);
				if (shape == null) {
					continue;
				}
				// A range only exists if its centre is on land.
				if (this.land.sampleValue(SamplerContext.EMPTY_UNCACHED, (int) shape.centerX(), 0, (int) shape.centerZ()) < -0.12f) {
					continue;
				}
				shapes.add(shape);
			}
			return shapes;
		}

		/** Mirrors the per-slot hash chain in {@link #sampleValue}: change one and the other must change with it (RangeCatalogTest checks). */
		private static RangeShape slotShape(long salt, Layout layout, int cellX, int cellZ, int slot, double occupancy) {
			long h = mix(salt ^ mix((long) cellX * GOLDEN + (long) cellZ * 0xC2B2AE3D27D4EB4FL + (long) slot * 0x165667B19E3779F9L));
			if (unit(h) >= occupancy) {
				return null;
			}
			int cell = layout.cellSize;
			h = mix(h + GOLDEN);
			double centerX = (double) cellX * cell + unit(h) * cell;
			h = mix(h + GOLDEN);
			double centerZ = (double) cellZ * cell + unit(h) * cell;
			h = mix(h + GOLDEN);
			double shapeRoll = unit(h);
			h = mix(h + GOLDEN);
			boolean wall = unit(h) < layout.wallShare;
			h = mix(h + GOLDEN);
			double angle = unit(h) * TWO_PI;
			h = mix(h + GOLDEN);
			double sizeRoll = unit(h);
			h = mix(h + GOLDEN);
			double sweepRoll = unit(h);
			h = mix(h + GOLDEN);
			double heightRoll = unit(h);

			if (shapeRoll < layout.lineShare) {
				return new RangeShape(cellX, cellZ, slot, RangeShape.Type.LINE, wall, centerX, centerZ, angle,
					layout.lineLength.lerp(sizeRoll), 0.0, 0.0, heightRoll, layout.taper);
			}
			boolean bowl = shapeRoll >= layout.lineShare + layout.arcShare;
			double radius = layout.arcRadius.lerp(sizeRoll);
			double sweep = Math.toRadians((bowl ? layout.bowlSweepDegrees : layout.arcSweepDegrees).lerp(sweepRoll));
			return new RangeShape(cellX, cellZ, slot, bowl ? RangeShape.Type.BOWL : RangeShape.Type.ARC, wall, centerX, centerZ, angle,
				0.0, radius, sweep, heightRoll, layout.taper);
		}

		private static double smoothstep(double x, FloatRange range) {
			return smoothstep(x, range.min, range.max);
		}

		private static double smoothstep(double x, double edge0, double edge1) {
			double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
			return t * t * (3.0 - 2.0 * t);
		}

		private static long mix(long z) {
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			return z ^ (z >>> 31);
		}

		/** The top 53 bits of a hash as a number in [0, 1). */
		private static double unit(long hash) {
			return (hash >>> 11) * 0x1.0p-53;
		}
	}
}
