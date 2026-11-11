package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import kami.economy.world.CoinClick;
import kami.economy.world.CoinRules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractContainerMenu.class)
public abstract class CoinMenuMixin {
    @WrapMethod(method = "doClick")
    private void kami$coinClick(int slot, int button, ClickType type, Player player, Operation<Void> original) {
        CoinClick.run(slot, button, type, player, original);
    }

    @WrapOperation(method = {"doClick", "moveItemStackTo"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;mayPlace(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean kami$coinPlace(Slot slot, ItemStack stack, Operation<Boolean> original) {
        return original.call(slot, stack) && CoinRules.mayPlace(slot, stack);
    }

    @WrapOperation(method = "moveItemStackTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean kami$coinMerge(ItemStack stack, ItemStack other, Operation<Boolean> original, @Local(ordinal = 0) Slot slot) {
        return original.call(stack, other) && CoinRules.mayPlace(slot, stack);
    }

    @WrapOperation(method = "doClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;isValidQuickcraftType(ILnet/minecraft/world/entity/player/Player;)Z"))
    private boolean kami$coinSpread(int type, Player player, Operation<Boolean> original) {
        return original.call(type, player) && !(type == 2 && CoinRules.isCoin(((AbstractContainerMenu) (Object) this).getCarried()));
    }

    @WrapOperation(method = "doClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;copyWithCount(I)Lnet/minecraft/world/item/ItemStack;", ordinal = 1))
    private ItemStack kami$coinClone(ItemStack stack, int count, Operation<ItemStack> original) {
        return CoinRules.isCoin(stack) ? ItemStack.EMPTY : original.call(stack, count);
    }
}
