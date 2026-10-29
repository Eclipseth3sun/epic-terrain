package com.epicterrain.earth;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Builds the same world (the seed in {@code -Depicterrain.seed}) with several settings of the rock-layer dials ({@code tilt}, {@code bend}, {@code bendScale}) and writes
 * each one's ground as raw numbers to {@code -Depicterrain.dumpDir}, for drawing side by side. Nothing happens unless that option is given.
 *
 * <p>File format, per variant {@code NAME.bin} (big endian): int half-width in blocks, int step, int n, then n x n of float ground (blocks above sea), float water surface
 * (NaN if none), byte kind, row by row from the north-west corner.
 */
@Tag("preview")
class EarthStreakPreviewTest {
	private static final String[][] VARIANTS = {
		// name, tilt, bend, bendScale
		{ "A_now", "0.35", "1.0", "16000" },
		{ "B_gentler", "0.20", "1.0", "16000" },
		{ "C_bendier", "0.35", "2.0", "16000" },
		{ "D_both", "0.22", "2.0", "16000" },
		{ "E_curvy", "0.22", "3.0", "10000" },
		{ "F_middle", "0.25", "2.5", "12000" },
		{ "G_waves", "0.25", "2.0", "9000" },
	};

	@Test
	void render() throws Exception {
		String dir = System.getProperty("epicterrain.dumpDir");
		if (dir == null) {
			return;
		}
		int half = 24_000;
		int step = 32;
		int n = 2 * half / step;
		new File(dir).mkdirs();
		String only = System.getProperty("epicterrain.variants", "");
		for (String[] v : VARIANTS) {
			if (!only.isEmpty() && !only.contains(v[0].substring(0, 1))) {
				continue; // e.g. -Depicterrain.variants=FG renders only F and G
			}
			System.setProperty("epicterrain.earth.tilt", v[1]);
			System.setProperty("epicterrain.earth.bend", v[2]);
			System.setProperty("epicterrain.earth.bendScale", v[3]);
			EarthWorld world = new EarthWorld(EarthConfig.fromProperties(), System.out::println);
			double[] out = new double[3];
			try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, v[0] + ".bin")), 1 << 20))) {
				data.writeInt(half);
				data.writeInt(step);
				data.writeInt(n);
				for (int j = 0; j < n; j++) {
					for (int i = 0; i < n; i++) {
						world.sample(-half + i * step + step / 2, -half + j * step + step / 2, out);
						data.writeFloat((float) out[0]);
						data.writeFloat((float) out[1]);
						data.writeByte((int) out[2]);
					}
				}
			}
			System.out.println("wrote " + v[0] + " (tilt " + v[1] + ", bend " + v[2] + ", scale " + v[3] + ")");
		}
		System.clearProperty("epicterrain.earth.tilt");
		System.clearProperty("epicterrain.earth.bend");
		System.clearProperty("epicterrain.earth.bendScale");
	}
}
