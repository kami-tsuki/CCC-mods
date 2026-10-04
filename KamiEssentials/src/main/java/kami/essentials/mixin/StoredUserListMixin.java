package kami.essentials.mixin;

import com.mojang.authlib.GameProfile;
import kami.essentials.discord.Gate;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.server.players.UserBanList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StoredUserList.class)
public abstract class StoredUserListMixin {
    @Inject(method = "add(Lnet/minecraft/server/players/StoredUserEntry;)V", at = @At("TAIL"))
    private void kami$ban(StoredUserEntry<?> entry, CallbackInfo ci) {
        if ((Object) this instanceof UserBanList && ((StoredUserEntryAccess) entry).kami$user() instanceof GameProfile profile) Gate.INSTANCE.mcBanned(profile.getId());
    }
}
