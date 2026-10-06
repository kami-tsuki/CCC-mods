package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import kami.economy.world.CoinLoss;
import kami.economy.world.CoinRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(StockTickerInteractionHandler.class)
public abstract class ShopPaymentMixin {
    @WrapMethod(method = "interactWithShop")
    private static void kami$payer(Player player, Level level, BlockPos pos, ItemStack stack, Operation<Void> original) {
        if (!CoinLoss.shopEnter(player, level, pos)) return;
        try {
            original.call(player, level, pos, stack);
        } finally {
            CoinLoss.shopExit();
        }
    }

    @WrapOperation(method = "lambda$interactWithShop$1", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/ItemHandlerHelper;insertItemStacked(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack kami$coinPayment(IItemHandler handler, ItemStack stack, boolean simulate, Operation<ItemStack> original, @Local(argsOnly = true) StockTickerBlockEntity ticker) {
        return CoinRules.isCoin(stack) ? CoinLoss.shopPaid((Object) ticker, stack) : original.call(handler, stack, simulate);
    }
}
