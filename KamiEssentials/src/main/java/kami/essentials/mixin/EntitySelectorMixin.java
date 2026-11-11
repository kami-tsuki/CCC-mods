package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Inject(method = "findEntities", at = @At("RETURN"), cancellable = true)
    private void kami$hideEntities(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> cir) {
        ServerPlayer viewer = source.getPlayer();
        if (viewer == null) return;
        List<Entity> list = new ArrayList<>(cir.getReturnValue());
        if (list.removeIf(e -> e instanceof ServerPlayer p && Vanish.INSTANCE.hides(p, viewer))) cir.setReturnValue(list);
    }

    @Inject(method = "findPlayers", at = @At("RETURN"), cancellable = true)
    private void kami$hidePlayers(CommandSourceStack source, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        ServerPlayer viewer = source.getPlayer();
        if (viewer == null) return;
        List<ServerPlayer> list = new ArrayList<>(cir.getReturnValue());
        if (list.removeIf(p -> Vanish.INSTANCE.hides(p, viewer))) cir.setReturnValue(list);
    }
}
