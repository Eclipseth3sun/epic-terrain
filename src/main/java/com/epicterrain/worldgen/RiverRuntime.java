package com.epicterrain.worldgen;

import com.epicterrain.river.RiverService;
import com.epicterrain.river.RiverServices;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Keeps river planning ahead of chunk generation: while a server runs, the plans for the country around every player (and around the
 * origin when a level loads) are started in the background, so that when chunks there are generated the plans are already done and
 * generation only reads from the index. Generation still waits correctly for any plan it needs that is not done yet.
 */
public final class RiverRuntime {
	/** Ticks between looks at where the players are. */
	private static final int PERIOD = 40;
	/** Players generate chunks well beyond where they stand; ask for the cells around this far out too. */
	private static final double LOOKAHEAD = 700.0;

	private RiverRuntime() {
	}

	public static void register() {
		ServerLevelEvents.LOAD.register((server, level) -> prefetch(level, 0.0, 0.0));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % PERIOD != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				ServerLevel level = player.level();
				double x = player.getX();
				double z = player.getZ();
				prefetch(level, x, z);
				prefetch(level, x + LOOKAHEAD, z + LOOKAHEAD);
				prefetch(level, x - LOOKAHEAD, z + LOOKAHEAD);
				prefetch(level, x + LOOKAHEAD, z - LOOKAHEAD);
				prefetch(level, x - LOOKAHEAD, z - LOOKAHEAD);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> RiverServices.clear());
	}

	private static void prefetch(ServerLevel level, double x, double z) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof EpicChunkGenerator)) {
			return;
		}
		RiverService service = RiverField.serviceOf(level.getChunkSource().randomState(), generator);
		if (service != null) {
			service.prefetch(x, z);
		}
	}
}
