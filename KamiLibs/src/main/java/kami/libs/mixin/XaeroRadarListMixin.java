package kami.libs.mixin;

import kami.libs.xaero.XaeroMaps;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "xaero.hud.minimap.radar.state.RadarList", remap = false)
public abstract class XaeroRadarListMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void kami$hide(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (XaeroMaps.fairPlayActive() || XaeroMaps.radarHidden(entity.getUUID())) cir.setReturnValue(false);
    }
}
