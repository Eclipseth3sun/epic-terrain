package com.epicterrain.worldgen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Makes the water of a new chunk settle, as vanilla's does.
 *
 * <p>Vanilla fills everything open below sea level with one level of water, which is stable (all of it is level, and what is not water is solid), and the water of its
 * aquifers and of the cave floors its carvers cut is marked for the fluid update the game gives a chunk as it becomes full ({@code markPosForPostProcessing}); so water that
 * could flow does flow, and what is left is at rest. Here a column can have a water level of its own (a river, a lake, a crater lake, see {@link EarthFluidPicker}), and nothing
 * marked it: a river's water steps down from one column to the next, and the source block at the top of each step stood beside air, a lake's one-block surface hung over a
 * cave the carvers had opened under it, and none of it moved until something next to it changed (in a world played for a while, 1,919 such blocks in 377 chunks above the sea,
 * counting only those whose neighbour was in the same chunk).
 *
 * <p>This looks at every block of water (from just under the sea level up, which is where the water of the picker lies) after the chunk's carving, surface and decoration are
 * done, and marks for the fluid update each that has air beside it or under it. The game then ticks them once the chunk is full and the water runs where it would run:
 * a step of a river becomes a small fall, a lake over a cave drains into it, and the rest was already at rest.
 */
public final class FluidSettling {
	/** Water from this height up is looked at: the sea's own is level, and the water of the picker (a river, a lake) is at the surface or above it. */
	static final int FROM_Y = 60;

	private FluidSettling() {
	}

	/** Whether settling is on: {@code -Depicterrain.earth.settle=false} turns it off. */
	public static boolean enabled() {
		return !"false".equalsIgnoreCase(System.getProperty("epicterrain.earth.settle"));
	}

	/**
	 * The water blocks of a chunk that would flow if they were updated: those with air beside them or under them.
	 *
	 * @param around reads the blocks next to the chunk (the world, or the neighbouring chunks), which a chunk's own edge water looks at
	 */
	public static List<BlockPos> unsettled(ChunkAccess chunk, BlockGetter around) {
		List<BlockPos> out = new ArrayList<>();
		ChunkPos cp = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
		for (int si = 0; si < sections.length; si++) {
			LevelChunkSection section = sections[si];
			int baseY = chunk.getMinY() + si * 16;
			if (baseY + 15 < FROM_Y || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.WATER))) {
				continue;
			}
			for (int dy = 0; dy < 16; dy++) {
				int y = baseY + dy;
				if (y < FROM_Y) {
					continue;
				}
				for (int dz = 0; dz < 16; dz++) {
					for (int dx = 0; dx < 16; dx++) {
						if (!section.getBlockState(dx, dy, dz).is(Blocks.WATER)) {
							continue;
						}
						int x = cp.getMinBlockX() + dx;
						int z = cp.getMinBlockZ() + dz;
						if (airAt(chunk, around, cp, x - 1, y, z, next) || airAt(chunk, around, cp, x + 1, y, z, next) || airAt(chunk, around, cp, x, y, z - 1, next)
							|| airAt(chunk, around, cp, x, y, z + 1, next) || (y > chunk.getMinY() && airAt(chunk, around, cp, x, y - 1, z, next))) {
							out.add(pos.set(x, y, z).immutable());
						}
					}
				}
			}
		}
		return out;
	}

	private static boolean airAt(ChunkAccess chunk, BlockGetter around, ChunkPos cp, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
		scratch.set(x, y, z);
		boolean inside = x >= cp.getMinBlockX() && x <= cp.getMaxBlockX() && z >= cp.getMinBlockZ() && z <= cp.getMaxBlockZ();
		BlockState state = inside ? chunk.getBlockState(scratch) : around.getBlockState(scratch);
		return state.isAir();
	}

	/** Marks the water of a chunk that would flow for the fluid update of its post-processing; returns how many blocks it marked. */
	public static int mark(ChunkAccess chunk, BlockGetter around) {
		if (!enabled()) {
			return 0;
		}
		List<BlockPos> blocks = unsettled(chunk, around);
		for (BlockPos pos : blocks) {
			chunk.markPosForPostProcessing(pos);
		}
		return blocks.size();
	}
}
