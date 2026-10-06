package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ithundxr.createnumismatics.content.bank.blaze_banker.BankAccountBehaviour;
import kami.economy.economy.CoinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BankAccountBehaviour.class)
public abstract class BlazeBankerMixin {
    @WrapOperation(method = "destroy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Containers;dropContents(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;)V"))
    private void kami$dropped(Level level, BlockPos pos, NonNullList<ItemStack> stacks, Operation<Void> original) {
        CoinHooks.dropped(level, pos, stacks);
        original.call(level, pos, stacks);
    }
}
