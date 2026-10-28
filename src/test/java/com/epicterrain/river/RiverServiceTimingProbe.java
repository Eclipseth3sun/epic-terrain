package com.epicterrain.river;

import com.epicterrain.worldgen.RiverField;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** How long does a chunk wait the first time it asks for rivers somewhere new? Not part of the normal test run. */
@Tag("preview")
class RiverServiceTimingProbe {
	@Test
	void firstLoad() {
		// -Depicterrain.seed / -Depicterrain.amount pick one world instead of the whole set
		long[] seeds = System.getProperty("epicterrain.seed") != null ? new long[] { Long.getLong("epicterrain.seed") } : new long[] { 4242L, 4310335781435980812L, 99L };
		int[] percents = System.getProperty("epicterrain.amount") != null ? new int[] { Integer.getInteger("epicterrain.amount") } : new int[] { 20, 50, 100 };
		for (long seed : seeds) {
			for (int percent : percents) {
				RiverServices.clear();
				RiverTestWorld world = RiverTestWorld.create(seed, percent);
				RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
				long t0 = System.currentTimeMillis();
				service.ensureReady(0, 0, true);
				long first = System.currentTimeMillis() - t0;
				long t1 = System.currentTimeMillis();
				service.ensureReady(40_000, 20_000, true);
				long second = System.currentTimeMillis() - t1;
				long grands = service.finishedPlans().stream().filter(p -> p.hasGrand()).count();
				System.out.printf("[timing] seed %d slider %d%%: first look at the origin waited %.1fs for %d plans (%d with a grand river); a new area 45 km away %.1fs more (%d plans in all)%n",
					seed, percent, first / 1000.0, service.plansFinished(), grands, second / 1000.0, service.plansFinished());
			}
		}
	}
}
