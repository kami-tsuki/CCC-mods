package kami.economy.mixin.coins;

import dev.ithundxr.createnumismatics.content.coins.CoinItem;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Item.class)
public abstract class CoinItemMixin {
    @Inject(method = "canFitInsideContainerItems()Z", at = @At("HEAD"), cancellable = true)
    private void kami$coinsStayOut(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof CoinItem) cir.setReturnValue(false);
    }
}
