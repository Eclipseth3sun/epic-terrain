package com.epicterrain.client;

import java.util.Optional;

import com.epicterrain.EpicTerrain;
import com.epicterrain.worldgen.EpicAmount;
import com.epicterrain.worldgen.EpicChunkGenerator;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Adds a "Customize" button to the Epic Terrain world type, like Superflat's. See WorldCreationUiStateMixin for how it is
 * hooked in (vanilla keeps its editors in an immutable map).
 */
public final class EpicPresetEditor implements PresetEditor {
	public static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, EpicTerrain.id("epic"));
	public static final Optional<ResourceKey<WorldPreset>> PRESET_KEY = Optional.of(PRESET);
	public static final EpicPresetEditor INSTANCE = new EpicPresetEditor();

	private EpicPresetEditor() {}

	@Override
	public Screen createEditScreen(CreateWorldScreen parent, WorldCreationContext settings) {
		int current = settings.selectedDimensions().overworld() instanceof EpicChunkGenerator epic ? epic.amount() : EpicAmount.DEFAULT;
		return new EpicCustomizeScreen(parent, current, percent -> parent.getUiState().updateDimensions(withAmount(percent)));
	}

	/** Swaps the overworld's generator for one that carries the chosen percentage. */
	static WorldCreationContext.DimensionsUpdater withAmount(int percent) {
		return (registryAccess, dimensions) -> {
			ChunkGenerator current = dimensions.overworld();
			if (current instanceof EpicChunkGenerator epic) {
				return dimensions.replaceOverworldGenerator(registryAccess, epic.withAmount(percent));
			}
			if (current instanceof NoiseBasedChunkGenerator noise) {
				// First customization: the datapack preset uses plain minecraft:noise with the epic settings.
				return dimensions.replaceOverworldGenerator(registryAccess,
					new EpicChunkGenerator(noise.getBiomeSource(), noise.generatorSettings(), percent));
			}
			return dimensions;
		};
	}
}
