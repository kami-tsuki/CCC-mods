package kami.claims.mixin.research;

import kami.claims.research.ItemGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneMenuMixin {
    @Shadow
    @Final
    Container repairSlots;

    @Shadow
    @Final
    private Container resultSlots;

    @Unique
    private ServerPlayer kami$player;

    @Unique
    private boolean kami$denied;

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("RETURN"), require = 1)
    private void kami$capture(int id, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
        if (inventory.player instanceof ServerPlayer sp) kami$player = sp;
    }

    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true, require = 1)
    private void kami$researchedItems(CallbackInfo ci) {
        ServerPlayer player = kami$player;
        if (player == null) return;
        ItemStack first = repairSlots.getItem(0);
        ItemStack second = repairSlots.getItem(1);
        if (ItemGate.allows(player, first) && ItemGate.allows(player, second)) {
            kami$denied = false;
            return;
        }
        resultSlots.setItem(0, ItemStack.EMPTY);
        if (!kami$denied) ItemGate.deny(player);
        kami$denied = true;
        ci.cancel();
    }
}
