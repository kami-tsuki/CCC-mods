package kami.economy.compat

import kami.economy.LOG
import kami.economy.Vendor
import kami.economy.now
import kami.libs.compat.Refl
import kami.libs.mc.ItemSpec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.UUID

object NumismaticsVendors {
    private val type = runCatching { Refl.cls("dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity") }
        .onFailure { LOG.warn("Numismatics vendor class not found, vendor registry is off") }.getOrNull()
    private val ownerField = type?.let { t -> runCatching { t.getDeclaredField("owner").apply { isAccessible = true } }.getOrNull() }

    fun isVendor(be: BlockEntity?) = type?.isInstance(be) == true

    fun read(be: BlockEntity, v: Vendor, country: String) {
        val filter = Refl.call(be, "getFilterItem") as ItemStack
        v.country = country
        val owner = checkNotNull(ownerField) { "vendor owner field unavailable" }.get(be)
        v.owner = (owner as? UUID)?.toString() ?: ""
        v.item = if (filter.isEmpty || Refl.call(be, "filterActsSpecial") == true) "" else ItemSpec.id(filter)
        v.count = filter.count.coerceAtLeast(1)
        v.price = Refl.call(be, "getTotalPrice") as Int
        v.sell = (Refl.call(be, "getMode") as Enum<*>).name == "SELL"
        val items = Refl.field(be, "items") as List<*>
        v.stock = if (!v.sell || Refl.call(be, "isCreativeVendor") == true || v.item.isEmpty()) -1
            else items.filterIsInstance<ItemStack>().filter { !it.isEmpty && Refl.call(be, "matchesFilterItem", it) == true }.sumOf { it.count }
        v.seen = now()
    }
}
