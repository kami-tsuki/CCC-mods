package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.claims.research.Processing;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceBlockEntityMixin {
    @WrapOperation(
        method = "serverTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;burn(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/item/crafting/RecipeHolder;Lnet/minecraft/core/NonNullList;ILnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;)Z"),
        require = 1
    )
    private static boolean kami$countBurn(RegistryAccess access, RecipeHolder<?> holder, NonNullList<ItemStack> items, int maxStack, AbstractFurnaceBlockEntity furnace, Operation<Boolean> original) {
        boolean burned = original.call(access, holder, items, maxStack, furnace);
        if (burned) Processing.burned(holder, access);
        return burned;
    }
}
