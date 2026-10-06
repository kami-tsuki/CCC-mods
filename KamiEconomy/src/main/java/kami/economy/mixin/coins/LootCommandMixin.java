package kami.economy.mixin.coins;

import java.util.List;
import kami.economy.world.CoinRules;
import net.minecraft.server.commands.LootCommand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LootCommand.class)
public abstract class LootCommandMixin {
    @ModifyVariable(method = "playerGive", at = @At("HEAD"), argsOnly = true)
    private static List<ItemStack> kami$coinLoot(List<ItemStack> stacks) {
        return CoinRules.withoutCoins(stacks);
    }
}
