package kami.libs.mixin;

import kami.libs.xaero.impl.MixinHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroGuiMapMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "getRightClickOptions", at = @At("RETURN"), require = 0, remap = false)
    private void kami$actions(CallbackInfoReturnable<ArrayList> cir) {
        try {
            ArrayList options = cir.getReturnValue();
            if (options == null) return;
            List extra = MixinHooks.rightClick(this, options.size());
            options.addAll(extra);
        } catch (Throwable t) {
            MixinHooks.fail(t);
        }
    }
}
