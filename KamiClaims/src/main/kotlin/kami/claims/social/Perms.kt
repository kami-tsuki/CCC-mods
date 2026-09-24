package kami.claims.social

import kami.claims.Cap
import kami.claims.KamiClaims
import kami.libs.perm.Permissions

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode

object Perms {
    private fun node(path: String, default: (ServerPlayer?) -> Boolean) = Permissions.node(KamiClaims.ID, path, default)

    val USE = node("use") { true }
    val FOUND = node("found") { true }
    val CLAIM = node("claim") { true }
    val PLOT = node("plot") { true }
    val JOBS = node("jobs") { true }
    val ADMIN = node("admin") { it?.hasPermissions(2) ?: true }
    val BYPASS = node("bypass") { it?.hasPermissions(2) ?: false }

    private val capNodes: Map<Cap, PermissionNode<Boolean>> = Cap.values().associateWith { node("cap.${it.name.lowercase()}") { true } }
    fun capNode(cap: Cap): PermissionNode<Boolean> = capNodes.getValue(cap)

    fun register(e: PermissionGatherEvent.Nodes) = Permissions.register(e, USE, FOUND, CLAIM, PLOT, JOBS, ADMIN, BYPASS, *capNodes.values.toTypedArray())

    fun has(p: ServerPlayer, n: PermissionNode<Boolean>) = Permissions.has(p, n)
    fun has(s: CommandSourceStack, n: PermissionNode<Boolean>) = Permissions.has(s, n)
}
