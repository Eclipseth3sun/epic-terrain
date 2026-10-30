package com.epicterrain.worldgen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthWorld;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Where the mountains of an Earth world are: its highest summits and its mountain massifs, with their places, sizes and biomes, and how far and which way each is from a
 * given position. Nothing happens unless {@code -Depicterrain.mountains=SEED} is given ({@code -Depicterrain.from=X,Z} says where you are, the spawn by default).
 */
@Tag("preview")
class MountainSurveyProbeTest {
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);
	private static final int STEP = 64;
	/**
	 * Ground this many blocks above the sea is mountain country (150, y 213 and up, by default; {@code -Depicterrain.mountainFrom=} changes it); 150 blocks more is its high core.
	 */
	private static final double MOUNTAIN = Double.parseDouble(System.getProperty("epicterrain.mountainFrom", "150"));
	private static final double HIGH = MOUNTAIN + 150.0;

	private record Summit(int x, int z, int topY) {
	}

	@Test
	void survey() {
		String seed = System.getProperty("epicterrain.mountains");
		if (seed == null) {
			return;
		}
		int fromX = 0;
		int fromZ = 0;
		String from = System.getProperty("epicterrain.from");
		if (from != null) {
			fromX = (int) Double.parseDouble(from.split(",")[0]);
			fromZ = (int) Double.parseDouble(from.split(",")[1]);
		}
		WorldgenHarness harness = WorldgenHarness.shared();
		WorldPreset preset = harness.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
		EarthChunkGenerator generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
		RandomState state = harness.randomState(Long.parseLong(seed.trim()), generator.generatorSettings().value());
		generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, state);
		EarthWorld world = generator.world();
		BiomeResolver resolver = generator.getBiomeSource().createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
		double[] w = world.window();
		int x0 = (int) w[0];
		int z0 = (int) w[2];
		int n = (int) ((w[1] - w[0]) / STEP);
		float[] g = new float[n * n];
		double[] out = new double[3];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				world.sampleBlock(x0 + i * STEP, z0 + j * STEP, out);
				g[j * n + i] = (float) out[0];
			}
		}
		System.out.printf("MOUNTAINS of world seed %s, window %.0f..%.0f; you are at x %d, z %d%n", seed, w[0], w[1], fromX, fromZ);

		// the massifs: connected stretches of ground more than 150 blocks above the sea
		int[] label = new int[n * n];
		List<int[]> massifs = new ArrayList<>(); // {cells, high cells, maxIndex}
		int next = 0;
		for (int start = 0; start < n * n; start++) {
			if (g[start] < MOUNTAIN || label[start] != 0) {
				continue;
			}
			next++;
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(start);
			label[start] = next;
			int cells = 0;
			int high = 0;
			int max = start;
			while (!queue.isEmpty()) {
				int c = queue.poll();
				cells++;
				high += g[c] >= HIGH ? 1 : 0;
				if (g[c] > g[max]) {
					max = c;
				}
				int ci = c % n;
				int cj = c / n;
				for (int dj = -1; dj <= 1; dj++) {
					for (int di = -1; di <= 1; di++) {
						int ni = ci + di;
						int nj = cj + dj;
						if (ni < 0 || nj < 0 || ni >= n || nj >= n) {
							continue;
						}
						int m = nj * n + ni;
						if (g[m] >= MOUNTAIN && label[m] == 0) {
							label[m] = next;
							queue.add(m);
						}
					}
				}
			}
			massifs.add(new int[] { next, cells, high, max });
		}
		massifs.removeIf(m -> m[1] < 400);
		massifs.sort(Comparator.comparingDouble((int[] m) -> -(g[m[3]] + 0.0004 * m[1])));
		double cellKm2 = STEP * STEP / 1.0e6;
		System.out.printf("%d massifs of 1.6 km2 or more above y %d; by summit height and size:%n", massifs.size(), (int) (63 + MOUNTAIN));
		int rank = 0;
		for (int[] m : massifs.subList(0, Math.min(12, massifs.size()))) {
			rank++;
			double sx = 0, sz = 0, sxx = 0, szz = 0, sxz = 0;
			int count = 0;
			for (int c = 0; c < n * n; c++) {
				if (label[c] == m[0]) {
					double x = x0 + (c % n) * STEP;
					double z = z0 + (c / n) * STEP;
					sx += x;
					sz += z;
					sxx += x * x;
					szz += z * z;
					sxz += x * z;
					count++;
				}
			}
			double cx = sx / count;
			double cz = sz / count;
			double vxx = sxx / count - cx * cx;
			double vzz = szz / count - cz * cz;
			double vxz = sxz / count - cx * cz;
			double tr = vxx + vzz;
			double det = vxx * vzz - vxz * vxz;
			double l1 = tr / 2 + Math.sqrt(Math.max(0, tr * tr / 4 - det));
			double l2 = tr / 2 - Math.sqrt(Math.max(0, tr * tr / 4 - det));
			// a uniform band of length L has a variance of L^2/12 along it
			double length = Math.sqrt(12 * l1) / 1000.0;
			double width = Math.sqrt(12 * Math.max(l2, 1.0)) / 1000.0;
			double angle = Math.toDegrees(0.5 * Math.atan2(2 * vxz, vxx - vzz));
			Summit top = refine(world, x0 + (m[3] % n) * STEP, z0 + (m[3] / n) * STEP);
			String biome = resolver.getNoiseBiome(Math.floorDiv(top.x, 4), Math.floorDiv(top.topY + 4, 4), Math.floorDiv(top.z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			System.out.printf("  #%d: summit y %d at x %d, z %d (%s), %s; massif %.0f km2 (%.1f km2 of ground over y %d), about %.0f km long and %.0f km wide, running %s; middle at x %.0f, z %.0f, %s%n", rank,
				top.topY, top.x, top.z, biome, where(top.x - fromX, top.z - fromZ), count * cellKm2, m[2] * cellKm2, (int) (63 + HIGH), length, width, axis(angle), cx, cz, where(cx - fromX, cz - fromZ));
		}

		// the individual summits, 3 km apart at least
		List<int[]> peaks = new ArrayList<>();
		int r = 7;
		for (int j = r; j < n - r; j++) {
			for (int i = r; i < n - r; i++) {
				float v = g[j * n + i];
				if (v < (float) (MOUNTAIN + 100.0)) {
					continue;
				}
				boolean top = true;
				for (int dj = -r; dj <= r && top; dj++) {
					for (int di = -r; di <= r; di++) {
						if (g[(j + dj) * n + i + di] > v) {
							top = false;
							break;
						}
					}
				}
				if (top) {
					peaks.add(new int[] { i, j });
				}
			}
		}
		peaks.sort(Comparator.comparingDouble((int[] p) -> -g[p[1] * n + p[0]]));
		List<Summit> summits = new ArrayList<>();
		for (int[] p : peaks) {
			Summit s = refine(world, x0 + p[0] * STEP, z0 + p[1] * STEP);
			boolean apart = true;
			for (Summit o : summits) {
				apart &= Math.hypot(o.x - s.x, o.z - s.z) >= 3000.0;
			}
			if (apart) {
				summits.add(s);
			}
			if (summits.size() == 12) {
				break;
			}
		}
		System.out.println("the 12 highest separate summits (3 km apart at least):");
		for (Summit s : summits) {
			String biome = resolver.getNoiseBiome(Math.floorDiv(s.x, 4), Math.floorDiv(s.topY + 4, 4), Math.floorDiv(s.z, 4)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
			System.out.printf("  y %d at x %d, z %d: %s, %s%n", s.topY, s.x, s.z, biome, where(s.x - fromX, s.z - fromZ));
		}
		double highest = 0;
		for (float v : g) {
			highest = Math.max(highest, v);
		}
		long over = 0;
		long mountain = 0;
		for (float v : g) {
			over += v >= HIGH ? 1 : 0;
			mountain += v >= MOUNTAIN ? 1 : 0;
		}
		System.out.printf("land above y %d: %.0f km2; above y %d: %.0f km2 (of the window's %.0f km2)%n", (int) (63 + MOUNTAIN), mountain * cellKm2, (int) (63 + HIGH), over * cellKm2, n * n * cellKm2);
	}

	/** The top of the ground within a block's reach of a position, found at 4-block steps. */
	private static Summit refine(EarthWorld world, int cx, int cz) {
		double[] out = new double[3];
		int bx = cx;
		int bz = cz;
		double best = -1.0e9;
		for (int dz = -STEP; dz <= STEP; dz += 4) {
			for (int dx = -STEP; dx <= STEP; dx += 4) {
				world.sampleBlock(cx + dx, cz + dz, out);
				if (out[0] > best) {
					best = out[0];
					bx = cx + dx;
					bz = cz + dz;
				}
			}
		}
		return new Summit(bx, bz, Math.min(511, (int) Math.ceil(63 + best) - 1));
	}

	private static String where(double east, double south) {
		double km = Math.hypot(east, south) / 1000.0;
		if (km < 0.3) {
			return "right here";
		}
		double bearing = Math.toDegrees(Math.atan2(east, -south)); // 0 north, 90 east
		String[] names = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
		return String.format("%.1f km %s of you", km, names[(int) Math.floorMod(Math.round(bearing / 45.0), 8)]);
	}

	private static String axis(double angleDegrees) {
		double a = ((angleDegrees % 180) + 180) % 180; // 0 east-west, 90 north-south, counted from +x towards +z (south)
		String[] names = { "east-west", "east-north-east to west-south-west", "south-east to north-west", "north-north-west to south-south-east", "north-south" };
		// from +x towards +z: 45 degrees is a line from north-west to south-east
		if (a < 22.5 || a >= 157.5) {
			return "east-west";
		} else if (a < 67.5) {
			return "north-west to south-east";
		} else if (a < 112.5) {
			return "north-south";
		}
		return "north-east to south-west";
	}
}
