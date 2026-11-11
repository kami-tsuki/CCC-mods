package kami.claims.mixin;

import kami.claims.Action;
import kami.claims.KamiClaims;
import kami.claims.world.Guard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper")
public abstract class SophisticatedMagnetMixin {
    private static boolean kami$warned;

    @Inject(method = "canNotPickup", at = @At("HEAD"), cancellable = true, require = 0)
    private void kami$claimedPickup(Entity pickup, Entity carrier, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (Guard.INSTANCE.blocked(carrier, pickup.blockPosition(), Action.INTERACT)) cir.setReturnValue(true);
        } catch (Throwable t) {
            if (!kami$warned) {
                kami$warned = true;
                KamiClaims.INSTANCE.getLOG().warn("Magnet claim check failed: {}", t.toString());
            }
        }
    }
}
