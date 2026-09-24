package kami.claims.mixin;

import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FireBlock.class)
public class FireBlockMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 0)
    private boolean kami$fireTick(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        if (state.is(BlockTags.FIRE) && !Guard.INSTANCE.fireAllowed(level, pos)) return false;
        return level.setBlock(pos, state, flags);
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 0)
    private boolean kami$fireBurnOut(Level level, BlockPos pos, BlockState state, int flags) {
        if (state.is(BlockTags.FIRE) && !Guard.INSTANCE.fireAllowed(level, pos)) return false;
        return level.setBlock(pos, state, flags);
    }
}
