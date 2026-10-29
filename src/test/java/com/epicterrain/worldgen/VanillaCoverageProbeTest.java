package com.epicterrain.worldgen;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.epicterrain.WorldgenHarness;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;
import com.mojang.datafixers.DataFixer;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Whether everything vanilla puts in the overworld can happen in an Earth world: every overworld biome, every structure, every feature the biomes carry. Nothing runs
 * unless {@code -Depicterrain.coverage=SEED[,SEED...]} is given (run it with {@code ./gradlew preview --tests *VanillaCoverageProbeTest -Depicterrain.coverage=4242}); each seed
 * simulates a full 37.5 km world (about a minute) and writes {@code vanilla_coverage_SEED.txt} to the preview folder.
 *
 * <p><b>Biomes</b> are counted from the biome source over the whole window (surface, and underground at several depths) and over 100 km round it. <b>Structures</b> are
 * generated for real: the candidate chunks of each structure set, nearest to the spawn first, go through the generator's {@code createStructures} with the game's own template
 * manager, until each structure whose biomes occur has been seen once or the search to 150 km ends. That proves the start (biome, ground, template pool, bounding box); it does not
 * place the pieces. <b>Features</b> are not run (a feature needs a live level); what is checked is that every placed feature of every vanilla overworld biome belongs to a biome
 * that occurs.
 */
@Tag("preview")
class VanillaCoverageProbeTest {
	private static final LevelHeightAccessor WORLD = LevelHeightAccessor.create(-64, 576);
	/** How far the structure search goes, in blocks, and the steps it takes there (each ring only visits what the last did not). */
	private static final int[] RINGS = { 8_000, 20_000, 40_000, 80_000, 150_000 };
	private static final int WANT_PER_STRUCTURE = 1;

	private static final class Tally {
		long count;
		double nearest = Double.MAX_VALUE;
		int x, y, z;

		void add(int x, int y, int z) {
			this.count++;
			double d = Math.hypot(x, z);
			if (d < this.nearest) {
				this.nearest = d;
				this.x = x;
				this.y = y;
				this.z = z;
			}
		}
	}

	@Test
	void audit() throws Exception {
		String seeds = System.getProperty("epicterrain.coverage");
		if (seeds == null) {
			return;
		}
		for (String s : seeds.split(",")) {
			new Probe(Long.parseLong(s.trim())).run();
			EarthWorlds.clear();
			System.gc();
		}
	}

	private static String name(Holder<?> holder) {
		return holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
	}

	private static final class Probe {
		final long seed;
		final StringBuilder report = new StringBuilder();
		final WorldgenHarness harness = WorldgenHarness.shared();
		EarthChunkGenerator generator;
		RandomState state;
		EarthWorld world;
		BiomeResolver resolver;
		final Map<String, Tally> surface = new TreeMap<>();
		final Map<String, Tally> underground = new TreeMap<>();
		final Map<String, Tally> beyond = new TreeMap<>();

		Probe(long seed) {
			this.seed = seed;
		}

		void say(String format, Object... args) {
			String line = String.format(format, args);
			this.report.append(line).append('\n');
			System.out.println(line);
		}

		void run() throws Exception {
			long started = System.nanoTime();
			WorldPreset preset = this.harness.registries.lookupOrThrow(Registries.WORLD_PRESET)
				.getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("epicterrain:earth"))).value();
			this.generator = (EarthChunkGenerator) preset.overworld().orElseThrow().generator();
			NoiseGeneratorSettings settings = this.generator.generatorSettings().value();
			this.state = this.harness.randomState(this.seed, settings);
			this.generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, WORLD, this.state);
			this.world = this.generator.world();
			Climate.Sampler sampler = this.state.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
			this.resolver = this.generator.getBiomeSource().createResolver(sampler);
			double[] w = this.world.window();
			this.say("=== world seed %d: window x %.0f..%.0f, z %.0f..%.0f (simulated in %.0f s)", this.seed, w[0], w[1], w[2], w[3], (System.nanoTime() - started) / 1e9);

			List<Holder.Reference<Biome>> expected = this.harness.registries.lookupOrThrow(Registries.BIOME).listElements().filter(h -> h.is(BiomeTags.IS_OVERWORLD)).toList();
			this.census(w);
			Set<String> occurring = new TreeSet<>();
			occurring.addAll(this.surface.keySet());
			occurring.addAll(this.underground.keySet());
			occurring.addAll(this.beyond.keySet());
			this.biomeReport(expected, w);
			this.featureReport(expected, occurring);
			this.structureReport(expected, occurring);
			this.say("=== done in %.0f s", (System.nanoTime() - started) / 1e9);

			Path dir = Path.of(System.getProperty("epicterrain.previewDir", "build/preview"));
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("vanilla_coverage_" + this.seed + ".txt"), this.report.toString());
		}

		// ---- biomes

		private String biomeAt(int x, int y, int z) {
			return name(this.resolver.getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4)));
		}

		private final long[][] floorBins = new long[2][8];
		private static final int[] FLOOR_EDGES = { 2, 10, 28, 60, 100, 127, 200 };

		private void floorBin(int which, double ground) {
			if (ground >= 0.0) {
				return;
			}
			int i = 0;
			while (i < FLOOR_EDGES.length && -ground >= FLOOR_EDGES[i]) {
				i++;
			}
			this.floorBins[which][i]++;
		}

		private void census(double[] w) {
			double[] out = new double[3];
			// the surface over the window, every 64 blocks
			for (int x = (int) w[0]; x < w[1]; x += 64) {
				for (int z = (int) w[2]; z < w[3]; z += 64) {
					this.world.sampleBlock(x, z, out);
					this.floorBin(0, out[0]);
					int y = (int) Math.ceil(EarthField.SEA_LEVEL_Y + Math.max(out[0], -30.0)) + 4;
					this.surface.computeIfAbsent(this.biomeAt(x, y, z), k -> new Tally()).add(x, y, z);
				}
			}
			// underground: at several depths below the ground, and near the bottom of the world, every 128 blocks
			int[] depths = { 25, 45, 65, 90, 120, 160, 220, 300 };
			for (int x = (int) w[0]; x < w[1]; x += 128) {
				for (int z = (int) w[2]; z < w[3]; z += 128) {
					this.world.sampleBlock(x, z, out);
					int ground = (int) Math.ceil(EarthField.SEA_LEVEL_Y + out[0]);
					for (int d : depths) {
						int y = ground - d;
						if (y > -60) {
							this.underground.computeIfAbsent(this.biomeAt(x, y, z), k -> new Tally()).add(x, y, z);
						}
					}
					this.underground.computeIfAbsent(this.biomeAt(x, -45, z), k -> new Tally()).add(x, -45, z);
				}
			}
			// round the window: the deep ocean and its islands
			for (int x = -100_000; x < 100_000; x += 256) {
				for (int z = -100_000; z < 100_000; z += 256) {
					if (x >= w[0] && x < w[1] && z >= w[2] && z < w[3]) {
						continue;
					}
					this.world.sampleBlock(x, z, out);
					this.floorBin(1, out[0]);
					int y = (int) Math.ceil(EarthField.SEA_LEVEL_Y + Math.max(out[0], -30.0)) + 4;
					this.beyond.computeIfAbsent(this.biomeAt(x, y, z), k -> new Tally()).add(x, y, z);
				}
			}
		}

		private void biomeReport(List<Holder.Reference<Biome>> expected, double[] w) {
			long windowCells = this.surface.values().stream().mapToLong(t -> t.count).sum();
			this.say("");
			this.say("BIOMES (surface cells are 64 x 64 blocks over the window; underground and beyond are samples; nearest = to the spawn, as x z)");
			this.say("%-34s %9s %7s %9s %9s  %s", "biome", "surface", "share", "under", "beyond", "nearest occurrence");
			List<String> missing = new ArrayList<>();
			for (Holder.Reference<Biome> h : expected.stream().sorted(Comparator.comparing(VanillaCoverageProbeTest::name)).toList()) {
				String id = name(h);
				Tally s = this.surface.get(id);
				Tally u = this.underground.get(id);
				Tally b = this.beyond.get(id);
				Tally nearest = null;
				for (Tally t : new Tally[] { s, u, b }) {
					if (t != null && (nearest == null || t.nearest < nearest.nearest)) {
						nearest = t;
					}
				}
				if (nearest == null) {
					missing.add(id);
				}
				this.say("%-34s %9d %6.2f%% %9d %9d  %s", id, s == null ? 0 : s.count, s == null ? 0.0 : 100.0 * s.count / windowCells, u == null ? 0 : u.count, b == null ? 0 : b.count,
					nearest == null ? "NEVER SEEN" : String.format("%d %d %d (%.1f km)", nearest.x, nearest.y, nearest.z, nearest.nearest / 1000.0));
			}
			Set<String> unexpected = new TreeSet<>();
			Set<String> expectedNames = new HashSet<>();
			expected.forEach(h -> expectedNames.add(name(h)));
			for (Map<String, Tally> m : List.of(this.surface, this.underground, this.beyond)) {
				for (String id : m.keySet()) {
					if (!expectedNames.contains(id)) {
						unexpected.add(id);
					}
				}
			}
			String[] labels = { "<2", "2-10", "10-28", "28-60", "60-100", "100-127", "127-200", ">=200" };
			for (int which = 0; which < 2; which++) {
				StringBuilder bins = new StringBuilder();
				for (int i = 0; i < labels.length; i++) {
					bins.append(String.format(" %s:%d", labels[i], this.floorBins[which][i]));
				}
				this.say("sea floor depth in blocks below sea level, %s (samples):%s", which == 0 ? "window" : "beyond", bins);
			}
			this.say("overworld biomes never seen: %s", missing.isEmpty() ? "none" : missing);
			this.say("biomes seen that vanilla's overworld does not have: %s", unexpected.isEmpty() ? "none" : unexpected);
		}

		// ---- features

		private Set<String> featuresOf(Holder<Biome> biome) {
			Set<String> out = new TreeSet<>();
			for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
				step.forEach(f -> out.add(name(f)));
			}
			return out;
		}

		private void featureReport(List<Holder.Reference<Biome>> expected, Set<String> occurring) {
			Set<String> all = new TreeSet<>();
			Set<String> present = new TreeSet<>();
			Map<String, List<String>> carriers = new HashMap<>();
			for (Holder.Reference<Biome> h : expected) {
				String id = name(h);
				for (String f : this.featuresOf(h)) {
					all.add(f);
					carriers.computeIfAbsent(f, k -> new ArrayList<>()).add(id);
					if (occurring.contains(id)) {
						present.add(f);
					}
				}
			}
			Set<String> missing = new TreeSet<>(all);
			missing.removeAll(present);
			this.say("");
			this.say("FEATURES: the overworld biomes carry %d placed features; the biomes that occur here carry %d of them", all.size(), present.size());
			if (missing.isEmpty()) {
				this.say("every placed feature of every overworld biome is carried by a biome that occurs");
			}
			for (String f : missing) {
				this.say("  missing %s (only in %s)", f, carriers.get(f));
			}
		}

		// ---- structures

		private StructureManager structureManager() {
			ProtoChunk empty = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, WORLD, PalettedContainerFactory.create(this.harness.registries), null);
			LevelAccessor level = (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(), new Class<?>[] { LevelAccessor.class }, (proxy, method, args) -> {
				Class<?> type = method.getReturnType();
				if (method.getName().equals("getChunk") && type.isAssignableFrom(ProtoChunk.class)) {
					return empty;
				}
				return type == boolean.class ? (Object) false : type == int.class ? (Object) 0 : type == long.class ? (Object) 0L : type == float.class ? (Object) 0f
					: type == double.class ? (Object) 0.0 : null;
			});
			return new StructureManager(level, new WorldOptions(this.seed, true, false), null);
		}

		private static final class Found {
			int x, z, pieces;
			double distance;
			String biome;
			String box;
		}

		private void structureReport(List<Holder.Reference<Biome>> expected, Set<String> occurring) throws IOException {
			Set<Holder<Biome>> expectedSet = new HashSet<>(expected);
			ChunkGeneratorStructureState structureState = this.generator.createState(this.harness.registries.lookupOrThrow(Registries.STRUCTURE_SET), this.state, this.seed);
			structureState.ensureStructuresGenerated();
			Path temp = Files.createTempDirectory("epicterrain-coverage");
			LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(temp).createAccess("coverage");
			DataFixer fixer = DataFixers.getDataFixer();
			StructureTemplateManager templates = new StructureTemplateManager(this.harness.resourceManager(), access, fixer, BuiltInRegistries.BLOCK);
			StructureManager manager = this.structureManager();
			PalettedContainerFactory factory = PalettedContainerFactory.create(this.harness.registries);
			Map<String, Found> found = new TreeMap<>();
			Map<String, String> notes = new TreeMap<>();
			Map<String, Integer> errors = new TreeMap<>();
			this.say("");
			this.say("STRUCTURES (generated for real by createStructures, nearest candidates to the spawn first, up to 150 km)");

			List<Holder<StructureSet>> sets = new ArrayList<>(structureState.possibleStructureSets());
			sets.sort(Comparator.comparing(VanillaCoverageProbeTest::name));
			for (Holder<StructureSet> set : sets) {
				StructurePlacement placement = set.value().placement();
				List<Holder<Structure>> wanted = new ArrayList<>();
				for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
					Holder<Structure> structure = entry.structure();
					boolean inOverworld = structure.value().biomes().stream().anyMatch(expectedSet::contains);
					boolean reachable = structure.value().biomes().stream().anyMatch(b -> occurring.contains(name(b)));
					if (!inOverworld) {
						continue; // the nether's and the end's
					}
					if (!reachable) {
						notes.put(name(structure), "none of its biomes occurs");
						continue;
					}
					wanted.add(structure);
				}
				if (wanted.isEmpty()) {
					continue;
				}
				Set<String> need = new TreeSet<>();
				wanted.forEach(s -> need.add(name(s)));
				int examined = 0;
				if (placement instanceof ConcentricRingsStructurePlacement rings) {
					List<ChunkPos> positions = new ArrayList<>(structureState.getRingPositionsFor(rings));
					positions.sort(Comparator.comparingDouble(c -> Math.hypot(c.x() * 16 + 8, c.z() * 16 + 8)));
					notes.put("minecraft:stronghold", positions.size() + " ring positions, nearest at " + (positions.isEmpty() ? "-" : String.format("%d %d", positions.get(0).x() * 16 + 8, positions.get(0).z() * 16 + 8)));
					for (ChunkPos c : positions) {
						examined++;
						this.tryChunk(c, structureState, manager, templates, factory, found, errors, need);
						if (found.keySet().containsAll(need) || examined >= 12) {
							break;
						}
					}
				} else if (placement instanceof RandomSpreadStructurePlacement spread) {
					int spacing = spread.spacing();
					long levelSeed = structureState.getLevelSeed();
					int doneRadius = 0;
					rings:
					for (int radius : RINGS) {
						int from = doneRadius / 16 / spacing - 1;
						int to = radius / 16 / spacing + 1;
						List<ChunkPos> candidates = new ArrayList<>();
						for (int rx = -to; rx <= to; rx++) {
							for (int rz = -to; rz <= to; rz++) {
								if (Math.abs(rx) < from && Math.abs(rz) < from) {
									continue;
								}
								ChunkPos c = spread.getPotentialStructureChunk(levelSeed, rx * spacing, rz * spacing);
								if (Math.hypot(c.x() * 16 + 8, c.z() * 16 + 8) > radius || Math.hypot(c.x() * 16 + 8, c.z() * 16 + 8) <= doneRadius) {
									continue;
								}
								if (placement.isStructureChunk(structureState, c.x(), c.z())) {
									candidates.add(c);
								}
							}
						}
						candidates.sort(Comparator.comparingDouble(c -> Math.hypot(c.x() * 16 + 8, c.z() * 16 + 8)));
						for (ChunkPos c : candidates) {
							examined++;
							this.tryChunk(c, structureState, manager, templates, factory, found, errors, need);
							if (found.keySet().containsAll(need)) {
								break rings;
							}
						}
						doneRadius = radius;
					}
				}
				for (String id : need) {
					if (!found.containsKey(id)) {
						notes.putIfAbsent(id, "NOT FOUND after " + examined + " candidate chunks of its set " + name(set));
					}
				}
			}

			this.say("%-44s %-10s %s", "structure", "nearest", "start (x z), pieces, biome");
			List<String> missing = new ArrayList<>();
			for (Holder.Reference<Structure> h : this.harness.registries.lookupOrThrow(Registries.STRUCTURE).listElements().sorted(Comparator.comparing(VanillaCoverageProbeTest::name)).toList()) {
				String id = name(h);
				boolean inOverworld = h.value().biomes().stream().anyMatch(expectedSet::contains);
				if (!inOverworld) {
					continue;
				}
				Found f = found.get(id);
				if (f != null) {
					this.say("%-44s %6.1f km  %d %d, %d pieces, %s, box %s", id, f.distance / 1000.0, f.x, f.z, f.pieces, f.biome, f.box);
				} else {
					missing.add(id);
					this.say("%-44s %-10s %s", id, "NOT SEEN", notes.getOrDefault(id, "(its set was not reached)"));
				}
			}
			if (notes.containsKey("minecraft:stronghold")) {
				this.say("stronghold: %s", notes.get("minecraft:stronghold"));
			}
			this.say("overworld structures not seen: %s", missing.isEmpty() ? "none" : missing);
			if (!errors.isEmpty()) {
				this.say("errors while generating starts:");
				errors.forEach((k, v) -> this.say("  %d x %s", v, k));
			}
			access.close();
		}

		private void tryChunk(ChunkPos c, ChunkGeneratorStructureState structureState, StructureManager manager, StructureTemplateManager templates, PalettedContainerFactory factory,
				Map<String, Found> found, Map<String, Integer> errors, Set<String> need) {
			ProtoChunk chunk = new ProtoChunk(c, UpgradeData.EMPTY, WORLD, factory, null);
			try {
				this.generator.createStructures(this.harness.allRegistries, structureState, manager, chunk, templates, Level.OVERWORLD);
			} catch (Throwable t) {
				errors.merge(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse(""), 1, Integer::sum);
				return;
			}
			for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
				StructureStart start = entry.getValue();
				if (!start.isValid()) {
					continue;
				}
				String id = this.harness.registries.lookupOrThrow(Registries.STRUCTURE).wrapAsHolder(entry.getKey()).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
				if (found.containsKey(id)) {
					continue;
				}
				Found f = new Found();
				f.x = c.x() * 16 + 8;
				f.z = c.z() * 16 + 8;
				f.distance = Math.hypot(f.x, f.z);
				f.pieces = start.getPieces().size();
				f.biome = name(this.resolver.getNoiseBiome(Math.floorDiv(start.getBoundingBox().getCenter().getX(), 4), Math.floorDiv(start.getBoundingBox().getCenter().getY(), 4),
					Math.floorDiv(start.getBoundingBox().getCenter().getZ(), 4)));
				f.box = start.getBoundingBox().getXSpan() + "x" + start.getBoundingBox().getYSpan() + "x" + start.getBoundingBox().getZSpan() + " y" + start.getBoundingBox().minY();
				found.put(id, f);
			}
		}
	}
}
