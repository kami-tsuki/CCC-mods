package kami.libs.economy

import dev.ithundxr.createnumismatics.Numismatics as Nu
import dev.ithundxr.createnumismatics.content.backend.BankAccount
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object Numismatics {
    fun online(id: UUID): ServerPlayer? = ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(id)

    fun balance(id: UUID): Long = Nu.BANK.getAccount(id)?.balance?.toLong() ?: 0

    fun deduct(id: UUID, amount: Int): Int {
        val account = Nu.BANK.getAccount(id) ?: return 0
        val part = minOf(amount, account.balance)
        return if (part > 0 && account.deduct(part, false)) part else 0
    }

    fun deposit(id: UUID, amount: Int): Boolean {
        if (amount <= 0) return true
        val account = Nu.BANK.getOrCreateAccount(id, BankAccount.Type.PLAYER)
        account.deposit(amount)
        return true
    }
}
