package kami.libs.mixin;

import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BankAccount.class)
public interface BankAccountAccess {
    @Accessor("additionalBalance")
    long kami$additional();
}
