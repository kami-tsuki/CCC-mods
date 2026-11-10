package kami.economy.economy

import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.EnchantedBookItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentInstance
import net.neoforged.neoforge.server.ServerLifecycleHooks

object EnchantBooks {
    private const val BOOK = "minecraft:enchanted_book"
    private const val MAX_SHIFT = 30
    var client: (() -> HolderLookup.Provider?)? = null

    private fun registries() = ServerLifecycleHooks.getCurrentServer()?.registryAccess() ?: client?.invoke()

    fun spec(enchant: String, level: Int) = "$BOOK#$enchant@$level"

    fun factor(level: Int) = (1L shl (level - 1).coerceIn(0, MAX_SHIFT)).toDouble()

    fun parse(spec: String): Pair<String, Int>? {
        if (!spec.startsWith("$BOOK#")) return null
        val pack = spec.substringAfter('#')
        val level = pack.substringAfterLast('@', "").toIntOrNull() ?: return null
        return pack.substringBeforeLast('@').takeIf { ResourceLocation.tryParse(it) != null }?.let { it to level }
    }

    fun spec(stack: ItemStack): String? {
        if (!stack.`is`(Items.ENCHANTED_BOOK) || stack.componentsPatch.size() != 1) return null
        val e = stack.get(DataComponents.STORED_ENCHANTMENTS)?.entrySet()?.singleOrNull() ?: return null
        if (e.intValue !in 1..e.key.value().maxLevel) return null
        return e.key.unwrapKey().orElse(null)?.location()?.let { spec(it.toString(), e.intValue) }
    }

    fun stack(spec: String): ItemStack? {
        val (id, level) = parse(spec) ?: return null
        val holder = registries()?.lookup(Registries.ENCHANTMENT)?.flatMap { it.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(id))) }?.orElse(null)
            ?: return ItemStack.EMPTY
        if (level !in 1..holder.value().maxLevel) return ItemStack.EMPTY
        return EnchantedBookItem.createForEnchantment(EnchantmentInstance(holder, level))
    }

    fun variants(): Map<String, Variant> {
        val all = registries()?.lookup(Registries.ENCHANTMENT)?.orElse(null) ?: return emptyMap()
        return all.listElements().toList().flatMap { h ->
            val id = h.key().location().toString()
            (2..h.value().maxLevel).map { l -> spec(id, l) to Variant(spec(id, 1), factor(l).toInt(), 1) }
        }.toMap()
    }
}
