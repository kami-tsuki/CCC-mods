package kami.geology.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import kami.geology.world.Hardness;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMixin {
    @Shadow
    public abstract Block getBlock();

    @ModifyReturnValue(method = "getDestroySpeed", at = @At("RETURN"))
    private float kamiGeology$hardness(float speed) {
        return Hardness.scale(getBlock(), speed);
    }
}
