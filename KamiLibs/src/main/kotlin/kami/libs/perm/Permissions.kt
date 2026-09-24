package kami.libs.perm

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.permission.PermissionAPI
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes

object Permissions {
    fun node(modId: String, path: String, default: (ServerPlayer?) -> Boolean): PermissionNode<Boolean> =
        PermissionNode(modId, path, PermissionTypes.BOOLEAN, PermissionNode.PermissionResolver { p, _, _ -> default(p) })

    fun register(e: PermissionGatherEvent.Nodes, vararg nodes: PermissionNode<Boolean>) = e.addNodes(*nodes)

    fun has(p: ServerPlayer, n: PermissionNode<Boolean>): Boolean =
        runCatching { PermissionAPI.getPermission(p, n) }.getOrElse { n.defaultResolver.resolve(p, p.uuid) }

    fun has(s: CommandSourceStack, n: PermissionNode<Boolean>): Boolean = s.player?.let { has(it, n) } ?: true
}
