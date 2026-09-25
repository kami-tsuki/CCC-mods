package kami.essentials

import kami.libs.perm.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode

object Perms {
    private val all = mutableListOf<PermissionNode<Boolean>>()

    private fun node(path: String, everyone: Boolean) =
        Permissions.node(KamiEssentials.ID, path) { p -> everyone || p?.hasPermissions(2) ?: true }.also { all += it }

    val INVSEE = node("invsee", false)
    val INVSEE_EDIT = node("invsee.edit", false)
    val ENDERCHEST = node("enderchest", false)
    val ENDERCHEST_OTHERS = node("enderchest.others", false)
    val ENDERCHEST_EDIT = node("enderchest.edit", false)
    val BALANCE = node("balance", true)
    val BALANCE_OTHERS = node("balance.others", false)
    val INVIS = node("invis", false)
    val INVIS_OTHERS = node("invis.others", false)
    val INVIS_SEE = node("invis.see", false)
    val SCOREBOARD = node("scoreboard", true)
    val MSG = node("msg", true)
    val TRADE = node("trade", true)
    val TRADE_ANYWHERE = node("trade.anywhere", false)
    val COUNTRYCHAT = node("countrychat", true)

    fun register(e: PermissionGatherEvent.Nodes) = Permissions.register(e, *all.toTypedArray())

    fun has(p: ServerPlayer, n: PermissionNode<Boolean>) = Permissions.has(p, n)
    fun has(s: CommandSourceStack, n: PermissionNode<Boolean>) = Permissions.has(s, n)
    fun gate(n: PermissionNode<Boolean>): (CommandSourceStack) -> Boolean = { has(it, n) }
}
