package kami.economy.mixin.numismatics;

import dev.ithundxr.createnumismatics.content.bank.BankMenu;
import kami.economy.economy.CoinHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BankMenu.class)
public abstract class BankMenuMixin {
    @Inject(method = "removed", at = @At("RETURN"))
    private void kami$closed(Player player, CallbackInfo ci) {
        CoinHooks.closed((BankMenu) (Object) this, player);
    }
}
