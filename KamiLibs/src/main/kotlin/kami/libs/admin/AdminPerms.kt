package kami.libs.admin

import com.mojang.authlib.GameProfile
import kami.libs.KamiLibs
import kami.libs.chat.tell
import kami.libs.perm.PermissionSet
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

object AdminPerms : PermissionSet(KamiLibs.ID) {
    enum class Level { NONE, VIEW, MANAGE }

    val VIEW = node("admin.view") { it?.hasPermissions(2) == true }
    val MANAGE = node("admin.manage") { it?.hasPermissions(3) == true }

    fun level(p: ServerPlayer) = when {
        has(p, MANAGE) -> Level.MANAGE
        has(p, VIEW) -> Level.VIEW
        else -> Level.NONE
    }

    fun notifyManagers(server: MinecraftServer, msg: Component) = server.playerList.players.filter { level(it) == Level.MANAGE }.forEach { it.tell(msg) }

    fun rank(p: ServerPlayer) = maxOf(
        p.server.getProfilePermissions(p.gameProfile),
        when (level(p)) {
            Level.MANAGE -> 3
            Level.VIEW -> 2
            Level.NONE -> 0
        }
    )

    fun outranks(actor: ServerPlayer, target: GameProfile) =
        rank(actor) > (actor.server.playerList.getPlayer(target.id)?.let(::rank) ?: actor.server.getProfilePermissions(target))
}
