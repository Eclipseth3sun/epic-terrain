package com.epicterrain.river;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * One {@link RiverService} per world, shared by everything that needs rivers (the biome labels and the carving feature are separate
 * pieces of code that must agree exactly), and the threads that plan on their behalf.
 */
public final class RiverServices {
	private static final ConcurrentHashMap<Long, RiverService> SERVICES = new ConcurrentHashMap<>();
	private static volatile ExecutorService pool;

	private RiverServices() {
	}

	/** The planning threads: a few daemon threads, so they never keep a game or server from closing. */
	public static synchronized ExecutorService pool() {
		if (pool == null) {
			AtomicInteger count = new AtomicInteger();
			int threads = Math.max(2, Math.min(12, Runtime.getRuntime().availableProcessors() - 1));
			pool = Executors.newFixedThreadPool(threads, runnable -> {
				Thread thread = new Thread(runnable, "Epic Terrain river planner #" + count.incrementAndGet());
				thread.setDaemon(true);
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
		}
		return pool;
	}

	/** The service for a world key, creating it the first time. */
	public static RiverService getOrCreate(long key, Supplier<RiverService> factory) {
		return SERVICES.computeIfAbsent(key, k -> factory.get());
	}

	/** Forgets every world's plans (when a server stops); the planning threads stay. */
	public static void clear() {
		SERVICES.clear();
	}

	public static int count() {
		return SERVICES.size();
	}
}
