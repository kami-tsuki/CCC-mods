package kami.economy.mixin.coins;

import java.util.Collection;
import kami.economy.world.CoinRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.ItemCommands;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemCommands.class)
public abstract class ItemCommandsMixin {
    @Inject(method = "setBlockItem", at = @At("HEAD"), cancellable = true)
    private static void kami$coinBlock(CommandSourceStack source, BlockPos pos, int slot, ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (CoinRules.refuse(source, stack.getItem())) cir.setReturnValue(0);
    }

    @Inject(method = "setEntityItem", at = @At("HEAD"), cancellable = true)
    private static void kami$coinEntity(CommandSourceStack source, Collection<? extends Entity> targets, int slot, ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (CoinRules.refuse(source, stack.getItem())) cir.setReturnValue(0);
    }
}
