package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
    private void kami$hideInvisible(ServerPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
        if (Vanish.INSTANCE.hides((ServerPlayer) (Object) this, viewer)) cir.setReturnValue(false);
    }
}
