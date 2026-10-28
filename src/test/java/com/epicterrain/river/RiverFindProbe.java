package com.epicterrain.river;

import java.util.Comparator;
import java.util.List;

import com.epicterrain.worldgen.RiverField;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lists the planned rivers nearest the origin of a world, with coordinates to teleport to. Not part of the normal test run.
 *
 * <pre>./gradlew preview --tests com.epicterrain.river.RiverFindProbe -Depicterrain.seed=-679957963969313949 -Depicterrain.amount=47</pre>
 */
@Tag("preview")
class RiverFindProbe {
	@Test
	void list() {
		long seed = Long.getLong("epicterrain.seed", 4242L);
		int percent = Integer.getInteger("epicterrain.amount", 47);
		double cx = Double.parseDouble(System.getProperty("epicterrain.cx", "0"));
		double cz = Double.parseDouble(System.getProperty("epicterrain.cz", "0"));
		RiverTestWorld world = RiverTestWorld.create(seed, percent);
		RiverService service = RiverField.serviceOf(world.state, world.settings.noiseRouter().temperature());
		service.ensureReady(cx, cz, true);
		List<River> rivers = service.finishedPlans().stream().flatMap(p -> p.rivers().stream()).toList();

		System.out.printf("[find] seed %d, slider %d%%: %d planned rivers around (%.0f, %.0f)%n", seed, percent, rivers.size(), cx, cz);
		System.out.println("[find] GRAND rivers, nearest first (tp to the source for the falls, to the mouth for the sea):");
		rivers.stream().filter(r -> r.kind() == River.Kind.GRAND).sorted(Comparator.comparingDouble(r -> dist(r.source(), cx, cz))).limit(8).forEach(r ->
			System.out.printf("[find]   %s: %.1f km long, source (%.0f, %.0f) y%.0f, mouth (%.0f, %.0f), %d falls%n", r.id(), r.length() / 1000, r.source().x(), r.source().z(),
				r.source().ground(), r.mouth().x(), r.mouth().z(), r.falls().size()));
		System.out.println("[find] TRIBUTARIES with the tallest total fall, within 6 km of the origin:");
		rivers.stream().filter(r -> r.kind() == River.Kind.TRIBUTARY && dist(r.source(), cx, cz) < 6000)
			.sorted(Comparator.comparingDouble((River r) -> r.source().water() - r.mouth().water()).reversed()).limit(6).forEach(r ->
				System.out.printf("[find]   %s: %.0f m long, falls %.0f blocks from (%.0f, %.0f) y%.0f to (%.0f, %.0f)%n", r.id(), r.length(), r.source().water() - r.mouth().water(),
					r.source().x(), r.source().z(), r.source().ground(), r.mouth().x(), r.mouth().z()));
		System.out.println("[find] LONGEST tributaries within 6 km of the origin:");
		rivers.stream().filter(r -> r.kind() == River.Kind.TRIBUTARY && dist(r.source(), cx, cz) < 6000)
			.sorted(Comparator.comparingDouble(River::length).reversed()).limit(5).forEach(r ->
				System.out.printf("[find]   %s: %.0f m long, source (%.0f, %.0f) y%.0f, mouth (%.0f, %.0f)%n", r.id(), r.length(), r.source().x(), r.source().z(), r.source().ground(),
					r.mouth().x(), r.mouth().z()));
	}

	private static double dist(River.Node node, double x, double z) {
		return Math.hypot(node.x() - x, node.z() - z);
	}
}
