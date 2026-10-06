package kami.libs.ui.text

import kami.libs.mc.ItemSpec
import kami.libs.text.SearchQuery
import kami.libs.text.SearchTerm
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.neoforged.fml.ModList
import java.util.WeakHashMap

object ItemSearch {
    private class Facts(stack: ItemStack, val spec: String) {
        val empty = stack.isEmpty
        val name = stack.hoverName.string.lowercase()
        val path = spec.substringAfter(':').lowercase()
        val namespace = spec.substringBefore(':').lowercase()
        val mod = ModList.get().getModContainerById(namespace).map { it.modInfo.displayName.lowercase() }.orElse("")
        private var tags: List<String>? = null
        private var tooltip: String? = null

        private fun tags(stack: () -> ItemStack): List<String> =
            tags ?: stack().tags.map { it.location.toString().lowercase() }.toList().also { tags = it }

        private fun tooltip(stack: () -> ItemStack): String {
            tooltip?.let { return it }
            val mc = Minecraft.getInstance()
            val level = mc.level
            val player = mc.player
            if (level == null || player == null) return ""
            val text = runCatching {
                stack().getTooltipLines(Item.TooltipContext.of(level), player, TooltipFlag.NORMAL).joinToString(" ") { it.string }.lowercase()
            }.getOrNull() ?: return ""
            tooltip = text
            return text
        }

        fun test(t: SearchTerm, extra: Array<out String>, stack: () -> ItemStack): Boolean = when (t.prefix) {
            null -> name.contains(t.text) || path.contains(t.text) || extra.any { it.contains(t.text, true) }
            '@' -> namespace.contains(t.text) || mod.contains(t.text)
            '#' -> tags(stack).any { it.contains(t.text) }
            else -> tooltip(stack).contains(t.text)
        }
    }

    private val byStack = WeakHashMap<ItemStack, Facts>()
    private val bySpec = HashMap<String, Facts>()
    private val registryIds: List<String> by lazy {
        val air = BuiltInRegistries.ITEM.getKey(Items.AIR).toString()
        BuiltInRegistries.ITEM.keySet().map { it.toString() }.filter { it != air }
    }

    fun matches(q: SearchQuery, stack: ItemStack, vararg extra: String): Boolean {
        if (q.isEmpty) return true
        val facts = byStack.getOrPut(stack) { Facts(stack, ItemSpec.spec(stack)) }
        return q.matches { facts.test(it, extra) { stack } }
    }

    fun ids(q: SearchQuery, extra: Collection<String> = emptyList()): Set<String> {
        val specs = (registryIds + extra).toSet()
        return specs.filterTo(HashSet()) { spec ->
            val facts = bySpec.getOrPut(spec) { Facts(ItemSpec.stack(spec), spec) }
            !facts.empty && q.matches { facts.test(it, emptyArray()) { ItemSpec.stack(spec) } }
        }
    }
}
