package kami.essentials.mixin;

import kami.essentials.vanish.Vanish;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Function;

@Mixin(ListPlayersCommand.class)
public abstract class ListCommandMixin {
    @Inject(method = "format", at = @At("HEAD"), cancellable = true)
    private static void kami$hideInvisible(CommandSourceStack source, Function<ServerPlayer, Component> nameGetter, CallbackInfoReturnable<Integer> cir) {
        ServerPlayer viewer = source.getPlayer();
        if (viewer == null) return;
        PlayerList list = source.getServer().getPlayerList();
        List<ServerPlayer> shown = list.getPlayers().stream().filter(p -> !Vanish.INSTANCE.hides(p, viewer)).toList();
        source.sendSuccess(() -> Component.translatable("commands.players.list", shown.size(), list.getMaxPlayers(), ComponentUtils.formatList(shown, nameGetter)), false);
        cir.setReturnValue(shown.size());
    }
}
