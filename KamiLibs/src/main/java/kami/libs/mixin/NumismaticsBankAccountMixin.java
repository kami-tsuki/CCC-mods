package kami.libs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "dev.ithundxr.createnumismatics.content.backend.BankAccount")
public interface NumismaticsBankAccountMixin {
    @Accessor("additionalBalance")
    long kami$additional();
}
