package kami.economy.economy

import kami.economy.Config
import kami.libs.economy.Coins
import kami.libs.mc.ItemSpec
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

enum class Classification { ALLOWED, AUCTION_ONLY, BLOCKED }

object Blacklist {
    private fun registryId(stack: ItemStack): String = ItemSpec.id(stack)

    private fun taczAmmoId(stack: ItemStack): String? {
        if (registryId(stack) != "tacz:ammo") return null
        return ItemSpec.spec(stack).substringAfter('#', "").takeIf { it.isNotEmpty() }
    }

    fun itemId(stack: ItemStack): String = ItemSpec.spec(stack)

    fun classify(stack: ItemStack): Classification {
        if (taczAmmoId(stack) != null) return Classification.ALLOWED

        val id = registryId(stack)
        if (id in Coins.values.keys) return Classification.BLOCKED
        if (id in Config.s.creativeItemSet) return Classification.BLOCKED

        if (stack.get(DataComponents.CUSTOM_DATA)?.isEmpty == false) return Classification.AUCTION_ONLY
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

        return if (stack.componentsPatch.isEmpty()) Classification.ALLOWED else Classification.AUCTION_ONLY
    }

    fun sellable(id: String): Boolean {
        val stack = prototype(id)?.takeUnless { it.isEmpty } ?: return false
        return classify(stack) == Classification.ALLOWED
    }

    fun prototype(id: String): ItemStack? = ItemSpec.stack(id).takeUnless { it.isEmpty }
}
