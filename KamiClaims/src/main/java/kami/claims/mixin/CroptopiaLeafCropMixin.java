package kami.claims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.claims.world.Sky;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Croptopia fruit leaves ripen in their own randomTick (no CropGrowEvent): only ripen under open sky. */
@Pseudo
@Mixin(targets = "com.epherical.croptopia.blocks.LeafCropBlock")
public abstract class CroptopiaLeafCropMixin {
    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require = 0)
    private boolean kami$skyRipen(ServerLevel level, BlockPos pos, BlockState next, int flags, Operation<Boolean> original) {
        if (!Sky.plant(level, pos, next.getBlock())) return false;
        return original.call(level, pos, next, flags);
    }
}
