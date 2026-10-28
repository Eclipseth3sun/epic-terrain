package com.epicterrain.worldgen;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.epicterrain.river.RiverConfig;
import com.epicterrain.river.RiverPlanner;
import com.epicterrain.river.RiverService;
import com.epicterrain.river.RiverServices;
import com.epicterrain.river.River;
import com.epicterrain.river.TerrainView;
import com.epicterrain.river.WorldTerrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
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
 * Where the epic rivers are, as far as the biome source is concerned: a zone number for every column a river reshapes.
 *
 * <p>Rivers are planned from the ranges (see the {@code river} package); this function only asks the shared {@link RiverService}
 * which river, if any, governs a column, and answers with the zone code the biome source understands (4 for the corridor of a
 * tributary, 5 for a grand river, 0 elsewhere). Columns that stand under water are not labelled: the river changes nothing there.
 * The terrain itself is not touched by this function; a feature carves the channel (see {@link RiverChannelFeature}).
 *
 * <p>It is 2D: the value is the same at every y.
 */
public record RiverField(
	Output output,
	DensityFunction slopedCheese,
	DensityFunction offset,
	DensityFunction uplift,
	DensityFunction land,
	DensityFunction ranges,
	float grandChance
) implements DensityFunction {
	public static final MapCodec<RiverField> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Output.CODEC.fieldOf("output").forGetter(RiverField::output),
			DensityFunction.CODEC.fieldOf("sloped_cheese").forGetter(RiverField::slopedCheese),
			DensityFunction.CODEC.fieldOf("offset").forGetter(RiverField::offset),
			DensityFunction.CODEC.fieldOf("uplift").forGetter(RiverField::uplift),
			DensityFunction.CODEC.fieldOf("land").forGetter(RiverField::land),
			DensityFunction.CODEC.fieldOf("ranges").forGetter(RiverField::ranges),
			Codec.floatRange(0.0f, 1.0f).optionalFieldOf("grand_chance", (float) RiverConfig.DEFAULT.grandChance()).forGetter(RiverField::grandChance)
		).apply(i, RiverField::new));

	/** Zone codes the biome source knows: after the three range zones. */
	public static final float TRIBUTARY_ZONE = 4.0f;
	public static final float GRAND_ZONE = 5.0f;
	/** The top solid block of a column at or below this is under vanilla water. */
	private static final int WET = 61;

	public enum Output {
		/** 0 outside every river corridor, 4 in a tributary's, 5 in a grand river's. */
		ZONE,
		/** Distance to the governing river's line in blocks; -1 where there is none. For debugging. */
		LATERAL;

		static final Codec<Output> CODEC = Codec.STRING.comapFlatMap(name -> {
			try {
				return DataResult.success(valueOf(name.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				return DataResult.error(() -> "unknown river_field output: " + name);
			}
		}, output -> output.name().toLowerCase(Locale.ROOT));
	}

	@Override
	public DensitySampler compileSampler(DensityFunction.CompileContext context) {
		RangeField rangeField = RangeField.find(this.ranges).orElseThrow(() -> new IllegalStateException("river_field needs a range_field inside 'ranges'"));
		DensitySampler rangeSampler = rangeField.compileSampler(context);
		if (!(rangeSampler instanceof RangeCatalog catalog)) {
			throw new IllegalStateException("a range_field compiled to " + rangeSampler.getClass().getName() + ", which is not a range catalog");
		}
		TerrainView terrain = new WorldTerrain(this.slopedCheese.compileSampler(context), this.offset.compileSampler(context),
			this.uplift.compileSampler(context), this.land.compileSampler(context));
		RiverConfig config = RiverConfig.DEFAULT.withGrandChance(this.grandChance);
		long key = catalog.worldKey() * 31L + config.hashCode();
		RiverService service = RiverServices.getOrCreate(key,
			() -> new RiverService(new RiverPlanner(catalog.worldKey(), config, terrain, catalog), catalog, RiverServices.pool()));
		return new Sampler(this.output, service, terrain);
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		DensityFunction sloped = rule.rewrite(this.slopedCheese);
		DensityFunction offset = rule.rewrite(this.offset);
		DensityFunction uplift = rule.rewrite(this.uplift);
		DensityFunction land = rule.rewrite(this.land);
		DensityFunction ranges = rule.rewrite(this.ranges);
		if (sloped == this.slopedCheese && offset == this.offset && uplift == this.uplift && land == this.land && ranges == this.ranges) {
			return this;
		}
		return new RiverField(this.output, sloped, offset, uplift, land, ranges, this.grandChance);
	}

	@Override
	public Interval range() {
		return Interval.of(-1.0f, 5.0f);
	}

	@Override
	public int domainAxes() {
		return 5; // x and z only
	}

	@Override
	public MapCodec<RiverField> codec() {
		return CODEC;
	}

	// ---- finding the service ---------------------------------------------------------------------------------------

	/** The river service of the world {@code state} generates for {@code generator}, or null if it is not an Epic Terrain world. */
	public static RiverService serviceOf(RandomState state, ChunkGenerator generator) {
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
			return null;
		}
		return serviceOf(state, noise.generatorSettings().value().noiseRouter().temperature());
	}

	/** The river service behind the first river field found inside {@code root}, or null if there is none. */
	public static RiverService serviceOf(RandomState state, DensityFunction root) {
		Finder finder = new Finder();
		finder.rewrite(root);
		if (finder.found == null) {
			return null;
		}
		// the compiler wraps a function that does not depend on y in a slice sampler; peel that off to reach ours
		DensitySampler sampler = state.getSampler(finder.found);
		while (sampler instanceof SliceFunction.YSampler slice) {
			sampler = slice.input();
		}
		return sampler instanceof Sampler ours ? ours.service : null;
	}

	private static final class Finder implements DfRewriteRule {
		private final Set<DensityFunction> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		private RiverField found;

		@Override
		public DensityFunction rewrite(DensityFunction function) {
			if (this.found != null || !this.seen.add(function)) {
				return function;
			}
			if (function instanceof RiverField field) {
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
		private final RiverService service;
		private final TerrainView terrain;

		Sampler(Output output, RiverService service, TerrainView terrain) {
			this.output = output;
			this.service = service;
			this.terrain = terrain;
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
			// Chunks being generated wait for the plans they need. The server thread (commands such as /locate sample tens of thousands
			// of points far and wide) never waits and never starts planning: it uses whatever has been planned already, which is what
			// the prefetch around the players keeps ahead of them.
			if (!Thread.currentThread().getName().startsWith("Server thread")) {
				this.service.ensureReady(blockX, blockZ, true);
			}
			// the sample stands for a 4 x 4 block biome cell, so look at its centre
			double x = blockX + 2.0;
			double z = blockZ + 2.0;
			RiverService.ColumnRiver river = this.service.at(x, z);
			if (river == null) {
				return this.output == Output.LATERAL ? -1.0f : 0.0f;
			}
			if (this.output == Output.LATERAL) {
				return (float) river.lateral();
			}
			if (this.terrain.ground(x, z) <= WET) {
				return 0.0f; // under vanilla water: the river changes nothing here
			}
			return river.kind() == River.Kind.GRAND ? GRAND_ZONE : TRIBUTARY_ZONE;
		}
	}
}
