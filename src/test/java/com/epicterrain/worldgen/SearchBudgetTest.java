package com.epicterrain.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The growing search that gives up in time, on a clock the test moves itself. */
class SearchBudgetTest {
	/** A search that costs {@code perCell} nanoseconds for every cell of its radius that no earlier step has looked at (the rest is cached) and finds something from {@code foundAt} on. */
	private static final class Fake {
		final long perCell;
		final int foundAt;
		long now;
		int calls;
		int deepest = -1;

		Fake(long perCell, int foundAt) {
			this.perCell = perCell;
			this.foundAt = foundAt;
		}

		String search(int radius) {
			this.calls++;
			long cells = square(2L * radius + 1) - (this.deepest < 0 ? 0 : square(2L * this.deepest + 1));
			this.now += Math.max(0L, cells) * this.perCell;
			this.deepest = Math.max(this.deepest, radius);
			return radius >= this.foundAt ? "found" : null;
		}

		private static long square(long v) {
			return v * v;
		}
	}

	@Test
	void somethingCloseIsFoundWithoutSearchingFar() {
		Fake fake = new Fake(1_000_000L, 3);
		assertNotNull(SearchBudget.growing(100, 5_000_000_000L, () -> fake.now, fake::search));
		assertTrue(fake.calls <= 2, "calls: " + fake.calls);
		assertTrue(fake.deepest <= 4, "looked as far as " + fake.deepest);
	}

	@Test
	void aSearchThatCannotFinishStopsInsideItsBudget() {
		Fake fake = new Fake(1_000_000L, Integer.MAX_VALUE); // a millisecond a cell: the whole radius of 100 would be 40 seconds
		long budget = 2_000_000_000L;
		assertNull(SearchBudget.growing(100, budget, () -> fake.now, fake::search));
		assertTrue(fake.now <= budget, "took " + fake.now / 1e9 + " s of " + budget / 1e9);
		assertTrue(fake.now > budget / 4, "used only " + fake.now / 1e9 + " s of " + budget / 1e9 + ": it gave up too early");
		assertTrue(fake.deepest < 100);
	}

	@Test
	void withTimeToSpareTheWholeRadiusIsSearched() {
		Fake fake = new Fake(1_000L, Integer.MAX_VALUE);
		assertNull(SearchBudget.growing(100, 60_000_000_000L, () -> fake.now, fake::search));
		assertEquals(100, fake.deepest, "the radius asked for was looked at");
	}

	@Test
	void somethingAtTheEdgeOfTheRadiusIsFoundIfTheTimeAllows() {
		Fake fake = new Fake(1_000L, 100);
		assertNotNull(SearchBudget.growing(100, 60_000_000_000L, () -> fake.now, fake::search));
	}

	@Test
	void tinyRadiiWork() {
		Fake none = new Fake(1L, Integer.MAX_VALUE);
		assertNull(SearchBudget.growing(0, 1_000_000L, () -> none.now, none::search));
		assertEquals(0, none.deepest);
		Fake one = new Fake(1L, 1);
		assertNotNull(SearchBudget.growing(1, 1_000_000L, () -> one.now, one::search));
	}

	@Test
	void theGameReadsItsLimitFromAnOption() {
		String old = System.getProperty("epicterrain.earth.locateSeconds");
		try {
			System.setProperty("epicterrain.earth.locateSeconds", "2.5");
			assertEquals(2_500_000_000L, SearchBudget.nanos());
			System.clearProperty("epicterrain.earth.locateSeconds");
			assertEquals((long) (SearchBudget.DEFAULT_SECONDS * 1e9), SearchBudget.nanos());
		} finally {
			if (old != null) {
				System.setProperty("epicterrain.earth.locateSeconds", old);
			} else {
				System.clearProperty("epicterrain.earth.locateSeconds");
			}
		}
	}
}
