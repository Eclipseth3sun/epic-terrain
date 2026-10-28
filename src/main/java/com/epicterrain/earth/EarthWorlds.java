package com.epicterrain.earth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The Earth worlds of this JVM, one per world key. The first time a world's terrain function is compiled it simulates the window for that world,
 * which takes about a minute at the default cell size; after that every function of the same world shares the result.
 */
public final class EarthWorlds {
	private static final Map<Long, EarthWorld> WORLDS = new ConcurrentHashMap<>();

	private EarthWorlds() {
	}

	/** The world for a key, simulating it if this is the first ask. The dials come from {@code -Depicterrain.earth.*} (see {@link EarthConfig#fromProperties}). */
	public static EarthWorld getOrCreate(long key, Consumer<String> log) {
		return WORLDS.computeIfAbsent(key, k -> new EarthWorld(EarthConfig.fromProperties().withSeed(k), log));
	}

	/** The only world there is, or null if there are none or several: a fallback for code that cannot tell which world it is working for. */
	public static EarthWorld only() {
		return WORLDS.size() == 1 ? WORLDS.values().iterator().next() : null;
	}

	public static void clear() {
		WORLDS.clear();
	}
}
