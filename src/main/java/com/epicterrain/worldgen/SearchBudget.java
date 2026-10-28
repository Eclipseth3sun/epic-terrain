package com.epicterrain.worldgen;

import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * How long a search of an Earth world ({@code /locate biome}, {@code /locate structure}, explorer maps, eyes of ender) may take before it gives up and says it found
 * nothing. The searches run on the server thread, and the Earth world is big enough that a search for something rare, which vanilla would answer in a few seconds
 * because most of its world is within reach, can take minutes; a search that stops with the usual "could not find ... within a reasonable distance" is better than a
 * frozen server. The limit is {@code -Depicterrain.earth.locateSeconds} (default 5).
 */
public final class SearchBudget {
	public static final double DEFAULT_SECONDS = 5.0;

	private SearchBudget() {
	}

	/** The limit in nanoseconds. */
	public static long nanos() {
		String value = System.getProperty("epicterrain.earth.locateSeconds");
		double seconds = value == null ? DEFAULT_SECONDS : Double.parseDouble(value);
		return (long) (seconds * 1e9);
	}

	private static long square(long v) {
		return v * v;
	}

	/**
	 * Runs a search whose cost grows with its radius (in cells, as the structure search counts it) in growing steps, so that it can stop in time: first a small radius,
	 * then a larger one while the time spent, and the time the next step is expected to take, fit the budget. Earlier steps leave their work cached (the structure
	 * starts of the chunks they looked at), so a step costs about what its new cells cost; that is what the expectation is made of, from the cost of the last step.
	 * The first answer found is returned, which is the same one a single search of the full radius would find (the search goes outwards ring by ring and stops at
	 * the first ring that has one). If the search is expected to run out of time, or the full radius found nothing, the result is null.
	 *
	 * @param radius the radius asked for
	 * @param budgetNanos how long all of it may take
	 * @param clock nanoseconds
	 * @param search the search for one radius; null if it found nothing
	 */
	public static <T> T growing(int radius, long budgetNanos, LongSupplier clock, IntFunction<T> search) {
		long start = clock.getAsLong();
		int done = -1; // the radius searched so far; none yet
		int next = Math.min(radius, 2);
		double perCell = 0.0; // nanoseconds per new cell, as of the last step
		while (true) {
			long before = clock.getAsLong();
			T found = search.apply(next);
			if (found != null) {
				return found;
			}
			long took = clock.getAsLong() - before;
			long newCells = square(2L * next + 1) - (done < 0 ? 0 : square(2L * done + 1));
			perCell = (double) took / Math.max(1L, newCells);
			done = next;
			if (done >= radius) {
				return null;
			}
			long left = budgetNanos - (clock.getAsLong() - start);
			// the largest step up to a doubling whose new cells fit in what is left
			int candidate = Math.min(radius, done * 2);
			while (candidate > done && perCell * (square(2L * candidate + 1) - square(2L * done + 1)) > left) {
				candidate--;
			}
			if (candidate <= done) {
				return null;
			}
			next = candidate;
		}
	}

	/** As {@link #growing(int, long, LongSupplier, IntFunction)}, with the limit and the clock of the game. */
	public static <T> T growing(int radius, IntFunction<T> search) {
		return growing(radius, nanos(), System::nanoTime, search);
	}
}
