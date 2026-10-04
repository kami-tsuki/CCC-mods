package kami.essentials.mixin;

import kami.essentials.display.Motd;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "buildServerStatus", at = @At("RETURN"), cancellable = true)
    private void kami$motd(CallbackInfoReturnable<ServerStatus> cir) {
        cir.setReturnValue(Motd.INSTANCE.apply((MinecraftServer) (Object) this, cir.getReturnValue()));
    }
}
