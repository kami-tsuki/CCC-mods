package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void kami$quietEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (player != null && Vanish.INSTANCE.active(player)) ci.cancel();
    }

    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V", at = @At("HEAD"), cancellable = true)
    private void kami$quietVibration(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
        if (context.sourceEntity() instanceof Player player && Vanish.INSTANCE.active(player)) ci.cancel();
    }
}
