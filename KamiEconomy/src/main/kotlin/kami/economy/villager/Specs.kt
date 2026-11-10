package kami.economy.villager

import kami.economy.economy.EnchantBooks
import kami.libs.mc.ItemSpec
import kami.libs.mc.ItemSpecs
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class Enchant(val id: String, val level: Int, val treasure: Boolean) {
    val book get() = EnchantBooks.spec(id, 1)
}

class Spec(val key: String, val base: String, val enchants: List<Enchant>, val market: Boolean)

object Specs {
    const val EMERALD = "minecraft:emerald"
    const val EMERALD_BLOCK = "minecraft:emerald_block"
    private const val BOOK = "minecraft:book"
    private const val ENCHANTED_BOOK = "minecraft:enchanted_book"

    fun short(id: String) = id.removePrefix("minecraft:").replace(':', '_')

    fun key(base: String, parts: List<String>) = ItemSpecs.join(base, parts.sorted().joinToString("+"))

    fun emeralds(id: String, count: Int) = when (id) {
        EMERALD -> count
        EMERALD_BLOCK -> count * 9
        else -> null
    }

    fun of(stack: ItemStack): Spec {
        EnchantBooks.spec(stack)?.let { return Spec(it, it, emptyList(), true) }
        val id = ItemSpec.id(stack)
        val enchants = (stack.get(DataComponents.ENCHANTMENTS)?.entrySet().orEmpty() + stack.get(DataComponents.STORED_ENCHANTMENTS)?.entrySet().orEmpty()).mapNotNull { e ->
            e.key.unwrapKey().orElse(null)?.location()?.toString()?.let { Enchant(it, e.intValue, e.key.`is`(EnchantmentTags.DOUBLE_TRADE_PRICE)) }
        }
        val parts = enchants.map { short(it.id) + it.level } + extras(stack)
        if (parts.isEmpty()) return Spec(id, id, emptyList(), true)
        return Spec(key(id, parts), if (id == ENCHANTED_BOOK && enchants.isNotEmpty()) BOOK else id, enchants, false)
    }

    private fun extras(stack: ItemStack): List<String> {
        val potion = stack.get(DataComponents.POTION_CONTENTS)?.potion()?.flatMap { it.unwrapKey() }?.orElse(null)?.location()?.toString()
        val stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS)?.effects().orEmpty().mapNotNull { e -> e.effect().unwrapKey().orElse(null)?.location()?.toString() }
        val map = if (stack.`is`(Items.FILLED_MAP)) stack.get(DataComponents.MAP_DECORATIONS)?.decorations()?.values.orEmpty()
            .mapNotNull { d -> d.type().unwrapKey().orElse(null)?.location()?.toString() } else emptyList()
        return (listOfNotNull(potion) + stew + map).map(::short).distinct()
    }
}
