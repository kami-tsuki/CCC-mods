package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.claims.research.RecipeFilter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
    @WrapOperation(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/inventory/ResultContainer;setRecipeUsed(Lnet/minecraft/world/level/Level;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/crafting/RecipeHolder;)Z"), require = 1)
    private static boolean kami$unlockedResult(ResultContainer container, Level level, ServerPlayer player, RecipeHolder<?> holder, Operation<Boolean> original) {
        return RecipeFilter.allowedFor(player, holder.id()) && original.call(container, level, player, holder);
    }
}
