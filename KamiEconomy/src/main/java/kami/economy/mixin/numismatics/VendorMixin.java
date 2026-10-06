package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ithundxr.createnumismatics.content.backend.behaviours.SliderStylePriceBehaviour;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity;
import kami.economy.economy.CoinHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VendorBlockEntity.class)
public abstract class VendorMixin {
    @Shadow
    public abstract boolean isCreativeVendor();

    @WrapOperation(method = "tryBuyFrom", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/backend/behaviours/SliderStylePriceBehaviour;pay(Lnet/minecraft/world/entity/player/Player;)V"))
    private void kami$payout(SliderStylePriceBehaviour price, Player player, Operation<Void> original) {
        original.call(price, player);
        CoinHooks.payout(this, isCreativeVendor(), player, price.getTotalPrice());
    }
}
