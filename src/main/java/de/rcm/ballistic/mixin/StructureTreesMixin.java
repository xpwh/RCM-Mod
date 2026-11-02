package de.rcm.ballistic.mixin;

import de.rcm.ballistic.base.BaseRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.BambooFeature;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.HugeFungusFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No trees on the military sites and in the cities: a tree, fallen log, huge mushroom or bamboo whose chunk a
 * base or a city reaches into is not planted (the sites themselves clear what grew before them; this keeps the
 * neighbouring chunks, decorated later, from growing into them).
 */
@Mixin(Feature.class)
public abstract class StructureTreesMixin {
	@Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration;Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$noTreesOnSites(net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration config, WorldGenLevel level,
		ChunkGenerator generator, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		Object self = this;
		if (!(self instanceof TreeFeature || self instanceof FallenTreeFeature || self instanceof AbstractHugeMushroomFeature
			|| self instanceof HugeFungusFeature || self instanceof BambooFeature)) {
			return;
		}
		ChunkAccess chunk;
		try {
			chunk = level.getChunk(pos);
		} catch (RuntimeException e) {
			return;
		}
		if (!chunk.hasAnyStructureReferences()) {
			return;
		}
		for (Structure s : chunk.getAllReferences().keySet()) {
			if (s.type() == BaseRegistry.MILITARY_BASE || s.type() == BaseRegistry.CITY) {
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
