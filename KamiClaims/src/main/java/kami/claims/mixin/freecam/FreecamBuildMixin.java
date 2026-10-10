package kami.claims.mixin.freecam;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.xolt.freecam.variant.impl.NormalBuild")
public abstract class FreecamBuildMixin {
    @Inject(method = "cheatsPermitted", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void kami$noCheats(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
