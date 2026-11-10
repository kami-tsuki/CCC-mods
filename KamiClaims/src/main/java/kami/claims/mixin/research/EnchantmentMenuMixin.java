package kami.claims.mixin.research;

import kami.claims.research.ItemGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
    @Shadow
    @Final
    private Container enchantSlots;

    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true, require = 1)
    private void kami$researchedItem(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayer sp && !ItemGate.allows(sp, enchantSlots.getItem(0))) {
            ItemGate.deny(sp);
            cir.setReturnValue(false);
        }
    }
}
