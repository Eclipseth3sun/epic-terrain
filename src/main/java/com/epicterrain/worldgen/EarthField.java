package com.epicterrain.worldgen;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import com.epicterrain.EpicTerrain;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;
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
 * The Earth world type's ground as a density function: every output reads the same {@link EarthWorld}.
 *
 * <ul>
 *   <li>{@link Output#DENSITY}: the terrain, {@code (63 + ground) - y}, positive (solid) below the ground and negative above it, one unit of density per block;</li>
 *   <li>{@link Output#SURFACE}: the y of the ground, a 2D function (the router's chunk surface level);</li>
 *   <li>{@link Output#LAND}: +1 on dry land a few blocks above the water and -1 elsewhere, which the spawn target looks for, so the player starts on the continent.</li>
 * </ul>
 * The world is found from the compile context's seed (the same way for every output), so it is simulated once, the first time any of them is compiled.
 */
public record EarthField(Output output) implements DensityFunction {
	public static final MapCodec<EarthField> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Output.CODEC.fieldOf("output").forGetter(EarthField::output)
		).apply(i, EarthField::new));

	public enum Output {
		DENSITY, SURFACE, LAND;

		static final Codec<Output> CODEC = Codec.STRING.comapFlatMap(name -> {
			try {
				return DataResult.success(valueOf(name.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				return DataResult.error(() -> "unknown earth_field output: " + name);
			}
		}, output -> output.name().toLowerCase(Locale.ROOT));
	}

	/** The block y of sea level: ground 0 is the plane y = 63. */
	public static final int SEA_LEVEL_Y = 63;

	@Override
	public DensitySampler compileSampler(DensityFunction.CompileContext context) {
		long key = context.createRandom(EpicTerrain.id("earth_field")).nextLong();
		return new Sampler(this.output, EarthWorlds.getOrCreate(key, EpicTerrain.LOGGER::info));
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		return switch (this.output) {
			case DENSITY -> Interval.of(-2048.0f, 2048.0f);
			case SURFACE -> Interval.of(-128.0f, 640.0f);
			case LAND -> Interval.of(-1.0f, 1.0f);
		};
	}

	@Override
	public int domainAxes() {
		return this.output == Output.DENSITY ? DensityFunction.ALL_AXES : DensityFunction.AXIS_X | DensityFunction.AXIS_Z;
	}

	@Override
	public MapCodec<EarthField> codec() {
		return CODEC;
	}

	// ---- finding the world ---------------------------------------------------------------------------------------------

	/** The world a compiled sampler reads, or null if it is not one of ours (the compiler may wrap a sampler that does not depend on y in a slice). */
	public static EarthWorld worldOf(DensitySampler sampler) {
		DensitySampler s = sampler;
		while (s instanceof SliceFunction.YSampler slice) {
			s = slice.input();
		}
		return s instanceof Sampler ours ? ours.world : null;
	}

	/** The world behind the first earth field found inside {@code root} (a router entry, which may be a reference to a named function), or null. */
	public static EarthWorld worldOf(RandomState state, DensityFunction root) {
		Finder finder = new Finder();
		finder.rewrite(root);
		return finder.found == null ? null : worldOf(state.getSampler(finder.found));
	}

	private static final class Finder implements DfRewriteRule {
		private final Set<DensityFunction> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		private EarthField found;

		@Override
		public DensityFunction rewrite(DensityFunction function) {
			if (this.found != null || !this.seen.add(function)) {
				return function;
			}
			if (function instanceof EarthField field) {
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

	private static final class Sampler implements DensitySampler {
		private final Output output;
		private final EarthWorld world;

		Sampler(Output output, EarthWorld world) {
			this.output = output;
			this.world = world;
		}

		private float value(double[] column, int x, int z, int y) {
			return switch (this.output) {
				case DENSITY -> (float) (SEA_LEVEL_Y + column[0] - y);
				case SURFACE -> (float) (SEA_LEVEL_Y + column[0]);
				case LAND -> column[0] >= 6.0 && Double.isNaN(column[1]) ? 1.0f : -1.0f;
			};
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			double[] column = new double[3];
			for (int z = 0; z < volume.sizeZ(); z++) {
				for (int x = 0; x < volume.sizeX(); x++) {
					this.world.sampleBlock(volume.blockX(x), volume.blockZ(z), column);
					for (int y = 0; y < volume.sizeY(); y++) {
						outputBuffer.set(volume.indexUnchecked(x, y, z), this.value(column, x, z, volume.blockY(y)));
					}
				}
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			double[] column = new double[3];
			this.world.sampleBlock(blockX, blockZ, column);
			return this.value(column, blockX, blockZ, blockY);
		}
	}
}
