package com.epicterrain.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The column of beds: its hardness, its thickness, and that it is a pure function of height. */
class EarthStrataTest {
	private static final Strata STRATA = new Strata(4242L);

	@Test
	void everyBedIsOneOfTheFourHardnesses() {
		for (double s = -3000.0; s < 6000.0; s += 3.7) {
			double h = STRATA.hardness(s);
			boolean known = false;
			for (double level : Strata.LEVELS) {
				known |= Math.abs(h - level) < 1.0e-9;
			}
			assertTrue(known, "the bed at " + s + " m erodes at " + h);
			assertEquals(Math.log(h), STRATA.ln(s), 1.0e-12);
		}
	}

	@Test
	void anAverageBedErodesAboutAsFastAsUniformRock() {
		double sum = 0.0;
		int n = 0;
		for (double s = -20_000.0; s < 20_000.0; s += 1.3) {
			sum += STRATA.hardness(s);
			n++;
		}
		assertEquals(1.0, sum / n, 0.12, "the mean erodibility over a thick column is about that of uniform rock");
	}

	@Test
	void bedsAreAboutSeventyMetresThickAndNeverEmpty() {
		int beds = 0;
		double previous = STRATA.ln(-3000.0);
		double start = -3000.0;
		double thinnest = Double.MAX_VALUE;
		double thickest = 0.0;
		for (double s = -3000.0; s < 3000.0; s += 0.5) {
			double now = STRATA.ln(s);
			if (now != previous) {
				double thickness = s - start;
				if (beds > 0) {
					thinnest = Math.min(thinnest, thickness);
					thickest = Math.max(thickest, thickness);
				}
				beds++;
				start = s;
				previous = now;
			}
		}
		// neighbouring beds of the same hardness join into one, so there are fewer changes than beds
		assertTrue(beds > 40 && beds < 85, "6 km of column holds a few dozen changes of bed: " + beds);
		assertTrue(thinnest >= 0.4 * Strata.SPACING - 1.0, "no bed is thinner than 40% of the spacing: " + thinnest);
		System.out.printf("[strata-test] %d changes of bed in 6 km; thinnest bed %.0f m, thickest run of one hardness %.0f m%n", beds, thinnest, thickest);
	}

	@Test
	void theColumnIsAFunctionOfHeightAndSeedOnly() {
		Strata again = new Strata(4242L);
		Strata other = new Strata(7L);
		int differ = 0;
		for (double s = -1000.0; s < 1000.0; s += 11.0) {
			assertEquals(STRATA.ln(s), again.ln(s), 0.0);
			differ += STRATA.ln(s) != other.ln(s) ? 1 : 0;
		}
		assertTrue(differ > 30, "another seed has other beds: " + differ);
	}
}
