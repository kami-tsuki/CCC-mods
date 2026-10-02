package kami.claims.client

import kami.claims.client.store.ClientResearch
import kami.libs.mc.ItemSpec
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object ResearchTooltip {
    private const val MAX_NODES = 3
    private val cache = HashMap<String, List<Component>>()
    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        ClientResearch.listen { cache.clear() }
        FORGE_BUS.addListener<ItemTooltipEvent> { event ->
            val id = ItemSpec.spec(event.itemStack)
            event.toolTip.addAll(cache.getOrPut(id) { lines(id) })
        }
    }

    private fun lines(itemId: String): List<Component> {
        val base = ItemSpec.base(itemId)
        val locking = (ClientResearch.recipesLockingItem(itemId).ifEmpty { ClientResearch.recipesLockingItem(base) }) + listOfNotNull(base.takeIf { it in ClientResearch.lockedBlocks() })
        if (locking.isEmpty()) return emptyList()
        val nodes = locking.flatMap(ClientResearch::unlockedBy).distinct().mapNotNull(ClientResearch::node).take(MAX_NODES).map { node ->
            val tree = ClientResearch.defs.trees.firstOrNull { it.id == node.tree }?.label()?.resolve().orEmpty()
            Component.translatable("kami_claims.research.tooltip.node", node.label().resolve(), tree)
        }
        val level = locking.flatMap(ClientResearch::unlockedByLevels).minOrNull()?.let { Component.translatable("kami_claims.research.tooltip.level", it) }
        return (nodes + listOfNotNull(level)).map { it.withStyle(ChatFormatting.RED) }
    }
}
