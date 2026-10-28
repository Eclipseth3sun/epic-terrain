package com.epicterrain.river;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.worldgen.ConfigSubstitution;
import com.epicterrain.worldgen.EpicAmount;
import com.epicterrain.worldgen.EpicChunkGenerator;
import com.epicterrain.worldgen.RangeCatalog;
import com.epicterrain.worldgen.RangeField;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;

/** A real Epic Terrain world (the registered settings with the slider at a chosen percentage) for river tests and previews. */
public final class RiverTestWorld {
	public final long seed;
	public final int percent;
	public final RandomState state;
	public final NoiseGeneratorSettings settings;
	public final RangeCatalog catalog;
	public final TerrainView terrain;
	public final DensityFunction upliftFunction;
	private final ConfigSubstitution amount;

	private RiverTestWorld(long seed, int percent, RandomState state, NoiseGeneratorSettings settings, RangeCatalog catalog, TerrainView terrain,
		DensityFunction upliftFunction, ConfigSubstitution amount) {
		this.seed = seed;
		this.percent = percent;
		this.state = state;
		this.settings = settings;
		this.catalog = catalog;
		this.terrain = terrain;
		this.upliftFunction = upliftFunction;
		this.amount = amount;
	}

	/** A registered range function (kind, distance, ...) as it behaves in this world, i.e. with this world's slider value. */
	public DensityFunction registeredHere(WorldgenHarness h, String id) {
		return this.amount.rewrite(registered(h, id));
	}

	public static DensityFunction registered(WorldgenHarness h, String id) {
		return h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id))).value();
	}

	public static RiverTestWorld create(long seed, int percent) {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings derived = EpicChunkGenerator.derive(h.settings("epicterrain:overworld").orElseThrow(), percent);
		RandomState state = h.randomState(seed, derived);
		ConfigSubstitution amount = new ConfigSubstitution(EpicChunkGenerator.RANGE_OCCUPANCY, new ConstantFunction(EpicAmount.occupancy(percent)));
		DensityFunction uplift = amount.rewrite(registered(h, "epicterrain:range/uplift"));
		DensityFunction sloped = amount.rewrite(registered(h, "epicterrain:overworld/sloped_cheese"));
		DensityFunction offset = amount.rewrite(registered(h, "epicterrain:overworld/offset"));
		TerrainView terrain = WorldTerrain.of(state, sloped, offset, uplift, registered(h, "minecraft:overworld/continents"));
		RangeCatalog catalog = RangeField.catalog(state, derived.noiseRouter().finalDensity());
		return new RiverTestWorld(seed, percent, state, derived, catalog, terrain, uplift, amount);
	}
}
