package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.Optional;
import kami.claims.research.RecipeFilter;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeCache;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RecipeCache.class)
public abstract class RecipeCacheMixin {
    @ModifyReturnValue(method = "get", at = @At("RETURN"), require = 1)
    private Optional<RecipeHolder<CraftingRecipe>> kami$unlockedCached(Optional<RecipeHolder<CraftingRecipe>> found) {
        return RecipeFilter.optional(found);
    }
}
