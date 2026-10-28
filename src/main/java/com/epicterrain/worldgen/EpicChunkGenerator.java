package com.epicterrain.worldgen;

import java.util.Optional;

import com.epicterrain.EpicTerrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;

/**
 * Vanilla's noise terrain generator with one extra setting: how much of the world is epic terrain, as a percentage.
 *
 * <p>The percentage is baked into an in-memory copy of the noise settings (the registry is never modified), so the
 * world file only stores the base settings id and a single number. Everything else is vanilla's pipeline.
 *
 * <p>Only construct this where the base settings are already bound: from the world-creation screen or when a world's
 * {@code level.dat} is read. Datapack presets use plain {@code minecraft:noise} (their registry entries are not bound yet
 * while the preset itself is being loaded).
 */
public final class EpicChunkGenerator extends NoiseBasedChunkGenerator {
	public static final ResourceKey<DensityFunction> RANGE_OCCUPANCY = ResourceKey.create(
		Registries.DENSITY_FUNCTION, EpicTerrain.id("config/range_occupancy"));

	public static final MapCodec<EpicChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
			NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.baseSettings),
			Codec.intRange(EpicAmount.MIN, EpicAmount.MAX).optionalFieldOf("amount", EpicAmount.DEFAULT).forGetter(g -> g.amount)
		).apply(i, i.stable(EpicChunkGenerator::new)));

	private final Holder<NoiseGeneratorSettings> baseSettings;
	private final int amount;

	public EpicChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> baseSettings, int amount) {
		super(biomeSource, Holder.direct(derive(baseSettings.value(), EpicAmount.clamp(amount))));
		this.baseSettings = baseSettings;
		this.amount = EpicAmount.clamp(amount);
	}

	public int amount() {
		return this.amount;
	}

	public Holder<NoiseGeneratorSettings> baseSettings() {
		return this.baseSettings;
	}

	public EpicChunkGenerator withAmount(int percent) {
		return new EpicChunkGenerator(this.biomeSource, this.baseSettings, percent);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	/** A copy of {@code base} whose range occupancy is the one for {@code percent}. */
	public static NoiseGeneratorSettings derive(NoiseGeneratorSettings base, int percent) {
		ConfigSubstitution rule = new ConfigSubstitution(RANGE_OCCUPANCY, new ConstantFunction(EpicAmount.occupancy(percent)));
		NoiseRouter router = base.noiseRouter();
		NoiseRouter newRouter = new NoiseRouter(
			rule.rewrite(router.temperature()), rule.rewrite(router.vegetation()), rule.rewrite(router.continents()),
			rule.rewrite(router.erosion()), rule.rewrite(router.depth()), rule.rewrite(router.ridges()),
			rule.rewrite(router.chunkSurfaceLevel()), rule.rewrite(router.finalDensity()));
		Optional<Aquifer.Config> aquifers = base.aquifers().map(c -> new Aquifer.Config(
			rule.rewrite(c.barrierNoise()), rule.rewrite(c.fluidLevelFloodednessNoise()), rule.rewrite(c.fluidLevelSpreadNoise()),
			rule.rewrite(c.lavaNoise()), rule.rewrite(c.exclusion()), rule.rewrite(c.surfaceLevel())));
		return new NoiseGeneratorSettings(
			base.noiseSettings(), base.defaultBlock(), base.defaultFluid(), newRouter, base.materialRule(), base.spawnTarget(),
			base.seaLevel(), base.disableMobGeneration(), aquifers, base.useLegacyRandomSource(), base.debugFunctions());
	}
}
