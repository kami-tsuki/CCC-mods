package kami.economy.villager

import dev.ithundxr.createnumismatics.content.backend.IDeductable
import dev.ithundxr.createnumismatics.content.backend.ReasonHolder
import dev.ithundxr.createnumismatics.content.bank.AuthorizedCardItem
import dev.ithundxr.createnumismatics.content.bank.CardItem
import kami.libs.economy.MoneyGuard
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.util.UUID

object Cards {
    fun isCard(s: ItemStack) = s.item is CardItem || s.item is AuthorizedCardItem

    fun account(s: ItemStack): UUID? = when (s.item) {
        is CardItem -> CardItem.get(s)
        is AuthorizedCardItem -> AuthorizedCardItem.get(s)?.accountID()
        else -> null
    }

    fun label(s: ItemStack): String = when (s.item) {
        is CardItem -> CardItem.getPlayerName(s)
        is AuthorizedCardItem -> AuthorizedCardItem.getAccountLabel(s)
        else -> null
    }.orEmpty()

    private fun deductor(p: Player, s: ItemStack): IDeductable? = if (isCard(s)) runCatching { IDeductable.get(s, p, ReasonHolder.IGNORED) }.getOrNull() else null

    fun usable(p: Player, s: ItemStack) = account(s) != null && deductor(p, s) != null

    fun balance(p: Player, s: ItemStack): Long = deductor(p, s)?.maxWithdrawal?.toLong() ?: 0

    fun charge(p: Player, s: ItemStack, amount: Long): Boolean {
        if (amount !in 0..Int.MAX_VALUE.toLong()) return false
        val d = deductor(p, s) ?: return false
        return MoneyGuard.explained("villager") { d.deduct(amount.toInt(), ReasonHolder.IGNORED) }
    }
}
