package kami.claims.mixin;

import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public class HopperTransferMixin {
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true, require = 1)
    private static void kami$eject(Level level, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        BlockPos target = pos.relative(hopper.getBlockState().getValue(HopperBlock.FACING));
        if (!Guard.INSTANCE.transferAllowed(level, pos, target)) cir.setReturnValue(false);
    }
}
