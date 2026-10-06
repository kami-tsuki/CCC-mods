package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.depositor.AbstractDepositorBlockEntity;
import dev.ithundxr.createnumismatics.content.salepoint.SalepointBlockEntity;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import kami.economy.economy.CoinHooks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({VendorBlockEntity.class, AbstractDepositorBlockEntity.class, SalepointBlockEntity.class})
public abstract class CoinBlockMixin {
    @WrapOperation(method = "lazyTick", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/backend/BankAccount;deposit(Ldev/ithundxr/createnumismatics/content/backend/Coin;I)V"))
    private void kami$flushed(BankAccount account, Coin coin, int count, Operation<Void> original) {
        CoinHooks.explained("block flush", () -> original.call(account, coin, count));
        CoinHooks.flushed((BlockEntity) (Object) this, coin.value * count);
    }

    @WrapOperation(method = "addCoin", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/backend/BankAccount;deposit(Ldev/ithundxr/createnumismatics/content/backend/Coin;I)V"))
    private void kami$received(BankAccount account, Coin coin, int count, Operation<Void> original) {
        CoinHooks.explained("block deposit", () -> original.call(account, coin, count));
    }
}
