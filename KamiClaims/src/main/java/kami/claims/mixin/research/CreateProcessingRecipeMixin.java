package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import kami.claims.research.Processing;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.processing.recipe.ProcessingRecipe")
public abstract class CreateProcessingRecipeMixin {
    @ModifyReturnValue(
        method = "rollResults(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/util/List;",
        at = @At("RETURN"),
        require = 0
    )
    private List<ItemStack> kami$countProcess(List<ItemStack> results) {
        Processing.rolled((Recipe<?>) (Object) this, results);
        return results;
    }
}
