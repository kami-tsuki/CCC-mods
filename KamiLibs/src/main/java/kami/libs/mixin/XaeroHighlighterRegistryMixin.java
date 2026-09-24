package kami.libs.mixin;

import kami.libs.KamiLibs;
import kami.libs.xaero.Highlights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "xaero.map.highlight.HighlighterRegistry", remap = false)
public class XaeroHighlighterRegistryMixin {
    @Inject(method = "end", at = @At("HEAD"), remap = false, require = 0)
    private void kami$attach(CallbackInfo ci) {
        try {
            Highlights.INSTANCE.attach(this);
        } catch (Throwable t) {
            KamiLibs.INSTANCE.getLOG().warn("Could not attach an overlay to Xaero's World Map", t);
        }
    }
}
