package kami.claims.mixin;

import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FlowingFluid.class)
public class FlowingFluidMixin {
    @Redirect(method = "spreadTo", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 0)
    private boolean kami$fluidSpread(LevelAccessor level, BlockPos pos, BlockState state, int flags) {
        if (!Guard.INSTANCE.fluidAllowed(level, pos)) return false;
        return level.setBlock(pos, state, flags);
    }
}
