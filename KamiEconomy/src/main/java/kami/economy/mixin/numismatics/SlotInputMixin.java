package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.coins.MergingCoinBag;
import dev.ithundxr.createnumismatics.content.coins.SlotInputMergingCoinBag;
import kami.economy.economy.CoinHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SlotInputMergingCoinBag.class)
public abstract class SlotInputMixin {
    @WrapOperation(method = "set", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/coins/MergingCoinBag;add(Ldev/ithundxr/createnumismatics/content/backend/Coin;I)V"))
    private void kami$deposited(MergingCoinBag bag, Coin coin, int count, Operation<Void> original) {
        original.call(bag, coin, count);
        CoinHooks.deposited(coin.value * count);
    }
}
