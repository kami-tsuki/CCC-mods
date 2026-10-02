package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kami.claims.research.RecipeFilter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.tacz.guns.inventory.GunSmithTableMenu")
public abstract class TaczGunSmithMixin {
    @WrapMethod(method = "doCraft(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/entity/player/Player;)V", require = 1)
    private void kami$unlockedCraft(ResourceLocation recipe, Player player, Operation<Void> original) {
        if (player instanceof ServerPlayer serverPlayer && RecipeFilter.denyCraft(serverPlayer, recipe)) return;
        original.call(recipe, player);
    }
}
