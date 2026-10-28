package com.epicterrain.worldgen;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.epicterrain.earth.EarthWorld;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * The chunk generator of the Earth world type. Vanilla's noise pipeline does the work (fill the terrain from a density function, build the surface
 * from the biomes, carve, decorate), with the density function the Earth model's ground under vanilla's caves (see {@link EarthField}); the one thing it
 * changes is where there is water: vanilla fills everything open below sea level, and here any column may have a level of its own (a river, a lake, a crater
 * lake), which is what the fluid picker answers ({@link EarthFluidPicker}). Vanilla's aquifers are kept below the ground (see {@code NoiseBasedAquiferMixin}).
 *
 * <p>The picker is replaced in the constructor (the field is widened by the access widener) and finds its world the first time the generator is asked
 * to do anything with a {@link RandomState}, which is where the seed is, by looking inside the router for the earth field.
 */
public final class EarthChunkGenerator extends NoiseBasedChunkGenerator {
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
			NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings)
		).apply(i, i.stable(EarthChunkGenerator::new)));

	private volatile EarthWorld world;
	private volatile RandomState boundTo;
	private final EarthFluidPicker picker;

	public EarthChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
		super(biomeSource, settings);
		this.picker = new EarthFluidPicker(settings);
		this.globalFluidPicker = () -> this.picker;
	}

	@Override
	protected MapCodec<? extends net.minecraft.world.level.chunk.ChunkGenerator> codec() {
		return CODEC;
	}

	/** Finds this world's Earth model, once per random state. */
	private void bind(RandomState state) {
		if (this.boundTo == state) {
			return;
		}
		synchronized (this) {
			if (this.boundTo != state) {
				this.world = EarthField.worldOf(state, this.generatorSettings().value().noiseRouter().continents());
				this.picker.bind(this.world);
				this.boundTo = state;
			}
		}
	}

	/** The world this generator works in, once it has been used (for tests and the debug screen). */
	public EarthWorld world() {
		return this.world;
	}

	/**
	 * Vanilla's search for the nearest structure ({@code /locate structure}, explorer maps, eyes of ender) looks at up to 100 placement cells in every direction,
	 * each of which has the chunk's structure starts generated on the way. In this world that is a long time for anything that is not close (an ocean monument, from
	 * the middle of the continent, was over four minutes), so it is run in growing steps that stop in time ({@link SearchBudget}).
	 */
	@Override
	public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> structures, BlockPos pos, int radius, boolean skipKnownStructures) {
		return SearchBudget.growing(radius, r -> super.findNearestMapStructure(level, structures, pos, r, skipKnownStructures));
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager, BiomeManager biomeManager,
			WorldGenRegion region, Set<Holder<Biome>> carverBiomes) {
		this.bind(randomState);
		return super.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, region, carverBiomes);
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		this.bind(randomState);
		return super.getBaseHeight(x, z, type, level, randomState);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		this.bind(randomState);
		return super.getBaseColumn(x, z, level, randomState);
	}
}
