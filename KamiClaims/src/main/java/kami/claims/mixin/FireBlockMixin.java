package kami.claims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FireBlock.class)
public class FireBlockMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 1)
    private boolean kami$fireTick(ServerLevel level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        return (!state.is(BlockTags.FIRE) || Guard.INSTANCE.fireAllowed(level, pos)) && original.call(level, pos, state, flags);
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 1)
    private boolean kami$fireBurnOut(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        return (!state.is(BlockTags.FIRE) || Guard.INSTANCE.fireAllowed(level, pos)) && original.call(level, pos, state, flags);
    }
}
