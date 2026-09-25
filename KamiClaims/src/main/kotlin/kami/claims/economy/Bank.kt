package kami.claims.economy

import kami.libs.economy.Coins
import kami.libs.economy.Numismatics

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

object Bank {
    private fun value(stack: ItemStack) = if (stack.isEmpty) 0 else Coins.values[BuiltInRegistries.ITEM.getKey(stack.item).toString()] ?: 0

    private fun carried(p: ServerPlayer) = p.inventory.items.sumOf { value(it).toLong() * it.count }

    fun funds(id: UUID): Long = Numismatics.balance(id) + (Numismatics.online(id)?.let(::carried) ?: 0)

    fun take(id: UUID, amount: Int): Boolean {
        if (amount <= 0) return true
        if (funds(id) < amount) return false
        var rest = amount - Numismatics.deduct(id, amount)
        if (rest > 0) Numismatics.online(id)?.let { p ->
            var removed = 0L
            p.inventory.items.filter { value(it) > 0 }.sortedBy { value(it) }.forEach { stack ->
                val unit = value(stack)
                while (removed < rest && !stack.isEmpty) {
                    stack.shrink(1)
                    removed += unit
                }
            }
            if (removed > rest) coins(p, (removed - rest).toInt())
        }
        return true
    }

    fun give(id: UUID, amount: Int): Boolean {
        if (amount <= 0) return true
        if (Numismatics.deposit(id, amount)) return true
        val p = Numismatics.online(id) ?: return false
        coins(p, amount)
        return true
    }

    private fun coins(p: ServerPlayer, amount: Int) {
        var rest = amount
        Coins.values.entries.sortedByDescending { it.value }.forEach { (item, unit) ->
            val n = rest / unit
            if (n <= 0) return@forEach
            rest -= n * unit
            var left = n
            while (left > 0) {
                val stack = ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)), minOf(left, 64))
                left -= stack.count
                if (!p.inventory.add(stack)) p.drop(stack, false)
            }
        }
    }
}
