package kami.libs.economy

import dev.ithundxr.createnumismatics.Numismatics as Nu
import dev.ithundxr.createnumismatics.content.backend.BankAccount
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import kami.libs.mixin.NumismaticsBankAccountMixin
import java.util.UUID

object Numismatics {
    fun online(id: UUID): ServerPlayer? = ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(id)

    fun balance(id: UUID): Long = Nu.BANK.getAccount(id)?.let(::total) ?: 0

    private fun total(account: BankAccount): Long = account.balance + (account as NumismaticsBankAccountMixin).`kami$additional`()

    fun deduct(id: UUID, amount: Int): Int {
        if (amount <= 0) return 0
        val account = Nu.BANK.getAccount(id) ?: return 0
        val have = total(account)
        val part = minOf(amount.toLong(), have).toInt()
        return if (Money.canDebit(have, part.toLong()) && MoneyGuard.explained("kami") { account.deduct(part, false) }) part else 0
    }

    fun balances(): List<Pair<UUID, Long>> =
        runCatching { Nu.BANK.accounts.values.filter { it.type == BankAccount.Type.PLAYER }.map { it.id to total(it) } }.getOrDefault(emptyList())

    fun richest(limit: Int): List<Pair<UUID, Long>> = balances().sortedByDescending { it.second }.take(limit)

    fun deposit(id: UUID, amount: Int): Boolean {
        if (amount < 0) return false
        if (amount == 0) return true
        return MoneyGuard.explained("kami") {
            val account = Nu.BANK.getOrCreateAccount(id, BankAccount.Type.PLAYER)
            Money.canCredit(total(account), amount.toLong()).also { if (it) account.deposit(amount) }
        }
    }
}
