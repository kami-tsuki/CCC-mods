package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import kami.claims.research.RecipeContext;
import kami.claims.research.RecipeFilter;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeCache;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RecipeCache.class)
public abstract class RecipeCacheMixin {
    @WrapOperation(method = "compute", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;"), require = 1)
    private Optional<RecipeHolder<CraftingRecipe>> kami$unfilteredCompute(
        RecipeManager manager, RecipeType<CraftingRecipe> type, RecipeInput input, Level level, Operation<Optional<RecipeHolder<CraftingRecipe>>> original
    ) {
        boolean suspended = RecipeContext.suspend();
        try {
            return original.call(manager, type, input, level);
        } finally {
            RecipeContext.resume(suspended);
        }
    }

    @ModifyReturnValue(method = "get", at = @At("RETURN"), require = 1)
    private Optional<RecipeHolder<CraftingRecipe>> kami$unlockedCached(Optional<RecipeHolder<CraftingRecipe>> found) {
        return RecipeFilter.optional(found);
    }
}
