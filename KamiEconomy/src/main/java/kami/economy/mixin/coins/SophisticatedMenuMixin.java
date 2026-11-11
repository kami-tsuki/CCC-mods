package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.economy.world.CoinClick;
import kami.economy.world.CoinRules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase")
public abstract class SophisticatedMenuMixin {
    @WrapMethod(method = "doClick")
    private void kami$coinClick(int slot, int button, ClickType type, Player player, Operation<Void> original) {
        CoinClick.run(slot, button, type, player, original);
    }

    @WrapOperation(method = {"clicked", "handleOverflow", "doClick", "mergeItemStack"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;mayPlace(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean kami$coinPlace(Slot slot, ItemStack stack, Operation<Boolean> original) {
        return original.call(slot, stack) && CoinRules.mayPlace(slot, stack);
    }
}
