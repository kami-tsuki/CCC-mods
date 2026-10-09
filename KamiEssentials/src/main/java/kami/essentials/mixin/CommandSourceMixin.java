package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(CommandSourceStack.class)
public abstract class CommandSourceMixin {
    @Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true)
    private void kami$hideInvisible(CallbackInfoReturnable<Collection<String>> cir) {
        CommandSourceStack self = (CommandSourceStack) (Object) this;
        ServerPlayer viewer = self.getPlayer();
        if (viewer == null) return;
        Collection<String> names = new ArrayList<>(cir.getReturnValue());
        names.removeIf(n -> {
            ServerPlayer p = self.getServer().getPlayerList().getPlayerByName(n);
            return p != null && Vanish.INSTANCE.hides(p, viewer);
        });
        cir.setReturnValue(names);
    }
}
