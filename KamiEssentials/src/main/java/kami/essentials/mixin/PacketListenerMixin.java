package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PacketListenerMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void kami$hideInvisible(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket info) || !((Object) this instanceof ServerGamePacketListenerImpl game)) return;
        ClientboundPlayerInfoUpdatePacket shown = Vanish.INSTANCE.filter(game.player, info);
        if (shown == info) return;
        ci.cancel();
        if (shown != null) game.send(shown, listener);
    }
}
