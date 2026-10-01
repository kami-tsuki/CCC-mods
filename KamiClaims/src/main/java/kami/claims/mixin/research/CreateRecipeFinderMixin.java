package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import kami.claims.research.RecipeFilter;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.recipe.RecipeFinder")
public abstract class CreateRecipeFinderMixin {
    @ModifyReturnValue(method = "get", at = @At("RETURN"), require = 0)
    private static List<RecipeHolder<? extends Recipe<?>>> kami$unlockedSearch(List<RecipeHolder<? extends Recipe<?>>> recipes) {
        return RecipeFilter.holders(recipes);
    }
}
