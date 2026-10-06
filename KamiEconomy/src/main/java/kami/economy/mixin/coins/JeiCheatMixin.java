package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.sugar.Local;
import kami.economy.world.CoinRules;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mezz.jei.common.util.ServerCommandUtil")
public abstract class JeiCheatMixin {
    @Inject(method = {"executeGive", "setHotbarSlot", "mousePickupItemStack", "giveToInventory"}, at = @At("HEAD"), cancellable = true)
    private static void kami$coinCheat(CallbackInfo ci, @Local(argsOnly = true) ItemStack stack) {
        if (CoinRules.isCoin(stack)) ci.cancel();
    }
}
