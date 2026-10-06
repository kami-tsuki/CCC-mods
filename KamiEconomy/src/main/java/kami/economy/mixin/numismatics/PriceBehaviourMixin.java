package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.backend.IDeductable;
import dev.ithundxr.createnumismatics.content.backend.ReasonHolder;
import dev.ithundxr.createnumismatics.content.backend.behaviours.SliderStylePriceBehaviour;
import dev.ithundxr.createnumismatics.content.salepoint.SalepointBlockEntity;
import java.util.Map;
import kami.economy.economy.CoinHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SliderStylePriceBehaviour.class)
public abstract class PriceBehaviourMixin {
    private static final String DEDUCT = "deduct(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;ZLdev/ithundxr/createnumismatics/content/backend/ReasonHolder;)Z";

    private BlockEntity kami$block() {
        return ((BlockEntityBehaviour) (Object) this).blockEntity;
    }

    private int kami$price() {
        return ((SliderStylePriceBehaviour) (Object) this).getTotalPrice();
    }

    @WrapOperation(method = DEDUCT, at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/backend/IDeductable;deduct(ILdev/ithundxr/createnumismatics/content/backend/ReasonHolder;)Z"))
    private boolean kami$card(IDeductable source, int amount, ReasonHolder reason, Operation<Boolean> original, @Local(argsOnly = true) Player player, @Local(argsOnly = true) boolean kept) {
        boolean ok = CoinHooks.explained("price", () -> original.call(source, amount, reason));
        if (ok) CoinHooks.paid(kami$block(), player, amount, true, kept);
        return ok;
    }

    @WrapOperation(method = DEDUCT, at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/coins/CoinItem;extract(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Ljava/util/Map;Z)Z"))
    private boolean kami$hand(Player player, InteractionHand hand, Map<Coin, Integer> prices, boolean onlyHand, Operation<Boolean> original, @Local(argsOnly = true) boolean kept) {
        boolean ok = original.call(player, hand, prices, onlyHand);
        if (ok) CoinHooks.paid(kami$block(), player, kami$price(), false, kept);
        return ok;
    }

    @WrapOperation(method = "deductFromSelf", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/backend/IDeductable;deduct(Ldev/ithundxr/createnumismatics/content/backend/Coin;ILdev/ithundxr/createnumismatics/content/backend/ReasonHolder;)Z"))
    private boolean kami$drawn(IDeductable source, Coin coin, int count, ReasonHolder reason, Operation<Boolean> original) {
        boolean ok = CoinHooks.explained("vendor payout", () -> original.call(source, coin, count, reason));
        if (ok) CoinHooks.drawn(kami$block(), coin.value * count);
        return ok;
    }

    @Inject(method = "addCoinsToSelf", at = @At("HEAD"))
    private void kami$salepoint(CallbackInfo ci) {
        if (kami$block() instanceof SalepointBlockEntity be) CoinHooks.paid(be, null, kami$price(), true, true);
    }
}
