package kami.claims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DispenserBlock.class)
public class DispenserTransferMixin {
    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/DispenserBlock;getDispenseMethod(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/core/dispenser/DispenseItemBehavior;"), require = 1)
    private DispenseItemBehavior kami$dispense(DispenserBlock block, Level level, ItemStack stack, Operation<DispenseItemBehavior> original,
                                               @Local(argsOnly = true) BlockState state, @Local(argsOnly = true) BlockPos pos) {
        DispenseItemBehavior behavior = original.call(block, level, stack);
        if (behavior.getClass() != DefaultDispenseItemBehavior.class && !Guard.INSTANCE.transferAllowed(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) return DispenseItemBehavior.NOOP;
        return behavior;
    }
}
