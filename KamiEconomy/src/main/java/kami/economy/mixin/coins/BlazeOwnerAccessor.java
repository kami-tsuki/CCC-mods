package kami.economy.mixin.coins;

import dev.ithundxr.createnumismatics.content.bank.blaze_banker.BlazeBankerBlockEntity;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlazeBankerBlockEntity.class)
public interface BlazeOwnerAccessor {
    @Accessor("owner")
    UUID kamiBankerOwner();
}
