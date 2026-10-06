package kami.economy.mixin.coins;

import kami.economy.world.CoinRules;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CreativeSlotMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private int dropSpamTickCount;

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER))
    private void kami$coinCreative(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        CoinRules.creative(player, packet.slotNum(), packet.itemStack(), dropSpamTickCount >= 200);
    }
}
