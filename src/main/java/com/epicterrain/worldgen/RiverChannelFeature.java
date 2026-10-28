package com.epicterrain.worldgen;

import com.epicterrain.river.RiverCarver;
import com.epicterrain.river.RiverService;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Cuts the planned rivers into the chunk being decorated: the channel and the gorge walls, tops up banks that came out a little low,
 * and fills the channel with water. It runs first in the decoration (before trees and everything else), so the rest of the world
 * generates on the reshaped ground.
 *
 * <p>It is listed in the feature lists of the epic river biomes only, and has no placement of its own: it runs once per chunk that
 * contains such a biome, with the chunk's corner as its origin. Every column is decided on its own (see {@link RiverCarver}), so
 * chunks agree at their borders.
 */
public record RiverChannelFeature() implements Feature {
	public static final MapCodec<RiverChannelFeature> CODEC = MapCodec.unit(RiverChannelFeature::new);

	@Override
	public MapCodec<RiverChannelFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		RiverService service = RiverField.serviceOf(level.getLevel().getChunkSource().randomState(), generator);
		if (service == null) {
			return false;
		}
		return carve(service, level, origin);
	}

	/** Carves the chunk whose corner is {@code origin}; separate from {@link #place} so it can be run without a server. */
	public static boolean carve(RiverService service, WorldGenLevel level, BlockPos origin) {
		// the labels that brought this chunk here already waited for these plans, so this normally returns at once
		service.ensureReady(origin.getX(), origin.getZ(), true);
		return RiverCarver.carve(service, origin.getX() >> 4, origin.getZ() >> 4, new LevelColumns(level)) > 0;
	}

	/** The carver's view of the chunk under construction. */
	private record LevelColumns(WorldGenLevel level) implements RiverCarver.Columns {
		@Override
		public int floor(int x, int z) {
			int y = this.level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
			// the canopy of a tree in the chunk next door may already stand over this column: the ground is what lies under it
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
			while (y > this.level.getMinY() && isGrowth(this.level.getBlockState(pos.setY(y)))) {
				y--;
			}
			return y;
		}

		private static boolean isGrowth(BlockState state) {
			return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
		}

		@Override
		public void set(int x, int y, int z, RiverCarver.Material material) {
			BlockState state = switch (material) {
				case AIR -> Blocks.AIR.defaultBlockState();
				case WATER -> Blocks.WATER.defaultBlockState();
				case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
				case STONE -> Blocks.STONE.defaultBlockState();
			};
			this.level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
		}

		@Override
		public boolean solid(int x, int y, int z) {
			if (y < this.level.getMinY() || y > this.level.getMaxY()) {
				return false;
			}
			BlockState state = this.level.getBlockState(new BlockPos(x, y, z));
			return !state.isAir() && state.getFluidState().isEmpty() && state.canOcclude();
		}

		@Override
		public boolean air(int x, int y, int z) {
			return y >= this.level.getMinY() && y <= this.level.getMaxY() && this.level.getBlockState(new BlockPos(x, y, z)).isAir();
		}

		@Override
		public void raise(int x, int z, int floor, int top) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, floor, z);
			BlockState surface = this.level.getBlockState(pos);
			BlockState under = this.level.getBlockState(pos.setY(floor - 1));
			if (under.isAir() || !under.getFluidState().isEmpty() || !under.canOcclude()) {
				under = Blocks.STONE.defaultBlockState();
			}
			// the old surface is buried under what is built on it; the new top takes its place as the surface
			for (int y = floor; y < top; y++) {
				this.level.setBlock(new BlockPos(x, y, z), under, Block.UPDATE_CLIENTS);
			}
			this.level.setBlock(new BlockPos(x, top, z), surface, Block.UPDATE_CLIENTS);
		}
	}
}
