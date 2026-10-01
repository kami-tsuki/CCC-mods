package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import java.util.Optional;
import kami.claims.research.RecipeFilter;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe")
public abstract class CreateSequencedAssemblyMixin {
    @ModifyReturnValue(method = "getRecipe", at = @At("RETURN"), require = 0)
    private static Optional<RecipeHolder<?>> kami$unlockedStep(Optional<RecipeHolder<?>> found) {
        return RecipeFilter.optional(found);
    }

    @ModifyReturnValue(method = "getRecipes", at = @At("RETURN"), require = 0)
    private static List<RecipeHolder<?>> kami$unlockedSteps(List<RecipeHolder<?>> recipes) {
        return RecipeFilter.holders(recipes);
    }
}
