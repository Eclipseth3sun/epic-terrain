package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.epicterrain.river.River;
import com.epicterrain.river.RiverService;
import com.epicterrain.river.RiverTestWorld;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** The feature itself, run against a stand-in level that answers only what it asks (heights) and records what it writes (blocks). */
class RiverChannelFeatureTest {
	@Test
	void theFeatureWritesRealBlocksWithTheRightHeightConvention() {
		RiverTestWorld world = RiverTestWorld.create(4242L, 60);
		RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
		service.ensureReady(0, 0, true);
		River river = service.finishedPlans().stream().flatMap(p -> p.rivers().stream()).filter(r -> r.kind() == River.Kind.TRIBUTARY && r.nodes().get(r.nodes().size() / 2).ground() > 90) // a river on dry ground, not through a lake
			.max(java.util.Comparator.comparingDouble(River::length)).orElseThrow();
		River.Node node = river.nodes().get(river.nodes().size() / 2);
		int chunkX = (int) Math.floor(node.x()) >> 4;
		int chunkZ = (int) Math.floor(node.z()) >> 4;

		Map<BlockPos, BlockState> written = new HashMap<>();
		WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "getHeight" -> {
					assertEquals(Heightmap.Types.OCEAN_FLOOR_WG, args[0], "the carver must read the floor heightmap, not the surface one");
					// a height map holds the y of the first free block above the ground
					return (int) Math.floor(world.terrain.ground((int) args[1], (int) args[2])) + 1;
				}
				case "getMinY" -> {
					return -64;
				}
				case "getMaxY" -> {
					return 511;
				}
				case "getBlockState" -> {
					return Blocks.STONE.defaultBlockState(); // the ground: not a tree, not air, and no cave under or beside the water
				}
				case "setBlock" -> {
					written.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
					return true;
				}
				case "hashCode" -> {
					return System.identityHashCode(proxy);
				}
				case "equals" -> {
					return proxy == args[0];
				}
				case "toString" -> {
					return "stand-in level";
				}
				default -> throw new UnsupportedOperationException("the feature asked the level for " + method.getName());
			}
		});

		// the feature's own entry point needs a server; the part under test is everything after the service is found
		boolean changed = RiverChannelFeature.carve(service, level, new BlockPos(chunkX * 16, -64, chunkZ * 16));
		assertTrue(changed, "a chunk in the middle of a river must change");
		long water = written.values().stream().filter(s -> s.is(Blocks.WATER)).count();
		long air = written.values().stream().filter(s -> s.isAir()).count();
		long gravel = written.values().stream().filter(s -> s.is(Blocks.GRAVEL)).count();
		System.out.printf("[rivers] feature wrote %d blocks into chunk (%d, %d): %d water, %d air, %d gravel%n", written.size(), chunkX, chunkZ, water, air, gravel);
		assertTrue(water > 0 && gravel > 0, "a river has water over a gravel bed");
		for (Map.Entry<BlockPos, BlockState> entry : written.entrySet()) {
			// (only plugging a cave beside the water reaches into a neighbour, and this stand-in ground has none)
			assertEquals(chunkX, entry.getKey().getX() >> 4, "the feature writes only its own chunk");
			assertEquals(chunkZ, entry.getKey().getZ() >> 4, "the feature writes only its own chunk");
			if (entry.getValue().is(Blocks.WATER)) {
				assertEquals(0, entry.getValue().getFluidState().getAmount() == 8 ? 0 : 1, "water is placed as source blocks");
			}
		}
		// and a chunk with no river in it is left alone
		Map<BlockPos, BlockState> untouched = new HashMap<>();
		WorldGenLevel quiet = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] { WorldGenLevel.class }, (proxy, method, args) -> switch (method.getName()) {
			case "getHeight" -> 70;
			case "setBlock" -> {
				untouched.put((BlockPos) args[0], (BlockState) args[1]);
				yield true;
			}
			default -> throw new UnsupportedOperationException(method.getName());
		});
		service.ensureReady(900_000, 900_000, true);
		assertEquals(false, RiverChannelFeature.carve(service, quiet, new BlockPos(900_000, -64, 900_000)));
		assertTrue(untouched.isEmpty());
	}
}
