package kami.economy.mixin.villager;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kami.economy.villager.Quotes;
import kami.economy.villager.VillagerTrade;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerMixin {
    @WrapOperation(method = "startTrading", require = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;openTradingScreen(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/network/chat/Component;I)V"))
    private void kami$open(Villager self, Player player, Component title, int level, Operation<Void> original) {
        if (!VillagerTrade.open(self, player, title)) original.call(self, player, title, level);
    }

    @Inject(method = {"restock", "catchUpDemand"}, require = 2, at = @At("TAIL"))
    private void kami$restock(CallbackInfo ci) {
        Quotes.restocked((Villager) (Object) this);
    }
}
