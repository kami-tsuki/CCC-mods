package kami.essentials.mixin;

import kami.essentials.skills.Effects;
import kami.essentials.skills.SkillSide;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class ArmorSlotMixin {
    @Shadow @Final private LivingEntity owner;
    @Shadow @Final private EquipmentSlot slot;

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true, require = 1)
    private void kami$showman(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (slot == EquipmentSlot.HEAD && !stack.isEmpty() && owner instanceof Player player && SkillSide.INSTANCE.value(player, Effects.SHOWMAN) > 0) cir.setReturnValue(true);
    }
}
