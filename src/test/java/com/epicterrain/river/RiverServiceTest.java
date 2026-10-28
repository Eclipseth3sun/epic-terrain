package com.epicterrain.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The cached service must answer the same way whoever asks, in whatever order, from however many threads. */
class RiverServiceTest {
	private static RiverService service(double grandChance, java.util.concurrent.Executor pool) {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic();
		RiverPlanner planner = new RiverPlanner(99L, RiverConfig.DEFAULT.withGrandChance(grandChance).withMinWaterKm2(0.0), world, world);
		return new RiverService(planner, world, pool);
	}

	/** Sample points over the synthetic world: along and across the wall, and far away. */
	private static List<double[]> points(long seed, int count) {
		Random random = new Random(seed);
		List<double[]> points = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			points.add(new double[] { random.nextInt(8000) - 4000, random.nextInt(8000) - 4000 });
		}
		return points;
	}

	@Test
	void plansOnDemandAndAnswersFromTheIndex() {
		RiverService service = service(0.0, Runnable::run);
		assertEquals(0, service.plansStarted(), "nothing is planned until something asks");
		assertNull(service.at(0, 0));

		assertTrue(service.ensureReady(0, 0, true));
		assertEquals(1, service.plansStarted(), "one range lives in this world");
		assertEquals(1, service.plansFinished());

		RangeRivers plan = service.finishedPlans().get(0);
		River river = plan.rivers().get(0);
		River.Node node = river.nodes().get(river.nodes().size() / 2);
		RiverService.ColumnRiver at = service.at(node.x(), node.z());
		assertNotNull(at, "a point on a planned river must find it");
		assertEquals(river.id(), at.river().id());
		assertTrue(at.lateral() < 1.0);
		assertEquals(node.water(), at.water(), 0.5);
		assertNull(service.at(0, 100_000), "far from every river there is none");
	}

	@Test
	void theAnswerDoesNotDependOnWhoAsksFirst() {
		List<double[]> points = points(1, 600);
		RiverService forward = service(1.0, Runnable::run);
		RiverService backward = service(1.0, Runnable::run);
		List<RiverService.ColumnRiver> a = new ArrayList<>();
		for (double[] p : points) {
			forward.ensureReady(p[0], p[1], true);
			a.add(forward.at(p[0], p[1]));
		}
		List<RiverService.ColumnRiver> b = new ArrayList<>();
		for (int i = points.size() - 1; i >= 0; i--) {
			backward.ensureReady(points.get(i)[0], points.get(i)[1], true);
			b.add(0, backward.at(points.get(i)[0], points.get(i)[1]));
		}
		assertEquals(a, b);
		assertTrue(a.stream().anyMatch(c -> c != null), "some of these points are on a river");
	}

	@Test
	void manyThreadsAskingAtOnceAgreeWithOneThread() throws Exception {
		List<double[]> points = points(2, 800);
		RiverService single = service(1.0, Runnable::run);
		List<RiverService.ColumnRiver> expected = new ArrayList<>();
		for (double[] p : points) {
			single.ensureReady(p[0], p[1], true);
			expected.add(single.at(p[0], p[1]));
		}

		RiverService shared = service(1.0, RiverServices.pool());
		ExecutorService askers = Executors.newFixedThreadPool(8);
		try {
			List<Future<List<RiverService.ColumnRiver>>> futures = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				int offset = t * 100;
				futures.add(askers.submit(() -> {
					List<RiverService.ColumnRiver> got = new ArrayList<>();
					for (int i = 0; i < points.size(); i++) {
						double[] p = points.get((i + offset) % points.size());
						shared.ensureReady(p[0], p[1], true);
						got.add(shared.at(p[0], p[1]));
					}
					return got;
				}));
			}
			for (int t = 0; t < 8; t++) {
				List<RiverService.ColumnRiver> got = futures.get(t).get(60, TimeUnit.SECONDS);
				for (int i = 0; i < points.size(); i++) {
					assertEquals(expected.get((i + t * 100) % points.size()), got.get(i), "thread " + t + " point " + i);
				}
			}
			assertEquals(1, shared.plansStarted(), "the one range is planned exactly once however many ask");
		} finally {
			askers.shutdownNow();
		}
	}

	@Test
	void prefetchNeverBlocksAndTheSafetyNetWaitsForExactlyWhatItNeeds() {
		List<Runnable> held = new ArrayList<>();
		RiverService service = service(0.0, held::add); // an executor that holds every task until released
		service.prefetch(0, 0);
		assertEquals(1, held.size(), "prefetch queues the plan and returns at once");
		assertFalse(service.ensureReady(0, 0, false), "not ready while the plan is still queued");
		assertNull(service.at(0, 0));
		held.forEach(Runnable::run);
		assertTrue(service.ensureReady(0, 0, false), "ready once the plan has run");
		assertEquals(1, service.plansFinished());
		// asking again, or prefetching again, plans nothing more
		service.prefetch(0, 0);
		service.ensureReady(0, 0, true);
		assertEquals(1, service.plansStarted());
	}

	@Test
	void askingWhetherSomethingIsReadyNeverStartsPlanning() {
		List<Runnable> held = new ArrayList<>();
		RiverService service = service(1.0, held::add);
		for (int i = 0; i < 2000; i++) {
			assertFalse(service.isReady(i * 37.0, -i * 91.0), "nothing has been asked for yet, so nothing is ready");
		}
		assertEquals(0, service.plansStarted(), "a command searching the world must not start any planning");
		assertTrue(held.isEmpty());
		service.prefetch(0, 0);
		assertFalse(service.isReady(0, 0), "requested but not done");
		held.forEach(Runnable::run);
		assertTrue(service.isReady(0, 0), "done");
		// asking over and over about a cell that is not ready costs one future check, not a walk over the neighbourhood
		RiverService busy = service(1.0, held::add);
		busy.prefetch(50_000, 50_000);
		long start = System.nanoTime();
		for (int i = 0; i < 200_000; i++) {
			busy.ensureReady(50_000, 50_000, false);
		}
		assertTrue((System.nanoTime() - start) / 1.0e9 < 2.0, "repeated asks must be cheap");
	}

	@Test
	void aChunkOnlyDependsOnRangesThatCanReachIt() {
		// the single wall sits in cell (0, 0). Ordinary rivers reach two cells; a grand river as far as the grand reach.
		RiverService ordinary = service(0.0, Runnable::run);
		ordinary.ensureReady(0, 0, true);
		assertEquals(1, ordinary.plansStarted());
		RiverService far = service(0.0, Runnable::run);
		far.ensureReady(6144.0 * 4, 0, true); // four cells away: no tributary can get here
		assertEquals(0, far.plansStarted(), "a point four cells from every range needs no plans without grand rivers");
		RiverService nearby = service(0.0, Runnable::run);
		nearby.ensureReady(6144.0 * 2, 0, true);
		assertEquals(1, nearby.plansStarted(), "two cells away a tributary could still arrive");

		// with grand rivers on, a point 30 km away may be crossed by one
		RiverService grand = service(1.0, Runnable::run);
		grand.ensureReady(30_000, 0, true);
		assertEquals(1, grand.plansStarted(), "the range rolled a grand river, which can reach 30 km");
		RiverService beyond = service(1.0, Runnable::run);
		beyond.ensureReady(200_000, 0, true);
		assertEquals(0, beyond.plansStarted(), "but not 200 km");
	}

	@Test
	void planningFailureLeavesTheWorldGeneratingWithoutRivers() {
		RiverPlannerTest.Synthetic world = new RiverPlannerTest.Synthetic() {
			final AtomicInteger calls = new AtomicInteger();

			@Override
			public double roughGround(double x, double z) {
				if (this.calls.incrementAndGet() > 5) {
					throw new IllegalStateException("terrain exploded");
				}
				return super.roughGround(x, z);
			}
		};
		RiverPlanner planner = new RiverPlanner(99L, RiverConfig.DEFAULT.withGrandChance(0.0), world, world);
		RiverService service = new RiverService(planner, world, Runnable::run);
		assertTrue(service.ensureReady(0, 0, true), "a failed plan must not block chunk generation");
		assertNull(service.at(0, 0));
	}
}
