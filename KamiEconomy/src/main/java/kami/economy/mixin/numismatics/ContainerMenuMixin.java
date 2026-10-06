package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kami.economy.economy.CoinHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
    @WrapMethod(method = "clicked")
    private void kami$coinWindow(int slot, int button, ClickType type, Player player, Operation<Void> original) {
        boolean open = CoinHooks.enterMenu(this, player);
        try {
            original.call(slot, button, type, player);
        } finally {
            if (open) CoinHooks.exitMenu();
        }
    }
}
