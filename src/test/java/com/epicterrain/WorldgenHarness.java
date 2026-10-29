package com.epicterrain;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import com.epicterrain.EpicTerrain;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Loads the vanilla datapack plus this mod's {@code data/} folder through the same registry loader the
 * game uses, so worldgen JSON can be validated and sampled without launching Minecraft.
 */
public final class WorldgenHarness implements AutoCloseable {
	private static WorldgenHarness shared;

	private final CloseableResourceManager resources;
	public final RegistryAccess.Frozen registries;
	/** The data-driven registries together with the built-in ones (block, item, ...), as the running game's registry access has them. */
	public final RegistryAccess.Frozen allRegistries;

	private WorldgenHarness(CloseableResourceManager resources, RegistryAccess.Frozen registries, RegistryAccess.Frozen allRegistries) {
		this.resources = resources;
		this.registries = registries;
		this.allRegistries = allRegistries;
	}

	/** Loads once per JVM; loading registries is slow and the result is immutable. */
	public static synchronized WorldgenHarness shared() {
		if (shared == null) {
			shared = load();
		}
		return shared;
	}

	private static WorldgenHarness load() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Under the JUnit launcher a few built-in registries (block, item, entity_type) are left unfrozen with
		// unbound tags; the real game binds those to empty and freezes them here, so do the same.
		for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
			if (registry instanceof MappedRegistry<?> mapped) {
				mapped.bindAllTagsToEmpty();
			}
			registry.freeze();
		}
		// In the real game Fabric runs the mod initializer before the freeze; the JUnit launcher doesn't run it at all.
		EpicTerrain.registerWorldgen(LateRegistration::register);

		String resourcesDir = System.getProperty("epicterrain.resourcesDir");
		if (resourcesDir == null) {
			throw new IllegalStateException("epicterrain.resourcesDir system property is not set");
		}

		PackResources vanilla = ServerPacksSource.createVanillaPackSource().fullResources();
		PackResources mod = new PathPackResources(
			new PackLocationInfo(EpicTerrain.MOD_ID, Component.literal(EpicTerrain.MOD_ID), PackSource.BUILT_IN, Optional.empty()),
			Path.of(resourcesDir)
		);
		CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla, mod));

		// Mirrors WorldLoader: tags for the static registries first, then the data-driven world registries.
		LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
		RegistryAccess.Frozen loadContext = layers.getAccessForLoading(RegistryLayer.WORLD);
		List<HolderLookup.RegistryLookup<?>> contextRegistries = TagLoader.buildUpdatedLookups(loadContext, staticTags);

		RegistryAccess.Frozen loaded = RegistryDataLoader.load(
			resources, contextRegistries, RegistryDataLoader.WORLD_REGISTRIES, ForkJoinPool.commonPool()
		).join();
		// Biome/placed-feature tags are bound after the registries load (ReloadableServerResources in the real game).
		TagLoader.loadTagsForExistingRegistries(resources, loaded).forEach(Registry.PendingTags::apply);
		// and the tags of the built-in registries (block tags such as features_cannot_replace, which structures look up), as the server's resource reload binds them
		staticTags.forEach(Registry.PendingTags::apply);
		return new WorldgenHarness(resources, loaded, layers.replaceFrom(RegistryLayer.WORLD, loaded).compositeAccess());
	}

	/** The vanilla pack and this mod's data, as the game's resource manager: what the structure template manager reads templates from. */
	public net.minecraft.server.packs.resources.ResourceManager resourceManager() {
		return resources;
	}

	public static ResourceKey<NoiseGeneratorSettings> settingsKey(String id) {
		return ResourceKey.create(Registries.NOISE_SETTINGS, Identifier.parse(id));
	}

	public Optional<NoiseGeneratorSettings> settings(String id) {
		return registries.lookupOrThrow(Registries.NOISE_SETTINGS).get(settingsKey(id)).map(h -> h.value());
	}

	public RandomState randomState(long seed, NoiseGeneratorSettings settings) {
		HolderGetter<NormalNoise> noises = registries.lookupOrThrow(Registries.NOISE);
		return RandomState.create(noises, seed, settings);
	}

	@Override
	public void close() {
		resources.close();
	}
}
