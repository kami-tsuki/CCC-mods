package kami.libs.admin

import com.mojang.authlib.GameProfile
import net.minecraft.server.MinecraftServer
import java.util.UUID

object Players {
    @Volatile
    var known: ((String) -> UUID?)? = null

    fun resolve(server: MinecraftServer, raw: String): UUID? {
        val s = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching { UUID.fromString(s) }.getOrNull()
            ?: server.playerList.getPlayerByName(s)?.uuid
            ?: known?.invoke(s)
    }

    fun profile(server: MinecraftServer, id: UUID): GameProfile = server.playerList.getPlayer(id)?.gameProfile
        ?: GameProfile(id, server.profileCache?.get(id)?.orElse(null)?.name ?: id.toString())
}
