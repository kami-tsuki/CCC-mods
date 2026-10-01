package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import kami.claims.research.RecipeContext;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPayloadContext.class)
public abstract class PayloadContextMixin {
    @WrapOperation(method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/thread/ReentrantBlockableEventLoop;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"), require = 1)
    private CompletableFuture<Void> kami$payloadContext(ReentrantBlockableEventLoop<?> loop, Runnable task, Operation<CompletableFuture<Void>> original) {
        if (((ServerPayloadContext) (Object) this).listener() instanceof ServerGamePacketListenerImpl game) {
            return original.call(loop, RecipeContext.wrap(game.getPlayer(), task));
        }
        return original.call(loop, task);
    }

    @WrapOperation(method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/thread/ReentrantBlockableEventLoop;submit(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"), require = 1)
    private <T> CompletableFuture<T> kami$payloadSupplierContext(ReentrantBlockableEventLoop<?> loop, Supplier<T> task, Operation<CompletableFuture<T>> original) {
        if (((ServerPayloadContext) (Object) this).listener() instanceof ServerGamePacketListenerImpl game) {
            return original.call(loop, RecipeContext.wrap(game.getPlayer(), task));
        }
        return original.call(loop, task);
    }
}
