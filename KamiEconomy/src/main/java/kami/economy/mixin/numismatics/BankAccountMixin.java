package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import java.util.List;
import java.util.UUID;
import kami.economy.economy.CoinHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BankAccount.class)
public abstract class BankAccountMixin {
    @Shadow
    private int balance;

    @Shadow
    private long additionalBalance;

    @Shadow
    private List<UUID> trustList;

    @Inject(method = "setBalance", at = @At("HEAD"))
    private void kami$balance(int next, CallbackInfo ci) {
        CoinHooks.balance((BankAccount) (Object) this, next);
    }

    private long kami$total() {
        return balance + additionalBalance;
    }

    @WrapMethod(method = "setBalance")
    private void kami$set(int next, Operation<Void> original) {
        CoinHooks.open();
        long before = kami$total();
        try {
            original.call(next);
        } finally {
            CoinHooks.close((BankAccount) (Object) this, before, kami$total(), trustList);
        }
    }

    @WrapMethod(method = "deposit(I)V")
    private void kami$deposit(int amount, Operation<Void> original) {
        CoinHooks.open();
        long before = kami$total();
        try {
            original.call(amount);
        } finally {
            CoinHooks.close((BankAccount) (Object) this, before, kami$total(), trustList);
        }
    }

    @WrapMethod(method = "deduct(IZ)Z")
    private boolean kami$deduct(int amount, boolean force, Operation<Boolean> original) {
        CoinHooks.open();
        long before = kami$total();
        try {
            return original.call(amount, force);
        } finally {
            CoinHooks.close((BankAccount) (Object) this, before, kami$total(), trustList);
        }
    }
}
