package kami.claims.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import kami.claims.research.Listeners;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every growth step of a DynamicTrees tree counts as one grown tree; DynamicTrees fires no growth event of its own. */
@Pseudo
@Mixin(targets = "com.dtteam.dynamictrees.tree.species.Species")
public abstract class DynamicTreesGrowMixin {
    @Inject(method = "grow", at = @At("RETURN"), require = 0)
    private void kami$treeGrew(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) Level level, @Local(argsOnly = true, ordinal = 0) BlockPos root) {
        if (cir.getReturnValueZ() && !level.isClientSide) Listeners.treeGrew(level, root);
    }
}
