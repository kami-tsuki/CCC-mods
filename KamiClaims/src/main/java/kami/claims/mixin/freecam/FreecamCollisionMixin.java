package kami.claims.mixin.freecam;

import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.xolt.freecam.config.CollisionBehavior")
public abstract class FreecamCollisionMixin {
    @Inject(method = "isIgnored", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void kami$solid(Block block, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
