package kami.economy.mixin.coins;

import java.util.UUID;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEntity.class)
public interface ItemOwnerAccessor {
    @Accessor("thrower")
    UUID kamiThrower();
}
