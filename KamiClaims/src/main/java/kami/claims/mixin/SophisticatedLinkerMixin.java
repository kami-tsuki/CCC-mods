package kami.claims.mixin;

import kami.claims.Action;
import kami.claims.KamiClaims;
import kami.claims.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiFunction;

@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.linkedstorage.EnderLinkerItem")
public abstract class SophisticatedLinkerMixin {
    private static boolean kami$warned;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "linkWithFeedback", at = @At("HEAD"), cancellable = true, require = 0)
    private static void kami$claimedLink(Player player, ItemStack stack, BlockPos pos, BiFunction<?, ?, ?> link, CallbackInfoReturnable<Object> cir) {
        try {
            if (Guard.INSTANCE.blocked(player, pos, Action.CONTAINER)) {
                if (player instanceof ServerPlayer sp) Guard.INSTANCE.explain(sp, sp.level(), pos, Action.CONTAINER);
                cir.setReturnValue(Enum.valueOf((Class) Class.forName("net.p3pp3rf1y.sophisticatedcore.linkedstorage.LinkedStorageService$LinkResult"), "UNSUPPORTED_ENDPOINT"));
            }
        } catch (Throwable t) {
            if (!kami$warned) {
                kami$warned = true;
                KamiClaims.INSTANCE.getLOG().warn("Ender Linker claim check failed: {}", t.toString());
            }
        }
    }
}
