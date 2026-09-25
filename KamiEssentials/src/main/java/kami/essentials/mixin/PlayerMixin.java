package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
    private void kami$silent(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide && Vanish.INSTANCE.active(self)) ci.cancel();
    }
}
