package kami.libs.mixin;

import kami.libs.xaero.impl.MinimapAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "xaero.hud.minimap.info.BuiltInInfoDisplays", remap = false)
public abstract class XaeroInfoDisplaysMixin {
    @SuppressWarnings("unchecked")
    @Inject(method = "forEach", at = @At("TAIL"), require = 0, remap = false)
    private static void kami$addDisplay(Consumer<?> consumer, CallbackInfo ci) {
        Object display = MinimapAdapter.INSTANCE.display();
        if (display != null) ((Consumer<Object>) consumer).accept(display);
    }
}
