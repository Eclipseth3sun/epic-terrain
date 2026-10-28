package com.epicterrain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** Test-only: registers into a built-in registry that Bootstrap has already frozen. */
final class LateRegistration {
	private LateRegistration() {}

	static <T> void register(Registry<T> registry, Identifier id, T value) {
		try {
			MappedRegistry<T> mapped = (MappedRegistry<T>) registry;
			Field frozen = MappedRegistry.class.getDeclaredField("frozen");
			frozen.setAccessible(true);
			frozen.setBoolean(mapped, false);
			ResourceKey<T> key = ResourceKey.create(registry.key(), id);
			Holder.Reference<T> holder = mapped.register(key, value, RegistrationInfo.BUILT_IN);
			frozen.setBoolean(mapped, true);
			Method bind = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
			bind.setAccessible(true);
			bind.invoke(holder, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not register " + id + " late", e);
		}
	}
}
