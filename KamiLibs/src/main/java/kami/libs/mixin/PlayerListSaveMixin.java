package kami.libs.mixin;

import kami.libs.db.SavePoint;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerListSaveMixin {
    @Inject(method = "saveAll", at = @At("HEAD"))
    private void kami$saveFirst(CallbackInfo ci) {
        SavePoint.INSTANCE.run();
    }
}
