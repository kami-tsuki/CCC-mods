package kami.claims.economy

import dev.ithundxr.createnumismatics.content.backend.Coin
import dev.ithundxr.createnumismatics.content.coins.CoinItem
import kami.libs.economy.CoinReturn
import kami.libs.economy.CoinSource
import kami.libs.economy.Coins
import kami.libs.economy.Flow
import kami.libs.economy.Statement
import kami.libs.economy.Numismatics
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

object Bank {
    private fun value(stack: ItemStack) = if (stack.isEmpty) 0 else (stack.item as? CoinItem)?.coin?.value ?: 0

    private fun carried(p: ServerPlayer) = p.inventory.items.sumOf { value(it).toLong() * it.count }

    fun funds(id: UUID): Long = Numismatics.balance(id) + (Numismatics.online(id)?.let(::carried) ?: 0)

    fun take(id: UUID, amount: Int, flow: Flow, counterparty: String = "", note: String = ""): Boolean =
        withdraw(id, amount).also { if (it && amount > 0) Statement.record(id, -amount.toLong(), flow, counterparty, Statement.note(note)) }

    fun give(id: UUID, amount: Int, flow: Flow, counterparty: String = "", note: String = ""): Boolean {
        val got = deliver(id, amount) ?: return false
        if (got > 0) Statement.record(id, got.toLong(), flow, counterparty, Statement.note(note))
        return true
    }

    private fun withdraw(id: UUID, amount: Int): Boolean {
        if (amount <= 0) return true
        if (funds(id) < amount) return false
        val deducted = Numismatics.deduct(id, amount)
        val rest = amount - deducted
        if (rest <= 0) return true
        val p = Numismatics.online(id)
        if (p == null || carried(p) < rest) {
            if (deducted > 0) Numismatics.deposit(id, deducted)
            return false
        }
        var removed = 0L
        p.inventory.items.map { it to value(it) }.filter { it.second > 0 }.sortedBy { it.second }.forEach { (stack, unit) ->
            while (removed < rest && !stack.isEmpty) {
                stack.shrink(1)
                removed += unit
            }
        }
        Coins.burned(CoinSource.KAMI, PlayerParty(id), removed)
        if (removed > rest) coins(p, (removed - rest).toInt())
        return removed >= rest
    }

    private fun deliver(id: UUID, amount: Int): Int? {
        if (amount <= 0) return 0
        if (Numismatics.deposit(id, amount)) return amount
        val p = Numismatics.online(id) ?: return null
        return coins(p, amount)
    }

    private fun coins(p: ServerPlayer, amount: Int): Int {
        var rest = amount
        Coin.valuesHighToLow().filter { it.value > 0 }.forEach { coin ->
            val n = rest / coin.value
            if (n <= 0) return@forEach
            var left = n
            while (left > 0) {
                val stack = coin.asStack(minOf(left, 64))
                if (stack.isEmpty) break
                left -= stack.count
                rest -= stack.count * coin.value
                if (!p.inventory.add(stack) && !CoinReturn.keep(p.uuid, stack)) p.drop(stack, false)
            }
        }
        val sent = amount - rest
        Coins.minted(CoinSource.KAMI, PlayerParty(p.uuid), sent.toLong())
        if (rest > 0) ServerBank.collect(PlayerParty(p.uuid), rest.toLong(), Flow.ROUNDING)
        return sent
    }
}
