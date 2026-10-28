package com.epicterrain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.worldgen.EpicAmount;
import com.epicterrain.worldgen.EpicChunkGenerator;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

class EpicPresetEditorTest {
	private static WorldDimensions epicPresetDimensions(WorldgenHarness h) {
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:epic"))).value();
		return preset.createWorldDimensions();
	}

	@Test
	void customizingSwapsInTheEpicGeneratorAndCanBeRepeated() {
		WorldgenHarness h = WorldgenHarness.shared();
		WorldDimensions fresh = epicPresetDimensions(h);
		assertFalse(fresh.overworld() instanceof EpicChunkGenerator, "the datapack preset uses plain minecraft:noise");

		WorldDimensions first = EpicPresetEditor.withAmount(55).apply(h.registries, fresh);
		assertEquals(55, assertInstanceOf(EpicChunkGenerator.class, first.overworld()).amount());

		WorldDimensions second = EpicPresetEditor.withAmount(8).apply(h.registries, first);
		assertEquals(8, assertInstanceOf(EpicChunkGenerator.class, second.overworld()).amount());

		// Other dimensions are left alone.
		assertTrue(second.dimensions().containsKey(LevelStem.NETHER));
		assertTrue(second.dimensions().containsKey(LevelStem.END));
		assertEquals(fresh.dimensions().get(LevelStem.NETHER), second.dimensions().get(LevelStem.NETHER));
	}

	@Test
	void sliderRangeMatchesWhatTheScreenOffers() {
		assertEquals(1, EpicAmount.MIN);
		assertEquals(100, EpicAmount.MAX);
		assertTrue(EpicAmount.DEFAULT >= EpicAmount.MIN && EpicAmount.DEFAULT <= EpicAmount.MAX);
	}

	/**
	 * Loading the class runs the mixin. The config sets defaultRequire=1, so if either target method
	 * ({@code getPresetEditor}, {@code updatePresetLists}) had no matching Map.get call, loading would throw here instead of
	 * crashing the world-creation screen in game.
	 */
	@Test
	void mixinIsAppliedToTheWorldCreationState() throws Exception {
		Class<?> state = Class.forName("net.minecraft.client.gui.screens.worldselection.WorldCreationUiState");
		boolean merged = Arrays.stream(state.getDeclaredMethods()).anyMatch(m -> m.getName().contains("epicterrain$lookupEditor"));
		assertTrue(merged, "the mixin handler should have been merged into WorldCreationUiState");
	}
}
