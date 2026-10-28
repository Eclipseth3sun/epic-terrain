package com.epicterrain.river;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The oceans around a point, at 128-block resolution: which cells are ocean, which belong to a body big enough for a grand river
 * to run to, and how far every cell is from such a body.
 *
 * <p>Ocean is chosen by vanilla from continentalness alone (below -0.19; deep ocean below -0.455), and vanilla oceans are many
 * small patches rather than a few big seas, so what counts as a destination is a size threshold, not a name. Cells under a
 * range's footprint are not ocean: the epic climate removes ocean biomes there.
 */
final class SeaMap {
	static final int CELL = 128;
	/** Continentalness below which vanilla picks an ocean biome. */
	static final double OCEAN = -0.19;

	private final int originX;
	private final int originZ;
	private final int size;
	private final boolean[] ocean;
	private final float[] distance;

	private SeaMap(int originX, int originZ, int size, boolean[] ocean, float[] distance) {
		this.originX = originX;
		this.originZ = originZ;
		this.size = size;
		this.ocean = ocean;
		this.distance = distance;
	}

	/** Maps the square of half-width {@code radius} around a centre; bodies of at least {@code minKm2} are the targets. */
	static SeaMap build(TerrainView terrain, double centerX, double centerZ, double radius, double minKm2) {
		int n = (int) Math.ceil(2 * radius / CELL);
		int originX = (int) Math.floor(centerX - radius);
		int originZ = (int) Math.floor(centerZ - radius);
		boolean[] ocean = new boolean[n * n];
		IntStream.range(0, n).parallel().forEach(row -> {
			for (int col = 0; col < n; col++) {
				double x = originX + col * CELL + CELL / 2.0;
				double z = originZ + row * CELL + CELL / 2.0;
				ocean[row * n + col] = terrain.land(x, z) < OCEAN && terrain.uplift(x, z) < 3.0;
			}
		});

		List<Integer> sizes = new ArrayList<>();
		int[] label = label(ocean, n, sizes);
		double cellKm2 = (CELL / 1000.0) * (CELL / 1000.0);
		boolean[] target = new boolean[n * n];
		for (int i = 0; i < n * n; i++) {
			target[i] = label[i] >= 0 && sizes.get(label[i]) * cellKm2 >= minKm2;
		}
		return new SeaMap(originX, originZ, n, ocean, distanceTo(target, n));
	}

	/** Distance in blocks from a position to the nearest target body (0 inside one); NaN outside the mapped square. */
	double distanceAt(double x, double z) {
		int index = index(x, z);
		return index < 0 ? Double.NaN : this.distance[index];
	}

	/** Is this position in an ocean biome (of any size)? False outside the mapped square. */
	boolean oceanAt(double x, double z) {
		int index = index(x, z);
		return index >= 0 && this.ocean[index];
	}

	private int index(double x, double z) {
		int col = (int) Math.floor((x - this.originX) / CELL);
		int row = (int) Math.floor((z - this.originZ) / CELL);
		if (col < 1 || row < 1 || col >= this.size - 1 || row >= this.size - 1) {
			return -1;
		}
		return row * this.size + col;
	}

	private static int[] label(boolean[] mask, int n, List<Integer> sizes) {
		int[] label = new int[n * n];
		java.util.Arrays.fill(label, -1);
		int[] stack = new int[n * n];
		for (int start = 0; start < n * n; start++) {
			if (!mask[start] || label[start] >= 0) {
				continue;
			}
			int id = sizes.size();
			int top = 0;
			int count = 0;
			stack[top++] = start;
			label[start] = id;
			while (top > 0) {
				int i = stack[--top];
				count++;
				int x = i % n;
				int z = i / n;
				if (x > 0 && mask[i - 1] && label[i - 1] < 0) {
					label[i - 1] = id;
					stack[top++] = i - 1;
				}
				if (x < n - 1 && mask[i + 1] && label[i + 1] < 0) {
					label[i + 1] = id;
					stack[top++] = i + 1;
				}
				if (z > 0 && mask[i - n] && label[i - n] < 0) {
					label[i - n] = id;
					stack[top++] = i - n;
				}
				if (z < n - 1 && mask[i + n] && label[i + n] < 0) {
					label[i + n] = id;
					stack[top++] = i + n;
				}
			}
			sizes.add(count);
		}
		return label;
	}

	/** Chamfer distance in blocks from every cell to the nearest set cell of {@code target}. */
	private static float[] distanceTo(boolean[] target, int n) {
		float inf = 1e9f;
		float s2 = (float) Math.sqrt(2);
		float[] d = new float[n * n];
		for (int i = 0; i < d.length; i++) {
			d[i] = target[i] ? 0 : inf;
		}
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int i = z * n + x;
				float v = d[i];
				if (x > 0) {
					v = Math.min(v, d[i - 1] + 1);
				}
				if (z > 0) {
					v = Math.min(v, d[i - n] + 1);
					if (x > 0) {
						v = Math.min(v, d[i - n - 1] + s2);
					}
					if (x < n - 1) {
						v = Math.min(v, d[i - n + 1] + s2);
					}
				}
				d[i] = v;
			}
		}
		for (int z = n - 1; z >= 0; z--) {
			for (int x = n - 1; x >= 0; x--) {
				int i = z * n + x;
				float v = d[i];
				if (x < n - 1) {
					v = Math.min(v, d[i + 1] + 1);
				}
				if (z < n - 1) {
					v = Math.min(v, d[i + n] + 1);
					if (x < n - 1) {
						v = Math.min(v, d[i + n + 1] + s2);
					}
					if (x > 0) {
						v = Math.min(v, d[i + n - 1] + s2);
					}
				}
				d[i] = v;
			}
		}
		for (int i = 0; i < d.length; i++) {
			d[i] *= CELL;
		}
		return d;
	}
}
