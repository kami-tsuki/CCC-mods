package kami.economy.economy

import kami.economy.Config
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

enum class Classification { ALLOWED, AUCTION_ONLY, BLOCKED }

object Blacklist {
    private const val TACZ_AMMO = "tacz:ammo"

    private fun registryId(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun taczAmmoId(stack: ItemStack): String? {
        if (registryId(stack) != TACZ_AMMO) return null
        val ammoId = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("AmmoId") ?: return null
        return ammoId.takeIf { it.isNotEmpty() }
    }

    fun itemId(stack: ItemStack): String = taczAmmoId(stack)?.let { "$TACZ_AMMO#$it" } ?: registryId(stack)

    fun classify(stack: ItemStack): Classification {
        if (taczAmmoId(stack) != null) return Classification.ALLOWED

        val id = registryId(stack)
        if (id in Config.s.coins.keys) return Classification.BLOCKED
        if (id in Config.s.creativeItemSet) return Classification.BLOCKED

        if (stack.has(DataComponents.CUSTOM_DATA) && !stack.get(DataComponents.CUSTOM_DATA)!!.isEmpty) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.BLOCK_ENTITY_DATA)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.CONTAINER)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.BUNDLE_CONTENTS)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.CHARGED_PROJECTILES)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.FIREWORKS)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.WRITABLE_BOOK_CONTENT)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.WRITTEN_BOOK_CONTENT)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.PROFILE)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.TRIM)) return Classification.AUCTION_ONLY
        if (stack.has(DataComponents.CUSTOM_NAME)) return Classification.AUCTION_ONLY
        if (id in Config.s.storageItemSet) return Classification.AUCTION_ONLY

        val stored = stack.get(DataComponents.STORED_ENCHANTMENTS)
        val normal = stack.get(DataComponents.ENCHANTMENTS)
        val enchantments = if (stored != null && !stored.isEmpty) stored else normal
        if (enchantments != null && !enchantments.isEmpty) {
            val entries = enchantments.entrySet()
            if (stack.`is`(Items.ENCHANTED_BOOK) && entries.size == 1) {
                val entry = entries.iterator().next()
                if (entry.intValue <= entry.key.value().maxLevel) return Classification.ALLOWED
            }
            return Classification.AUCTION_ONLY
        }

        return Classification.ALLOWED
    }
}
