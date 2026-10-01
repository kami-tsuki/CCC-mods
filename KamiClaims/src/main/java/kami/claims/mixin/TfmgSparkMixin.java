package kami.claims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.drmangotea.tfmg.base.spark.Spark")
public abstract class TfmgSparkMixin {
    @WrapOperation(method = "onHitBlock", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"), require = 0)
    private boolean kami$sparkFire(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        return Guard.INSTANCE.igniteAllowed(level, pos, ((Projectile) (Object) this).getOwner()) && original.call(level, pos, state);
    }
}
