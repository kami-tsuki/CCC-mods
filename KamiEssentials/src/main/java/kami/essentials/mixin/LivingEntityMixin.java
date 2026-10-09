package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "take", at = @At("HEAD"), cancellable = true)
    private void kami$quietPickup(Entity entity, int count, CallbackInfo ci) {
        if ((Object) this instanceof Player player && !player.level().isClientSide && Vanish.INSTANCE.active(player)) ci.cancel();
    }
}
