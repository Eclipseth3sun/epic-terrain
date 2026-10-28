package com.epicterrain.river;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.epicterrain.worldgen.RiverField;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Writes the planned nodes of the rivers inside a rectangle to a file, to compare with what a real world generated. Not run by default. */
@Tag("preview")
class RiverNodesProbe {
	@Test
	void write() throws Exception {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		int percent = Integer.getInteger("epicterrain.amount", 47);
		String[] box = System.getProperty("epicterrain.bbox", "-1536,-1536,1536,1536").split(",");
		double x0 = Double.parseDouble(box[0]);
		double z0 = Double.parseDouble(box[1]);
		double x1 = Double.parseDouble(box[2]);
		double z1 = Double.parseDouble(box[3]);
		Path out = Path.of(System.getProperty("epicterrain.nodesOut", "build/preview/river_nodes.csv"));
		Files.createDirectories(out.getParent());
		RiverTestWorld world = RiverTestWorld.create(seed, percent);
		RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
		service.ensureReady((x0 + x1) / 2, (z0 + z1) / 2, true);
		List<River> rivers = service.finishedPlans().stream().flatMap(p -> p.rivers().stream()).toList();
		int written = 0;
		try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out))) {
			writer.println("river;kind;i;x;z;ground;water;width;depth");
			for (River river : rivers.stream().sorted(Comparator.comparing(River::id)).toList()) {
				for (int i = 0; i < river.nodes().size(); i += 3) {
					River.Node n = river.nodes().get(i);
					if (n.x() >= x0 && n.x() < x1 && n.z() >= z0 && n.z() < z1) {
						writer.printf(java.util.Locale.ROOT, "%s;%s;%d;%.1f;%.1f;%.0f;%.2f;%.1f;%.1f%n", river.id(), river.kind(), i, n.x(), n.z(), n.ground(), n.water(), n.width(), n.depth());
						written++;
					}
				}
			}
		}
		System.out.printf("[nodes] %d planned rivers in all; wrote %d nodes inside the box to %s%n", rivers.size(), written, out);
	}
}
