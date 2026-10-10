package kami.essentials.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import kami.essentials.skills.SkillEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @ModifyVariable(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private int kami$skillDurability(int amount, @Local(argsOnly = true) LivingEntity entity) {
        return entity instanceof ServerPlayer player ? SkillEvents.durability((ItemStack) (Object) this, player, amount) : amount;
    }
}
