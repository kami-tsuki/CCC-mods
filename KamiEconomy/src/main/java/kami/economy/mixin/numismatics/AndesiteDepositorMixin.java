package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.content.backend.IDeductable;
import dev.ithundxr.createnumismatics.content.backend.ReasonHolder;
import dev.ithundxr.createnumismatics.content.depositor.AndesiteDepositorBlock;
import kami.economy.economy.CoinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AndesiteDepositorBlock.class)
public abstract class AndesiteDepositorMixin {
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/backend/IDeductable;deduct(Ldev/ithundxr/createnumismatics/content/backend/Coin;ILdev/ithundxr/createnumismatics/content/backend/ReasonHolder;)Z"))
    private boolean kami$card(IDeductable source, Coin coin, int count, ReasonHolder reason, Operation<Boolean> original, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) Player player) {
        boolean ok = CoinHooks.explained("depositor", () -> original.call(source, coin, count, reason));
        if (ok) CoinHooks.paid(level.getBlockEntity(pos), player, coin.value * count, true, true);
        return ok;
    }

    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Ldev/ithundxr/createnumismatics/content/coins/CoinItem;extract(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Ldev/ithundxr/createnumismatics/content/backend/Coin;Z)Z"))
    private boolean kami$hand(Player player, InteractionHand hand, Coin coin, boolean onlyHand, Operation<Boolean> original, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        boolean ok = original.call(player, hand, coin, onlyHand);
        if (ok) CoinHooks.paid(level.getBlockEntity(pos), player, coin.value, false, true);
        return ok;
    }
}
