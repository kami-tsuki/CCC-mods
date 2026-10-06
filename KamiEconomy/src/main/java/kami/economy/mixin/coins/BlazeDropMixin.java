package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ithundxr.createnumismatics.content.bank.blaze_banker.BankAccountBehaviour;
import java.util.UUID;
import kami.economy.world.CoinLoss;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BankAccountBehaviour.class)
public abstract class BlazeDropMixin {
    @WrapMethod(method = "destroy")
    private void kami$dropOwner(Operation<Void> original) {
        UUID prev = CoinLoss.dropping(((BlockEntityBehaviour) (Object) this).blockEntity);
        try {
            original.call();
        } finally {
            CoinLoss.dropped(prev);
        }
    }
}
