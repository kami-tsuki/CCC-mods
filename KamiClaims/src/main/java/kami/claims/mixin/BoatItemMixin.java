package kami.claims.mixin;

import kami.claims.world.Guard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers who placed a boat so they can always break it again, even inside a claim that denies them breaking. */
@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
    @Inject(method = "getBoat", at = @At("RETURN"))
    private void kami$ownBoat(Level level, HitResult hit, ItemStack stack, Player player, CallbackInfoReturnable<Boat> cir) {
        if (!level.isClientSide && player != null) Guard.ownBoat(cir.getReturnValue(), player);
    }
}
