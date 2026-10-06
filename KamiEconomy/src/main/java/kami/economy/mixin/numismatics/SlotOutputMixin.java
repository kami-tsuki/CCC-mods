package kami.economy.mixin.numismatics;

import dev.ithundxr.createnumismatics.content.coins.SlotOutputMergingCoinBag;
import kami.economy.economy.CoinHooks;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SlotOutputMergingCoinBag.class)
public abstract class SlotOutputMixin {
    @Inject(method = "remove", at = @At("RETURN"))
    private void kami$withdrawn(int amount, CallbackInfoReturnable<ItemStack> cir) {
        CoinHooks.withdrawn(cir.getReturnValue());
    }
}
