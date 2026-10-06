package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import kami.economy.economy.CoinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StockTickerInteractionHandler.class)
public abstract class StockTickerMixin {
    @WrapMethod(method = "interactWithShop")
    private static void kami$shop(Player player, Level level, BlockPos pos, ItemStack stack, Operation<Void> original) {
        boolean open = CoinHooks.enterShop(player, pos);
        try {
            original.call(player, level, pos, stack);
        } finally {
            if (open) CoinHooks.exitShop();
        }
    }
}
