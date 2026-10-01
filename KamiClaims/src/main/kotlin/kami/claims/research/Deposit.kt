package kami.claims.research

import kami.claims.service.Fail
import kami.claims.service.Service
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import kotlin.math.min

object Deposit {
    fun take(player: ServerPlayer, key: String, index: Int): Long {
        val country = Service.home(player)
        val entry = Queue.entry(country, key)
        val task = Research.defs.node(key)?.tasks?.getOrNull(index) as? DepositTask ?: throw Fail("kami_claims.research.error.no_deposit")
        if (entry.state != NodeState.QUEUED) throw Fail("kami_claims.research.error.tasks_closed")
        var left = task.target - (entry.tasks[index] ?: 0)
        if (left <= 0) throw Fail("kami_claims.research.error.task_done")
        val wanted = left
        val inventory = player.inventory
        for (stack in inventory.items) {
            if (stack.isEmpty || stack.isDamaged || !task.accepts(BuiltInRegistries.ITEM.getKey(stack.item).toString())) continue
            val moved = min(left, stack.count.toLong())
            stack.shrink(moved.toInt())
            left -= moved
            if (left == 0L) break
        }
        if (left == wanted) throw Fail("kami_claims.research.error.no_items")
        inventory.setChanged()
        return Queue.credit(country, key, index, wanted - left)
    }
}
