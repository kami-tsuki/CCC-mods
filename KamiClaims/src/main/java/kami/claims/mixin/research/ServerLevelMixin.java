package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kami.claims.research.RecipeContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @WrapMethod(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", require = 1)
    private void kami$scheduledTickContext(BlockPos pos, Block block, Operation<Void> original) {
        RecipeContext.run((ServerLevel) (Object) this, pos.asLong(), () -> original.call(pos, block));
    }
}
