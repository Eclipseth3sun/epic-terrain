package com.epicterrain.worldgen;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;

/**
 * Inlines every referenced density function and replaces one named function with a replacement, without touching the
 * registry. Used to bake the slider value into a copy of the noise settings.
 *
 * <p>Referenced functions are shared all over the graph, so each is rewritten once and reused; without that the
 * inlined tree would be re-expanded at every reference.
 */
public final class ConfigSubstitution implements DfRewriteRule {
	private final ResourceKey<DensityFunction> target;
	private final DensityFunction replacement;
	private final Map<DensityFunction, DensityFunction> rewritten = new IdentityHashMap<>();

	public ConfigSubstitution(ResourceKey<DensityFunction> target, DensityFunction replacement) {
		this.target = target;
		this.replacement = replacement;
	}

	@Override
	public DensityFunction rewrite(DensityFunction function) {
		if (function instanceof DensityFunctions.HolderHolder(Holder<DensityFunction> holder)) {
			if (holder.is(this.target)) {
				return this.replacement;
			}
			DensityFunction inner = holder.value();
			DensityFunction done = this.rewritten.get(inner);
			if (done == null) {
				done = this.rewrite(inner);
				this.rewritten.put(inner, done);
			}
			return done;
		}
		return function.rewriteChildren(this);
	}
}
