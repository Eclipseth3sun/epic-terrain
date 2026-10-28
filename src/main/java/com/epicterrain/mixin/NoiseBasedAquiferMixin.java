package com.epicterrain.mixin;

import com.epicterrain.worldgen.EarthFluidPicker;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;

/**
 * Vanilla's aquifers take the water above the ground to be the sea at one level, and near a river or a lake standing above the sea they put their own levels
 * and barrier blocks where the water should be. In the Earth world the fluid picker ({@link EarthFluidPicker}) says what is water above the ground, so above
 * the ground of a column the aquifer asks it and nothing else, as vanilla's own aquifer does above the highest ground of a chunk; below the ground the
 * aquifers are vanilla's. Other worlds (their picker is not ours) are not touched.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class NoiseBasedAquiferMixin {
	@Shadow
	@Final
	private Aquifer.FluidPicker globalFluidPicker;

	@Shadow
	private boolean shouldScheduleFluidUpdate;

	@Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
	private void epicterrain$pickerAboveTheGround(int x, int y, int z, double density, CallbackInfoReturnable<BlockState> cir) {
		if (density <= 0.0 && this.globalFluidPicker instanceof EarthFluidPicker picker && picker.isAboveGround(x, y, z)) {
			this.shouldScheduleFluidUpdate = false;
			cir.setReturnValue(picker.computeFluid(x, y, z).at(y));
		}
	}
}
