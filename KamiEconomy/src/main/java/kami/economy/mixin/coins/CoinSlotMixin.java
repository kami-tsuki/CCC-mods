package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.economy.world.CoinRules;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Slot.class)
public abstract class CoinSlotMixin {
    @WrapOperation(method = "safeInsert(Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/ItemStack;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;mayPlace(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean kami$coinInsert(Slot slot, ItemStack stack, Operation<Boolean> original) {
        return original.call(slot, stack) && CoinRules.mayPlace(slot, stack);
    }
}
