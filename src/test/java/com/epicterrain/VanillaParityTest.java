package com.epicterrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/** The core promise: where no epic range reaches, an Epic Terrain world generates exactly like vanilla. */
class VanillaParityTest {
	private static final long SEED = 987654321L;

	/**
	 * Terrain is interpolated between sample points up to 16 blocks apart, so "outside a range" has to mean the whole
	 * neighbourhood is untouched; right at a range's fringe the difference is tiny but not zero.
	 */
	private static boolean touchesRange(RandomState state, DensityFunction uplift, int x, int z) {
		for (int dx = -32; dx <= 32; dx += 16) {
			for (int dz = -32; dz <= 32; dz += 16) {
				if (state.sampleBlockValueUncached(uplift, x + dx, 0, z + dz) > 0.0f) {
					return true;
				}
			}
		}
		return false;
	}

	@Test
	void terrainMatchesVanillaOutsideRanges() {
		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings vanilla = h.settings("minecraft:overworld").orElseThrow();
		NoiseGeneratorSettings epic = h.settings("epicterrain:overworld").orElseThrow();
		RandomState vanillaState = h.randomState(SEED, vanilla);
		RandomState epicState = h.randomState(SEED, epic);
		DensityFunction uplift = h.registries.lookupOrThrow(Registries.DENSITY_FUNCTION)
			.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("epicterrain:range/uplift"))).value();

		Random random = new Random(11);
		int compared = 0;
		int skipped = 0;
		while (compared < 3000) {
			int x = random.nextInt(200_000) - 100_000;
			int z = random.nextInt(200_000) - 100_000;
			if (touchesRange(epicState, uplift, x, z)) {
				skipped++;
				continue;
			}
			int y = -64 + random.nextInt(384); // vanilla's own build range
			float expected = vanillaState.sampleBlockValueUncached(vanilla.noiseRouter().finalDensity(), x, y, z);
			float actual = epicState.sampleBlockValueUncached(epic.noiseRouter().finalDensity(), x, y, z);
			assertEquals(expected, actual, 1.0e-6f, "final_density differs at " + x + "," + y + "," + z);
			compared++;
		}
		// Sanity: ranges must not cover most of the world, or the test above proves little.
		assertTrue(skipped < compared, "epic ranges cover too much of the sampled world: skipped " + skipped + " vs compared " + compared);
		System.out.println("vanilla parity: " + compared + " points identical, " + skipped + " inside ranges skipped");
	}
}
