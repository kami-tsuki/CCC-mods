package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ithundxr.createnumismatics.content.salepoint.SalepointBlockEntity;
import kami.economy.economy.CoinHooks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SalepointBlockEntity.class)
public abstract class SalepointMixin {
    @WrapMethod(method = "attemptTransaction")
    private void kami$explained(Operation<Void> original) {
        CoinHooks.explained("salepoint", () -> original.call());
    }
}
