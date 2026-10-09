package kami.claims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DropperBlock.class)
public class DropperTransferMixin {
    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"), require = 1)
    private ItemStack kami$drop(Container from, Container to, ItemStack stack, Direction dir, Operation<ItemStack> original,
                                @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) BlockPos pos) {
        if (!Guard.INSTANCE.transferAllowed(level, pos, pos.relative(dir.getOpposite()))) return stack;
        return original.call(from, to, stack, dir);
    }
}
