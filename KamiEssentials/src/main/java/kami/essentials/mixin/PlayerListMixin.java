package kami.essentials.mixin;

import com.mojang.authlib.GameProfile;
import kami.essentials.chat.Feed;
import kami.essentials.discord.Gate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
    private void kami$feed(Component message, boolean overlay, CallbackInfo ci) {
        if (Feed.INSTANCE.intercept(message)) ci.cancel();
    }

    @Inject(method = "canPlayerLogin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
    private void kami$gate(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() != null || !((PlayerList) (Object) this).getServer().isDedicatedServer()) return;
        Component kick = Gate.INSTANCE.check(profile);
        if (kick != null) cir.setReturnValue(kick);
    }
}
