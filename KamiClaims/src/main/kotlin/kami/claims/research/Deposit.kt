package kami.claims.research

import kami.claims.service.Fail
import kami.claims.service.Service
import kami.libs.skills.SkillClass
import kami.libs.skills.Skills
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import kotlin.math.ln
import kotlin.math.min

object Deposit {
    private fun hasContents(stack: ItemStack) =
        stack.get(DataComponents.CONTAINER)?.nonEmptyItems()?.iterator()?.hasNext() == true ||
            stack.get(DataComponents.BUNDLE_CONTENTS)?.isEmpty == false

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
            if (stack.isEmpty || stack.isDamaged || hasContents(stack) || !task.accepts(BuiltInRegistries.ITEM.getKey(stack.item).toString())) continue
            val moved = min(left, stack.count.toLong())
            stack.shrink(moved.toInt())
            left -= moved
            if (left == 0L) break
        }
        if (left == wanted) throw Fail("kami_claims.research.error.no_items")
        inventory.setChanged()
        val moved = wanted - left
        Skills.grant(player.uuid, SkillClass.RESEARCHER, "deposit", ln(1.0 + moved), "deposit:$key")
        return Queue.credit(country, key, index, moved + (moved * Skills.value(player.uuid, Skills.RESEARCH_INSIGHT)).toLong())
    }
}
