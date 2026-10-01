package kami.claims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FlowingFluid.class)
public class FlowingFluidMixin {
    @WrapOperation(method = "spreadTo", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 1)
    private boolean kami$fluidSpread(LevelAccessor level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original, @Local(argsOnly = true) Direction direction) {
        return Guard.INSTANCE.fluidAllowed(level, pos.relative(direction.getOpposite()), pos) && original.call(level, pos, state, flags);
    }
}
