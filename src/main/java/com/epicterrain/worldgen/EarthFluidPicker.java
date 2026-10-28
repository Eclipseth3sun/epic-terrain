package com.epicterrain.worldgen;

import com.epicterrain.earth.EarthWorld;

import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Where there is water, column by column, in the Earth world: a river, a lake or a crater lake stands at a level of its own above the sea, which vanilla's
 * single sea level cannot say. This is the "global" fluid of the noise pipeline (what fills open space where the aquifers are not consulted); it finds its
 * world when the generator does (see {@link EarthChunkGenerator}).
 *
 * <p>Vanilla's aquifers (the water and lava pockets underground, the flooding of caves under the sea) assume that the water above the ground is the sea at one
 * level, and near a river that stands twenty blocks above the sea they replace its water with levels of their own. So the aquifers are only consulted below the
 * ground ({@link #isAboveGround}, used by {@code NoiseBasedAquiferMixin}); above it this picker alone says what is water and what is air.
 */
public final class EarthFluidPicker implements Aquifer.FluidPicker {
	/** Where vanilla puts lava (below this, and below sea level, open space is lava). */
	private static final int LAVA_LEVEL = -54;

	/** The settings, which may not be loaded yet when the generator is (the world preset is decoded before the noise settings it names are bound); they are read at first use. */
	private final Holder<NoiseGeneratorSettings> settingsHolder;
	private final Aquifer.FluidStatus lava = new Aquifer.FluidStatus(LAVA_LEVEL, Blocks.LAVA.defaultBlockState());
	private final Aquifer.FluidStatus[] levels = new Aquifer.FluidStatus[1024];
	private final ThreadLocal<double[]> scratch = ThreadLocal.withInitial(() -> new double[3]);
	private volatile NoiseGeneratorSettings settings;
	private volatile Aquifer.FluidStatus sea;
	private volatile EarthWorld world;

	EarthFluidPicker(Holder<NoiseGeneratorSettings> settings) {
		this.settingsHolder = settings;
	}

	private NoiseGeneratorSettings settings() {
		NoiseGeneratorSettings known = this.settings;
		if (known == null) {
			known = this.settingsHolder.value();
			this.sea = new Aquifer.FluidStatus(known.seaLevel(), known.defaultFluid());
			this.settings = known;
		}
		return known;
	}

	/** Gives the picker the world it answers for. */
	void bind(EarthWorld world) {
		this.world = world;
	}

	@Override
	public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
		NoiseGeneratorSettings settings = this.settings();
		EarthWorld w = this.world;
		if (w != null) {
			double[] out = this.scratch.get();
			w.sampleBlock(x, z, out);
			if (!Double.isNaN(out[1])) {
				int level = settings.seaLevel() + (int) Math.round(out[1]);
				if (level >= 0 && level < this.levels.length) {
					Aquifer.FluidStatus known = this.levels[level];
					if (known == null) {
						known = new Aquifer.FluidStatus(level, settings.defaultFluid());
						this.levels[level] = known;
					}
					return known;
				}
			}
		}
		return y < Math.min(LAVA_LEVEL, settings.seaLevel()) ? this.lava : this.sea;
	}

	/** Whether a block is above the ground of its column (open space there is the water of the column, or air). */
	public boolean isAboveGround(int x, int y, int z) {
		EarthWorld w = this.world;
		if (w == null) {
			return false;
		}
		double[] out = this.scratch.get();
		w.sampleBlock(x, z, out);
		return y >= Math.ceil(EarthField.SEA_LEVEL_Y + out[0]);
	}
}
