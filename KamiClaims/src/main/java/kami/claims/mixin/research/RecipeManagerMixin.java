package kami.claims.mixin.research;

import com.google.common.collect.Multimap;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.Optional;
import kami.claims.research.RecipeContext;
import kami.claims.research.RecipeFilter;
import kami.claims.research.RecipeScope;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow
    private Multimap<RecipeType<?>, RecipeHolder<?>> byType;

    @WrapMethod(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;", require = 1)
    @SuppressWarnings("unchecked")
    private <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> kami$unlockedRecipe(
        RecipeType<T> type, I input, Level level, RecipeHolder<T> hint, Operation<Optional<RecipeHolder<T>>> original
    ) {
        Optional<RecipeHolder<T>> found = original.call(type, input, level, hint);
        if (found.isEmpty() || !RecipeFilter.locked(found.get())) return found;
        RecipeScope scope = RecipeContext.scope();
        if (scope == null) return found;
        for (RecipeHolder<?> candidate : byType.get(type)) {
            RecipeHolder<T> holder = (RecipeHolder<T>) candidate;
            if (scope.allows(holder.id()) && holder.value().matches(input, level)) return Optional.of(holder);
        }
        return Optional.empty();
    }

    @ModifyReturnValue(method = "getAllRecipesFor", at = @At("RETURN"), require = 1)
    private <T extends Recipe<?>> List<RecipeHolder<T>> kami$unlockedAll(List<RecipeHolder<T>> recipes) {
        return RecipeFilter.holders(recipes);
    }

    @ModifyReturnValue(method = "getRecipesFor", at = @At("RETURN"), require = 1)
    private <T extends Recipe<?>> List<RecipeHolder<T>> kami$unlockedMatching(List<RecipeHolder<T>> recipes) {
        return RecipeFilter.holders(recipes);
    }
}
