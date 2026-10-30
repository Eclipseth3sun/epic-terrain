package com.epicterrain.worldgen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.epicterrain.EpicTerrain;
import com.epicterrain.earth.BiomeClimate;
import com.epicterrain.earth.Continent;
import com.epicterrain.earth.EarthConfig;
import com.epicterrain.earth.EarthWorld;
import com.epicterrain.earth.EarthWorlds;
import com.epicterrain.earth.Islands;
import com.epicterrain.earth.Wetlands;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The biomes of the Earth world type: the same vanilla table, asked about the numbers the Earth model works out.
 *
 * <p>Vanilla's temperature, humidity and weirdness noise give the regional randomness (they come in through the climate sampler, as vanilla's own source
 * reads them); everything else is the model's: the height of the ground, how far it stands above the lowland around it, the rainfall and the distance to the
 * sea decide the zone and the coast (see {@link BiomeClimate}), a river's water asks for a river, and an island's own numbers are used for its columns
 * (see {@link Islands}). Four things get a biome by name instead: a crater lake, the summit of a young volcano, an atoll's reef and its islets (the three
 * island biomes), and a mushroom island, which is vanilla's mushroom fields because its continentalness is below -1.05.
 *
 * <p><b>Below the surface</b> the table is asked again with a depth, as vanilla does (its depth parameter is 0 at the surface and grows by 3 for every 384
 * blocks down, which the tests of this class measured against vanilla's own function: a depth of 0.2 is 26 blocks down and 0.9 is 115). Vanilla's table puts its
 * lush, dripstone and sulfur caves at depths of 0.2 to 0.9 wherever the humidity, continentalness, erosion and weirdness suit them, and the deep dark from 1.1 under
 * mountains (erosion of -0.2 or less); so the same climate numbers as the surface's decide, and a column below the surface has the cave biome of its depth range or,
 * where the table has none there, the biome of the surface. The depth is measured from the ground of the Earth model, as vanilla's is from its own terrain, and
 * is held at 1.0 above y -10 (where the table has no cave) so that the deep dark stays at the bottom of the world and not inside a tall mountain.
 */
public final class EarthBiomeSource extends BiomeSource {
	public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			MultiNoiseBiomeSource.CODEC.codec().fieldOf("base").forGetter(s -> s.base),
			Biome.CODEC.fieldOf("volcano").forGetter(s -> s.volcano),
			Biome.CODEC.fieldOf("atoll").forGetter(s -> s.atoll),
			Biome.CODEC.fieldOf("crater_lake").forGetter(s -> s.craterLake)
		).apply(i, EarthBiomeSource::new));

	/** Where in a column the climate sampler is asked (the quart y of block 16): temperature, humidity and weirdness do not change with height. */
	private static final int SAMPLE_QUART_Y = 4;
	/** Blocks of depth that make 1.0 of vanilla's depth parameter (3 for every 384 blocks). */
	private static final double BLOCKS_PER_DEPTH = 128.0;
	/** The table is asked for caves at the middle of each tenth of depth from 0.2 to 0.9 (its cave biomes come and go with depth), and for the deep dark at this one (1.1 and more). */
	private static final double DEEP_DEPTH = 1.2;
	private static final double CAVES_FROM = 0.2;
	private static final double CAVES_TO = 0.9;
	private static final double DEEP_FROM = 1.1;
	/** Above this y the depth is held below the deep dark's. */
	private static final double DEEP_DARK_CEILING = -10.0;

	private final MultiNoiseBiomeSource base;
	private final Holder<Biome> volcano;
	private final Holder<Biome> atoll;
	private final Holder<Biome> craterLake;
	private final BiomeClimate.Dials dials = BiomeClimate.Dials.fromProperties();
	private volatile Set<Holder<Biome>> undergroundBiomes;

	public EarthBiomeSource(MultiNoiseBiomeSource base, Holder<Biome> volcano, Holder<Biome> atoll, Holder<Biome> craterLake) {
		this.base = base;
		this.volcano = volcano;
		this.atoll = atoll;
		this.craterLake = craterLake;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		// what the table can answer, and the three island biomes
		return Stream.concat(this.base.possibleBiomes().stream(), Stream.of(this.volcano, this.atoll, this.craterLake)).distinct();
	}

	/** The world a climate sampler works for: ours is behind its continentalness function. */
	private static EarthWorld worldOf(Climate.Sampler sampler) {
		EarthWorld world = EarthField.worldOf(sampler.continentalness().sampler());
		return world != null ? world : EarthWorlds.only();
	}

	/** Whether a biome is one of the underground ones of the table (the caves and the deep dark). */
	private boolean isUnderground(Holder<Biome> biome) {
		Set<Holder<Biome>> known = this.undergroundBiomes;
		if (known == null) {
			known = this.base.possibleBiomes().stream().filter(b -> b.unwrapKey().map(key -> {
				String path = key.identifier().getPath();
				return path.contains("caves") || path.contains("deep_dark");
			}).orElse(false)).collect(Collectors.toUnmodifiableSet());
			this.undergroundBiomes = known;
		}
		return known.contains(biome);
	}

	@SuppressWarnings("unchecked")
	private static Holder<Biome>[] newBins() {
		return new Holder[9];
	}

	/**
	 * What a 4 x 4 block column is made of for the purposes of biomes: the biome of its surface, the climate numbers to ask the table with below it, and how
	 * high the surface is. The biomes of the caves and the deep dark are asked of the table the first time a block below the surface is.
	 */
	private final class Column {
		final Holder<Biome> surface;
		final double surfaceY;
		/** The table's inputs (temperature, humidity, continentalness, erosion, weirdness, and the continentalness to ask with below the surface), or null where the column has one biome all the way down. */
		final float[] climate;
		/** The answers of the table for each tenth of depth from 0.2 (index 2) to 0.9, asked for when they are first needed. */
		private final Holder<Biome>[] caves = newBins();
		private Holder<Biome> deep;

		Column(Holder<Biome> surface, double surfaceY, float[] climate) {
			this.surface = surface;
			this.surfaceY = surfaceY;
			this.climate = climate;
		}

		private Holder<Biome> ask(double depth) {
			Holder<Biome> asked = EarthBiomeSource.this.base.getNoiseBiome(Climate.target(this.climate[0], this.climate[1], this.climate[5], this.climate[3], (float) depth, this.climate[4]));
			return EarthBiomeSource.this.isUnderground(asked) ? asked : this.surface;
		}

		/** The biome at a block height. */
		Holder<Biome> at(double y) {
			if (this.climate == null) {
				return this.surface;
			}
			double depth = (this.surfaceY - y) / BLOCKS_PER_DEPTH;
			if (y > DEEP_DARK_CEILING && depth >= DEEP_FROM - 0.1) {
				depth = DEEP_FROM - 0.1;
			}
			if (depth >= CAVES_FROM && depth <= CAVES_TO) {
				int bin = Math.min(8, (int) (depth * 10.0));
				Holder<Biome> known = this.caves[bin];
				if (known == null) {
					known = this.ask(bin / 10.0 + 0.05);
					this.caves[bin] = known;
				}
				return known;
			}
			if (depth >= DEEP_FROM) {
				if (this.deep == null) {
					this.deep = this.ask(DEEP_DEPTH);
				}
				return this.deep;
			}
			return this.surface;
		}
	}

	/** The biome at any place. */
	private final class Resolver implements BiomeResolver {
		private final EarthWorld world;
		private final Climate.Sampler sampler;

		Resolver(EarthWorld world, Climate.Sampler sampler) {
			this.world = world;
			this.sampler = sampler;
		}

		Column column(int quartX, int quartZ) {
			return EarthBiomeSource.this.columnAt(this.world, this.sampler, quartX, quartZ);
		}

		@Override
		public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
			return this.column(quartX, quartZ).at(quartY * 4 + 2);
		}

		/** The biomes of the cube around a place: each column is worked out once, then asked at each height. */
		@Override
		public Set<Holder<Biome>> getBiomesWithin(int x, int y, int z, int radius) {
			int minY = QuartPos.fromBlock(y - radius);
			int maxY = QuartPos.fromBlock(y + radius);
			Set<Holder<Biome>> out = new HashSet<>();
			for (int qz = QuartPos.fromBlock(z - radius); qz <= QuartPos.fromBlock(z + radius); qz++) {
				for (int qx = QuartPos.fromBlock(x - radius); qx <= QuartPos.fromBlock(x + radius); qx++) {
					Column column = this.column(qx, qz);
					for (int qy = minY; qy <= maxY; qy++) {
						out.add(column.at(qy * 4 + 2));
					}
				}
			}
			return out;
		}
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return new Resolver(worldOf(sampler), sampler);
	}

	/**
	 * Vanilla's search for the nearest biome ({@code /locate biome}) asks about every height of every column of a spiral out to 6.4 km. Here each column is worked
	 * out once and then asked at the heights, which is the same answer for a ninth of the work (the work for a column is the cost, a few microseconds), and the
	 * search stops when {@link SearchBudget} says so, answering as vanilla does when it finds nothing: from the middle of the continent a search for an atoll
	 * would otherwise go on until it had looked everywhere.
	 */
	@Override
	public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalStep, int verticalStep, Predicate<Holder<Biome>> allowed, RandomState randomState,
			LevelReader level) {
		Set<Holder<Biome>> wanted = this.possibleBiomes().stream().filter(allowed).collect(Collectors.toUnmodifiableSet());
		if (wanted.isEmpty()) {
			return null;
		}
		int[] heights = Mth.outFromOrigin(origin.getY(), level.getMinY() + 1, level.getMaxY() + 1, verticalStep).toArray();
		Resolver resolver = (Resolver) this.createCachingResolver(randomState);
		long deadline = System.nanoTime() + SearchBudget.nanos();
		int looked = 0;
		for (BlockPos step : BlockPos.spiralAround(BlockPos.ZERO, Math.floorDiv(radius, horizontalStep), Direction.EAST, Direction.SOUTH)) {
			int x = origin.getX() + step.getX() * horizontalStep;
			int z = origin.getZ() + step.getZ() * horizontalStep;
			Column column = resolver.column(QuartPos.fromBlock(x), QuartPos.fromBlock(z));
			for (int y : heights) {
				Holder<Biome> biome = column.at(QuartPos.fromBlock(y) * 4 + 2);
				if (wanted.contains(biome)) {
					return Pair.of(new BlockPos(x, y, z), biome);
				}
			}
			if ((++looked & 255) == 0 && System.nanoTime() > deadline) {
				EpicTerrain.LOGGER.info("Biome search gave up after {} columns ({} s, -Depicterrain.earth.locateSeconds)", looked, SearchBudget.nanos() / 1e9);
				return null;
			}
		}
		return null;
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int quartSizeX, int quartSizeY, int quartSizeZ) {
		Resolver outside = new Resolver(worldOf(sampler), sampler);
		// each column of the volume is worked out once, however many heights are asked
		Column[] columns = new EarthBiomeSource.Column[quartSizeX * quartSizeZ];
		return (quartX, quartY, quartZ) -> {
			int dx = quartX - minQuartX;
			int dz = quartZ - minQuartZ;
			if (dx < 0 || dz < 0 || dx >= quartSizeX || dz >= quartSizeZ) {
				return outside.getNoiseBiome(quartX, quartY, quartZ);
			}
			int index = dz * quartSizeX + dx;
			Column known = columns[index];
			if (known == null) {
				known = outside.column(quartX, quartZ);
				columns[index] = known;
			}
			return known.at(quartY * 4 + 2);
		};
	}

	/** The column at a 4 x 4 block quart of the world. */
	private Column columnAt(EarthWorld world, Climate.Sampler sampler, int quartX, int quartZ) {
		int x = quartX * 4 + 2;
		int z = quartZ * 4 + 2;
		double[] column = new double[3];
		world.sampleBlock(x, z, column);
		double ground = column[0];
		double surfaceY = EarthConfig.SEA_LEVEL_Y + ground;
		int kind = (int) column[2];
		if (kind == EarthWorld.CRATER) {
			return new Column(this.craterLake, surfaceY, null);
		}
		Islands.Highlight feature = ground > -3.0 ? world.highlight(x, z) : null;
		if (feature != null) {
			return new Column(switch (feature) {
				case VOLCANO -> this.volcano;
				case ATOLL -> this.atoll;
				case CRATER_LAKE -> this.craterLake;
			}, surfaceY, null);
		}
		Climate.TargetPoint noise = sampler.sample(quartX, SAMPLE_QUART_Y, quartZ);
		double t0 = Climate.unquantizeCoord(noise.temperature());
		double h0 = Climate.unquantizeCoord(noise.humidity());
		double w0 = Climate.unquantizeCoord(noise.weirdness());
		BiomeClimate.Cell cell = new BiomeClimate.Cell();
		Islands.Island island = ground > 0.0 ? world.islandAt(x, z) : null;
		long seed = world.config.seed();
		if (island != null) {
			Islands.climate(ground, world.islandCoast(island, x, z), t0, h0, w0, x, z, seed, this.dials, cell);
		} else {
			// a marsh is called a swamp whatever its ground does between the pools and the hummocks: land of the flattest erosion, which vanilla's table answers swamp
			// or, in the warm, mangrove swamp (the same field makes the ground marshy, see Wetlands)
			boolean marsh = kind == EarthWorld.NO_WATER && ground > -8.0 && ground < 12.0 && Wetlands.isMarsh(world.wetland(x, z));
			double heightM = marsh ? Math.max(Continent.metresOf(ground), 60.0) : Continent.metresOf(ground);
			BiomeClimate.cell(heightM, marsh ? 0.0 : world.reliefMetres(x, z), BiomeClimate.climateRain(world.rainMm(x, z)), world.coastBlocks(x, z), kind == EarthWorld.RIVER, t0, h0, w0, x, z, seed,
				marsh ? 0.0 : world.hilliness(x, z), this.dials, cell);
			if (marsh) {
				cell.erosion = 0.72;
				cell.weirdness = (w0 < 0.0 ? -1.0 : 1.0) * 0.30;
				cell.continentalness = Math.max(cell.continentalness, -0.05);
			}
		}
		if (ground > -12.0 && world.mushroom(x, z)) {
			Islands.mushroomClimate(cell);
		}
		double coast = island != null ? world.islandCoast(island, x, z) : world.coastBlocks(x, z);
		float[] climate = { (float) cell.temperature, (float) cell.humidity, (float) cell.continentalness, (float) cell.erosion, (float) cell.weirdness,
			(float) BiomeClimate.undergroundContinentalness(seed, x, z, coast) };
		return new Column(this.base.getNoiseBiome(Climate.target(climate[0], climate[1], climate[2], climate[3], 0.0f, climate[4])), surfaceY, climate);
	}

	@Override
	public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) {
		EarthWorld world = worldOf(sampler);
		if (world == null) {
			result.add("Earth: no world");
			return;
		}
		double[] column = new double[3];
		world.sampleBlock(feetPos.getX(), feetPos.getZ(), column);
		result.add(String.format("Earth: ground %.1f blocks above sea, water %s (%s), relief %.0f m, rain %.0f mm, coast %.0f blocks", column[0],
			Double.isNaN(column[1]) ? "none" : String.format("%.1f", column[1]), new String[] { "none", "river", "lake", "crater lake" }[(int) column[2]],
			world.reliefMetres(feetPos.getX(), feetPos.getZ()), world.rainMm(feetPos.getX(), feetPos.getZ()), world.coastBlocks(feetPos.getX(), feetPos.getZ())));
	}
}
