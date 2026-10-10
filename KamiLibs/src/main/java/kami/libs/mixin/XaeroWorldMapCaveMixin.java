package kami.libs.mixin;

import kami.libs.xaero.impl.MixinHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "xaero.map.config.util.WorldMapClientConfigUtils", remap = false)
public abstract class XaeroWorldMapCaveMixin {
    @Inject(method = "getEffectiveCaveModeAllowed", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void kami$fairCave(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (MixinHooks.caveBlocked()) cir.setReturnValue(false);
        } catch (Throwable t) {
            MixinHooks.fail(t);
        }
    }
}
