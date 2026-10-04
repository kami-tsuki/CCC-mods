package kami.essentials.mixin;

import net.minecraft.server.players.StoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StoredUserEntry.class)
public interface StoredUserEntryAccess {
    @Invoker("getUser")
    Object kami$user();
}
