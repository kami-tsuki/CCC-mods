package kami.essentials

import kami.libs.perm.PermissionSet
import net.minecraft.commands.CommandSourceStack
import net.neoforged.neoforge.server.permission.nodes.PermissionNode

object Perms : PermissionSet(KamiEssentials.ID) {
    private fun node(path: String, everyone: Boolean) = node(path) { p -> everyone || p?.hasPermissions(2) ?: true }

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
    val INLINE_OPEN = node("chat.inline.open", true)
    val TRADE = node("trade", true)
    val TRADE_ANYWHERE = node("trade.anywhere", false)
    val COUNTRYCHAT = node("countrychat", true)
    val ADMINCHAT = node("adminchat", false)
    val UNSTUCK = node("unstuck", true)
    val DISCORD_ADMIN = node("discord.admin", false)
    val DISCORD_RELOAD = node("discord.reload", false)

    fun gate(n: PermissionNode<Boolean>): (CommandSourceStack) -> Boolean = { has(it, n) }
}
