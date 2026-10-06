package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.sugar.Local;
import kami.economy.world.CoinRules;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler")
public abstract class SophisticatedStorageMixin {
    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true)
    private void kami$coinValid(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) ItemStack stack) {
        if (CoinRules.isCoin(stack)) cir.setReturnValue(false);
    }

    @Inject(method = {"insertItem", "insertItemOnlyToSlot"}, at = @At("HEAD"), cancellable = true)
    private void kami$coinInsert(CallbackInfoReturnable<ItemStack> cir, @Local(argsOnly = true) ItemStack stack) {
        if (CoinRules.isCoin(stack)) cir.setReturnValue(stack);
    }
}
