package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.claims.research.RecipeContext;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin {
    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"), require = 1)
    private void kami$blockEntityContext(TickingBlockEntity ticker, Operation<Void> original) {
        RecipeContext.run((Level) (Object) this, ticker.getPos().asLong(), () -> original.call(ticker));
    }

    @WrapMethod(method = "guardEntityTick", require = 1)
    private <T extends Entity> void kami$entityContext(Consumer<T> action, T entity, Operation<Void> original) {
        RecipeContext.run(entity, 0L, () -> original.call(action, entity));
    }
}
