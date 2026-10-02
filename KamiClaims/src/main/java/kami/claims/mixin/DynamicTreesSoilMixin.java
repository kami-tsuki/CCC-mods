package kami.claims.mixin;

import kami.claims.world.Sky;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Grown DynamicTrees only keep growing under open sky (or the sky_transparent tag). */
@Pseudo
@Mixin(targets = "com.dtteam.dynamictrees.block.soil.SoilBlock")
public abstract class DynamicTreesSoilMixin {
    @Inject(method = "updateTree(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void kami$skyTree(BlockState state, Level level, BlockPos pos, RandomSource random, boolean natural, CallbackInfo ci) {
        if (!Sky.tree(level, pos)) ci.cancel();
    }
}
