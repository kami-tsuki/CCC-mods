package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import kami.claims.research.RecipeFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.crafting.BlockRecipeFinder")
public abstract class CbcBlockRecipeFinderMixin {
    @ModifyReturnValue(method = "get", at = @At("RETURN"), require = 0)
    private static List<Object> kami$unlockedResults(List<Object> recipes) {
        return RecipeFilter.blockRecipes(recipes);
    }
}
