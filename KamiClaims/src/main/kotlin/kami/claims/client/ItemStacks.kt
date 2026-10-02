package kami.claims.client

import kami.libs.mc.ItemSpec
import kami.libs.mc.Selectors
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

private const val MAX_SELECTORS = 3
private const val CYCLE_MS = 1000L

private val stacks = HashMap<String, ItemStack>()
private val members = HashMap<String, List<List<ItemStack>>>()
private val shown = HashMap<String, MutableList<ItemStack>>()

fun stackOf(selector: String): ItemStack = stacks.getOrPut(selector) { if ('#' in selector.drop(1)) ItemSpec.stack(selector) else firstItem(selector)?.let { ItemStack(it) } ?: ItemStack.EMPTY }

fun cyclingStacksOf(subject: String, now: Long): List<ItemStack> {
    val options = members.getOrPut(subject) {
        subject.split(',').map { stacksOf(it.trim()) }.filter { it.isNotEmpty() }.take(MAX_SELECTORS)
    }
    val out = shown.getOrPut(subject) { options.mapTo(ArrayList()) { it.first() } }
    val tick = now / CYCLE_MS
    options.forEachIndexed { i, items -> out[i] = items[(tick % items.size).toInt()] }
    return out
}

fun forgetStacks() {
    stacks.clear()
    members.clear()
    shown.clear()
}

private fun stacksOf(selector: String): List<ItemStack> =
    if ('#' in selector.drop(1)) listOf(ItemSpec.stack(selector)).filterNot { it.isEmpty } else allItems(selector).map(::ItemStack)

private fun tagItems(selector: String): List<Item> = ResourceLocation.tryParse(selector.drop(1))
    ?.let { BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, it)).orElse(null) }
    ?.map { it.value() }?.filter { it != Items.AIR }.orEmpty()

private fun allItems(selector: String): List<Item> = if (selector.startsWith("#")) tagItems(selector) else listOfNotNull(firstItem(selector))

private fun firstItem(selector: String): Item? = when {
    selector.startsWith("#") -> tagItems(selector).firstOrNull()
    '*' in selector || '?' in selector -> BuiltInRegistries.ITEM.keySet().filter { Selectors.glob(selector, it.toString()) }.minOrNull()
        ?.let { BuiltInRegistries.ITEM.get(it) }?.takeIf { it != Items.AIR }
    else -> ResourceLocation.tryParse(ItemSpec.base(selector))?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.takeIf { it != Items.AIR }
}
