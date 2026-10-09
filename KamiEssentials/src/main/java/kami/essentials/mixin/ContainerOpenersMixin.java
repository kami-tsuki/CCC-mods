package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersMixin {
    @Unique
    private final Set<UUID> kami$silent = ConcurrentHashMap.newKeySet();

    @Inject(method = "incrementOpeners", at = @At("HEAD"), cancellable = true)
    private void kami$quietOpen(Player player, Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!level.isClientSide && Vanish.INSTANCE.active(player)) {
            kami$silent.add(player.getUUID());
            ci.cancel();
        }
    }

    @Inject(method = "decrementOpeners", at = @At("HEAD"), cancellable = true)
    private void kami$quietClose(Player player, Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!level.isClientSide && kami$silent.remove(player.getUUID())) ci.cancel();
    }

    @Inject(method = "getPlayersWithContainerOpen", at = @At("RETURN"), cancellable = true)
    private void kami$unseen(Level level, BlockPos pos, CallbackInfoReturnable<List<Player>> cir) {
        if (level.isClientSide || kami$silent.isEmpty()) return;
        cir.setReturnValue(cir.getReturnValue().stream().filter(p -> !kami$silent.contains(p.getUUID())).toList());
    }
}
