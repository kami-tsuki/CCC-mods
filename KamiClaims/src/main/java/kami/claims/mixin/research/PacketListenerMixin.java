package kami.claims.mixin.research;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kami.claims.research.RecipeContext;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PacketListenerMixin {
    @WrapMethod(method = "handleContainerClick", require = 1)
    private void kami$containerClick(ServerboundContainerClickPacket packet, Operation<Void> original) {
        boolean pushed = kami$push();
        try {
            original.call(packet);
        } finally {
            RecipeContext.pop(pushed);
        }
    }

    @WrapMethod(method = "handlePlaceRecipe", require = 1)
    private void kami$placeRecipe(ServerboundPlaceRecipePacket packet, Operation<Void> original) {
        boolean pushed = kami$push();
        try {
            original.call(packet);
        } finally {
            RecipeContext.pop(pushed);
        }
    }

    @WrapMethod(method = "handleContainerButtonClick", require = 1)
    private void kami$buttonClick(ServerboundContainerButtonClickPacket packet, Operation<Void> original) {
        boolean pushed = kami$push();
        try {
            original.call(packet);
        } finally {
            RecipeContext.pop(pushed);
        }
    }

    @WrapMethod(method = "handleUseItemOn", require = 1)
    private void kami$useItemOn(ServerboundUseItemOnPacket packet, Operation<Void> original) {
        boolean pushed = kami$push();
        try {
            original.call(packet);
        } finally {
            RecipeContext.pop(pushed);
        }
    }

    @WrapMethod(method = "handleUseItem", require = 1)
    private void kami$useItem(ServerboundUseItemPacket packet, Operation<Void> original) {
        boolean pushed = kami$push();
        try {
            original.call(packet);
        } finally {
            RecipeContext.pop(pushed);
        }
    }

    private boolean kami$push() {
        return RecipeContext.push(((ServerGamePacketListenerImpl) (Object) this).getPlayer(), 0L);
    }
}
