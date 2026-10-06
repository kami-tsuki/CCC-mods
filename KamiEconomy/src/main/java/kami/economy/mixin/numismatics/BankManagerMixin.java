package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.GlobalBankManager;
import java.util.UUID;
import kami.economy.economy.CoinHooks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GlobalBankManager.class)
public abstract class BankManagerMixin {
    @WrapMethod(method = "getOrCreateAccount")
    private BankAccount kami$created(UUID id, BankAccount.Type type, Operation<BankAccount> original) {
        return CoinHooks.explained("account created", () -> original.call(id, type));
    }
}
