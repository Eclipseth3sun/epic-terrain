package com.epicterrain.worldgen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Vanilla's multi-noise overworld biome source plus "epic zones", so vanilla's own {@code /locate biome} can find epic
 * terrain features, and so every block of epic terrain carries an epic biome label.
 *
 * <p>Terrain and biomes are computed separately, and the biome source only ever sees six climate numbers. The climate
 * functions therefore smuggle a zone number through the temperature channel: {@code temperature + ZONE_STEP * zone}.
 * Vanilla's temperatures stay within about +-2, so the zone is recovered by rounding. The marker is stripped before vanilla
 * picks a biome. Then the zone decides what to do with vanilla's answer:
 *
 * <ul>
 *   <li>a <em>summit</em> zone swaps a few vanilla biomes (the peaks) for one epic biome;</li>
 *   <li>a <em>variants</em> zone swaps each vanilla biome for its own labelled twin, which is an exact clone, so the land
 *       looks and behaves as vanilla but is marked as epic (this is how the foothills are labelled).</li>
 * </ul>
 *
 * <p>Anything vanilla chose that a zone has no rule for (caves, oceans, ...) is left alone. Zone {@code n} (1-based) is
 * {@code zones[n - 1]}.
 */
public final class EpicBiomeSource extends BiomeSource {
	/** Distance between zone codes in the temperature channel. */
	public static final float ZONE_STEP = 8.0f;

	/** One twin: where vanilla would put {@code from}, an epic zone puts {@code to}. */
	public record Variant(Holder<Biome> from, Holder<Biome> to) {
		static final Codec<Variant> CODEC = RecordCodecBuilder.create(i -> i.group(
				Biome.CODEC.fieldOf("from").forGetter(Variant::from),
				Biome.CODEC.fieldOf("to").forGetter(Variant::to)
			).apply(i, Variant::new));
	}

	/** What an epic zone does to vanilla's biome. */
	public record Zone(Optional<HolderSet<Biome>> replaces, Optional<Holder<Biome>> biome, List<Variant> variants) {
		private static final Codec<Zone> OBJECT_CODEC = RecordCodecBuilder.create(i -> i.group(
				RegistryCodecs.holderSet(Registries.BIOME).optionalFieldOf("replaces").forGetter(Zone::replaces),
				Biome.CODEC.optionalFieldOf("biome").forGetter(Zone::biome),
				Variant.CODEC.listOf().optionalFieldOf("variants", List.of()).forGetter(Zone::variants)
			).apply(i, Zone::new));

		/** Worlds saved by earlier builds list zones as bare biome ids and keep one shared "replaces" at the top level. */
		private static final Codec<Zone> LEGACY_CODEC = Biome.CODEC.xmap(
			biome -> new Zone(Optional.empty(), Optional.of(biome), List.of()), zone -> zone.biome().orElseThrow());

		static final Codec<Zone> CODEC = Codec.withAlternative(OBJECT_CODEC, LEGACY_CODEC);

		Stream<Holder<Biome>> biomes() {
			return Stream.concat(this.biome.stream(), this.variants.stream().map(Variant::to));
		}
	}

	public static final MapCodec<EpicBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			MultiNoiseBiomeSource.CODEC.codec().fieldOf("base").forGetter(s -> s.base),
			RegistryCodecs.holderSet(Registries.BIOME).optionalFieldOf("replaces").forGetter(s -> s.legacyReplaces),
			Zone.CODEC.listOf().fieldOf("zones").forGetter(s -> s.zones)
		).apply(i, EpicBiomeSource::new));

	private final MultiNoiseBiomeSource base;
	private final Optional<HolderSet<Biome>> legacyReplaces;
	private final List<Zone> zones;
	private final List<Map<Holder<Biome>, Holder<Biome>>> variantMaps;
	/** The first zone's relabelling that has any, used for land a summit zone does not claim; null if there is none. */
	private final Map<Holder<Biome>, Holder<Biome>> fallbackVariants;

	public EpicBiomeSource(MultiNoiseBiomeSource base, Optional<HolderSet<Biome>> legacyReplaces, List<Zone> zones) {
		this.base = base;
		this.legacyReplaces = legacyReplaces;
		this.zones = List.copyOf(zones);
		this.variantMaps = this.zones.stream().map(zone -> {
			Map<Holder<Biome>, Holder<Biome>> map = new HashMap<>();
			zone.variants().forEach(v -> map.put(v.from(), v.to()));
			return map;
		}).toList();
		this.fallbackVariants = this.variantMaps.stream().filter(map -> !map.isEmpty()).findFirst().orElse(null);
	}

	public MultiNoiseBiomeSource base() {
		return this.base;
	}

	/** Zone number encoded in a temperature value (0 for plain vanilla terrain). */
	public static int zoneOf(float markedTemperature) {
		return Math.max(0, Math.round(markedTemperature / ZONE_STEP));
	}

	/** The real temperature once the zone marker is removed. */
	public static float temperatureOf(float markedTemperature) {
		return markedTemperature - ZONE_STEP * zoneOf(markedTemperature);
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(this.base.possibleBiomes().stream(), this.zones.stream().flatMap(Zone::biomes));
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		return (quartX, quartY, quartZ) -> this.resolve(sampler.sample(quartX, quartY, quartZ));
	}

	@Override
	public BiomeResolver createResolverForChunk(
		Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int quartSizeX, int quartSizeY, int quartSizeZ
	) {
		// Same batched sampling as vanilla's multi-noise source, but through resolve() so zones are honoured.
		DensityVolume volume = new DensityVolume(
			quartSizeX, quartSizeY, quartSizeZ, QuartPos.toBlock(minQuartX), QuartPos.toBlock(minQuartY), QuartPos.toBlock(minQuartZ), 4, 4, 4);
		DensityBuffer temperature = DensityBuffer.createUnpooled(volume.size());
		DensityBuffer vegetation = DensityBuffer.createUnpooled(volume.size());
		DensityBuffer continents = DensityBuffer.createUnpooled(volume.size());
		DensityBuffer erosion = DensityBuffer.createUnpooled(volume.size());
		DensityBuffer depth = DensityBuffer.createUnpooled(volume.size());
		DensityBuffer ridges = DensityBuffer.createUnpooled(volume.size());
		sampler.temperature().sampleVolume(temperature, volume);
		sampler.humidity().sampleVolume(vegetation, volume);
		sampler.continentalness().sampleVolume(continents, volume);
		sampler.erosion().sampleVolume(erosion, volume);
		sampler.depth().sampleVolume(depth, volume);
		sampler.weirdness().sampleVolume(ridges, volume);
		return (quartX, quartY, quartZ) -> {
			int index = volume.indexUnchecked(quartX - minQuartX, quartY - minQuartY, quartZ - minQuartZ);
			return this.resolve(Climate.target(
				temperature.get(index), vegetation.get(index), continents.get(index), erosion.get(index), depth.get(index), ridges.get(index)));
		};
	}

	/** The foothill biome that stands in for a vanilla biome, or null if that biome (ocean, river, cave, ...) has none. */
	public Holder<Biome> foothillFor(Holder<Biome> vanilla) {
		return this.fallbackVariants == null ? null : this.fallbackVariants.get(vanilla);
	}

	/** The climate point with any zone marker removed: what vanilla's own source would be asked. */
	public static Climate.TargetPoint withoutZone(Climate.TargetPoint point) {
		float temperature = Climate.unquantizeCoord(point.temperature());
		if (zoneOf(temperature) == 0) {
			return point;
		}
		return new Climate.TargetPoint(
			Climate.quantizeCoord(temperatureOf(temperature)), point.humidity(), point.continentalness(), point.erosion(), point.depth(), point.weirdness());
	}

	/** What vanilla's multi-noise source answers for this point, before any epic zone has its say. */
	public Holder<Biome> vanillaBiome(Climate.TargetPoint point) {
		return this.base.getNoiseBiome(withoutZone(point));
	}

	/** Picks the biome for a climate point whose temperature may carry a zone marker. */
	public Holder<Biome> resolve(Climate.TargetPoint point) {
		int zone = zoneOf(Climate.unquantizeCoord(point.temperature()));
		Holder<Biome> vanilla = this.vanillaBiome(point);
		if (zone == 0 || zone > this.zones.size()) {
			return vanilla;
		}
		Zone rule = this.zones.get(zone - 1);
		if (rule.biome().isPresent()) {
			Optional<HolderSet<Biome>> replaces = rule.replaces().or(() -> this.legacyReplaces);
			if (replaces.isPresent() && replaces.get().contains(vanilla)) {
				return rule.biome().get();
			}
		}
		Holder<Biome> twin = this.variantMaps.get(zone - 1).get(vanilla);
		if (twin == null && this.fallbackVariants != null) {
			// A summit zone only claims vanilla's peak biomes; any other land inside it is still epic, so it gets the
			// foothill label rather than staying unmarked.
			twin = this.fallbackVariants.get(vanilla);
		}
		return twin != null ? twin : vanilla;
	}

	@Override
	public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) {
		Climate.TargetPoint point = sampler.sample(
			QuartPos.fromBlock(feetPos.getX()), QuartPos.fromBlock(feetPos.getY()), QuartPos.fromBlock(feetPos.getZ()));
		int zone = zoneOf(Climate.unquantizeCoord(point.temperature()));
		result.add("Epic zone: " + (zone == 0 ? "none" : zone <= this.zones.size() ? this.describe(zone) : String.valueOf(zone)));
		this.base.addDebugInfo(result, feetPos, new Climate.Sampler(
			new DensitySampler.Bound(new WithoutZone(sampler.temperature().sampler()), sampler.temperature().context()),
			sampler.humidity(), sampler.continentalness(), sampler.erosion(), sampler.depth(), sampler.weirdness()));
	}

	private String describe(int zone) {
		Zone rule = this.zones.get(zone - 1);
		return rule.biome().map(Holder::getRegisteredName).orElse(rule.variants().isEmpty() ? String.valueOf(zone) : "foothills");
	}

	/** A temperature sampler with the zone marker removed, for code that only understands real temperatures. */
	private record WithoutZone(DensitySampler inner) implements DensitySampler {
		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
			this.inner.sampleVolume(context, outputBuffer, volume);
			for (int i = 0; i < volume.size(); i++) {
				outputBuffer.set(i, temperatureOf(outputBuffer.get(i)));
			}
		}

		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			return temperatureOf(this.inner.sampleValue(context, blockX, blockY, blockZ));
		}
	}
}
