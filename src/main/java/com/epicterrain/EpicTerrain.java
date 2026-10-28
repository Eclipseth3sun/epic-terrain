package com.epicterrain;

import com.epicterrain.earth.EarthWorlds;
import com.epicterrain.worldgen.EarthBiomeSource;
import com.epicterrain.worldgen.EarthChunkGenerator;
import com.epicterrain.worldgen.EarthField;
import com.epicterrain.worldgen.EpicBiomeSource;
import com.epicterrain.worldgen.EpicChunkGenerator;
import com.epicterrain.worldgen.RangeField;
import com.epicterrain.worldgen.RiverChannelFeature;
import com.epicterrain.worldgen.RiverField;
import com.epicterrain.worldgen.RiverRuntime;
import com.mojang.serialization.MapCodec;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EpicTerrain implements ModInitializer {
	public static final String MOD_ID = "epicterrain";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		registerWorldgen(Registry::register);
		RiverRuntime.register();
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> EarthWorlds.clear());
		LOGGER.info("Epic Terrain loaded");
	}

	/** How a worldgen type gets into a built-in registry; tests swap this out because they bootstrap after the freeze. */
	@FunctionalInterface
	public interface Registrar {
		<T> void register(Registry<T> registry, Identifier id, T value);
	}

	public static void registerWorldgen(Registrar registrar) {
		registrar.register(BuiltInRegistries.CHUNK_GENERATOR, id("epic_noise"), EpicChunkGenerator.CODEC);
		registrar.register(BuiltInRegistries.BIOME_SOURCE, id("epic_multi_noise"), EpicBiomeSource.CODEC);
		registrar.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth_noise"), EarthChunkGenerator.CODEC);
		registrar.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);
		registrar.<MapCodec<? extends DensityFunction>>register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("earth_field"), EarthField.CODEC);
		registrar.<MapCodec<? extends DensityFunction>>register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("range_field"), RangeField.CODEC);
		registrar.<MapCodec<? extends DensityFunction>>register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("river_field"), RiverField.CODEC);
		registrar.<MapCodec<? extends Feature>>register(BuiltInRegistries.FEATURE_TYPE, id("river_channel"), RiverChannelFeature.CODEC);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
