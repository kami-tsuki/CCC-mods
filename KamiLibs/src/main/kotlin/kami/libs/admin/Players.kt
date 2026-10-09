package kami.libs.admin

import com.mojang.authlib.GameProfile
import net.minecraft.server.MinecraftServer
import kami.libs.util.toUuidOrNull
import java.util.UUID

object Players {
    @Volatile
    var known: ((String) -> UUID?)? = null

    fun resolve(server: MinecraftServer, raw: String): UUID? {
        val s = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        return s.toUuidOrNull()
            ?: server.playerList.getPlayerByName(s)?.uuid
            ?: known?.invoke(s)
    }

    fun name(server: MinecraftServer, id: UUID): String? =
        server.playerList.getPlayer(id)?.name?.string ?: server.profileCache?.get(id)?.orElse(null)?.name

    fun profile(server: MinecraftServer, id: UUID): GameProfile = server.playerList.getPlayer(id)?.gameProfile
        ?: GameProfile(id, name(server, id) ?: id.toString())
}
