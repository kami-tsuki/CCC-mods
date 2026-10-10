package kami.claims.mixin.freecam;

import kami.claims.client.compat.freecam.FreecamGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.xolt.freecam.Freecam")
public abstract class FreecamMixin {
    @Inject(method = "toggle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void kami$toggle(CallbackInfo ci) {
        if (FreecamGuard.blocked()) ci.cancel();
    }

    @Inject(method = "toggleTripod", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void kami$toggleTripod(CallbackInfo ci) {
        if (FreecamGuard.blocked()) ci.cancel();
    }
}
