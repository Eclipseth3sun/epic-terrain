package com.epicterrain.client.mixin;

import java.util.Map;

import com.epicterrain.client.EpicPresetEditor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * Vanilla looks preset editors up in {@code PresetEditor.EDITORS}, an immutable {@code Map.of(...)}. Both lookups (the
 * Customize button's editor, and "is this preset customizable?" when data packs change) go through this wrapper so the
 * Epic Terrain preset gets one too.
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {
	@WrapOperation(
		method = { "getPresetEditor", "updatePresetLists" },
		at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;")
	)
	private Object epicterrain$lookupEditor(Map<?, ?> editors, Object key, Operation<Object> original) {
		if (EpicPresetEditor.PRESET_KEY.equals(key)) {
			return EpicPresetEditor.INSTANCE;
		}
		return original.call(editors, key);
	}
}
