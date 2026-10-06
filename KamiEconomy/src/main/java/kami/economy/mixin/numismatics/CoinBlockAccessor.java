package kami.economy.mixin.numismatics;

import dev.ithundxr.createnumismatics.content.depositor.AbstractDepositorBlockEntity;
import dev.ithundxr.createnumismatics.content.salepoint.SalepointBlockEntity;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin({VendorBlockEntity.class, AbstractDepositorBlockEntity.class, SalepointBlockEntity.class})
public interface CoinBlockAccessor {
    @Accessor("owner")
    UUID kamiOwner();

    @Invoker("getDepositAccount")
    UUID kamiDepositAccount();
}
