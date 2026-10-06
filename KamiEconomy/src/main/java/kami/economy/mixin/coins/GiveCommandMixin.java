package kami.economy.mixin.coins;

import java.util.Collection;
import kami.economy.world.CoinRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {
    @Inject(method = "giveItem", at = @At("HEAD"), cancellable = true)
    private static void kami$coinGive(CommandSourceStack source, ItemInput input, Collection<ServerPlayer> targets, int count, CallbackInfoReturnable<Integer> cir) {
        if (CoinRules.refuse(source, input.getItem())) cir.setReturnValue(0);
    }
}
