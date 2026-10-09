package kami.libs.mixin;

import kami.libs.KamiLibs;
import kami.libs.xaero.impl.WorldMapAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "xaero.map.highlight.HighlighterRegistry", remap = false)
public class XaeroHighlighterRegistryMixin {
    @Inject(method = "end", at = @At("HEAD"), remap = false, require = 0)
    private void kami$attach(CallbackInfo ci) {
        try {
            WorldMapAdapter.attach(this);
        } catch (Throwable t) {
            KamiLibs.LOG.warn("Could not attach an overlay to Xaero's World Map", t);
        }
    }
}
