package com.epicterrain;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Writes the ground height plain vanilla would generate for a seed over a rectangle, so a played world's saved chunks can be
 * compared against it (see tools in the scratchpad's map_world.py). Not part of the normal test run.
 *
 * <pre>./gradlew preview --tests com.epicterrain.VanillaBaselineTest -Depicterrain.seed=... -Depicterrain.bbox=x0,z0,x1,z1
 *     [-Depicterrain.step=16] [-Depicterrain.baselineOut=path/to/baseline.bin]</pre>
 *
 * <p>Output: a small text header next to the file ({@code .txt}: x0 z0 step width depth) and then {@code width * depth}
 * big-endian shorts, row by row from the north-west corner; each is the height of the top face of the highest non-air block
 * (so the water surface, 63, over oceans): the median of a 3 x 3 grid of columns across its step x step cell. WORLD_SURFACE_WG is the only height type
 * that works here: the ocean-floor and motion-blocking types need game state that a headless harness lacks and silently
 * answer -64.
 */
@Tag("preview")
class VanillaBaselineTest {
	@Test
	void writeBaseline() throws Exception {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		String[] bbox = System.getProperty("epicterrain.bbox", "-512,-512,512,512").split(",");
		int x0 = Integer.parseInt(bbox[0].trim());
		int z0 = Integer.parseInt(bbox[1].trim());
		int x1 = Integer.parseInt(bbox[2].trim());
		int z1 = Integer.parseInt(bbox[3].trim());
		int step = Integer.getInteger("epicterrain.step", 16);
		File out = new File(System.getProperty("epicterrain.baselineOut", "build/preview/vanilla_baseline.bin"));
		out.getParentFile().mkdirs();

		WorldgenHarness h = WorldgenHarness.shared();
		NoiseGeneratorSettings settings = h.settings("minecraft:overworld").orElseThrow();
		RandomState state = h.randomState(seed, settings);
		WorldPreset preset = h.registries.lookupOrThrow(Registries.WORLD_PRESET)
			.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("minecraft:normal"))).value();
		BiomeSource biomes = preset.overworld().orElseThrow().generator().getBiomeSource();
		NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, Holder.direct(settings));
		LevelHeightAccessor world = LevelHeightAccessor.create(-64, 384);

		int width = (x1 - x0) / step;
		int depth = (z1 - z0) / step;
		short[] heights = new short[width * depth];
		long start = System.currentTimeMillis();
		// A single column can land in a cave-entrance shaft and read tens of blocks low, so take the median of a grid of columns
		// per cell (3 x 3 by default), which is what the played world's per-cell median is compared against.
		int grid = Integer.getInteger("epicterrain.samples", 3);
		IntStream.range(0, depth).parallel().forEach(row -> {
			int[] column = new int[grid * grid];
			for (int col = 0; col < width; col++) {
				int n = 0;
				for (int i = 0; i < grid; i++) {
					for (int j = 0; j < grid; j++) {
						int x = x0 + col * step + (step * (2 * i + 1)) / (2 * grid);
						int z = z0 + row * step + (step * (2 * j + 1)) / (2 * grid);
						column[n++] = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, world, state);
					}
				}
				java.util.Arrays.sort(column);
				heights[row * width + col] = (short) column[column.length / 2];
			}
		});
		try (DataOutputStream stream = new DataOutputStream(new FileOutputStream(out))) {
			for (short height : heights) {
				stream.writeShort(height);
			}
		}
		Files.writeString(new File(out.getPath() + ".txt").toPath(), x0 + " " + z0 + " " + step + " " + width + " " + depth + "\n", StandardCharsets.UTF_8);
		System.out.printf("[baseline] seed %d: %d x %d cells of %d blocks in %.1fs -> %s%n", seed, width, depth, step, (System.currentTimeMillis() - start) / 1000.0, out);
	}
}
