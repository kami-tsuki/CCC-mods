package kami.libs.ui.widget

import kami.libs.mc.ItemSpec
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.world.item.ItemStack

private const val CYCLE_MS = 1000L
private const val SLOT_MIN = 18
private val tagStacks = HashMap<String, List<ItemStack>>()

fun tagMembers(tag: String): List<ItemStack> = tagStacks.getOrPut(tag) { ItemSpec.stacks(tag) }

internal fun clearTagMembers() = tagStacks.clear()

private fun Ui.drawStack(r: Rect, stack: ItemStack) {
    val s = minOf(ITEM_ICON, r.w - 2, r.h - 2).coerceAtLeast(1)
    val pose = g.pose()
    pose.pushPose()
    pose.translate(r.x + (r.w - s) / 2f, r.y + (r.h - s) / 2f, 0f)
    pose.scale(s / ITEM_ICON.toFloat(), s / ITEM_ICON.toFloat(), 1f)
    g.renderItem(stack, 0, 0)
    pose.popPose()
}

fun Ui.itemCycle(r: Rect, tag: String, key: Any): Boolean {
    val stacks = tagMembers(tag)
    if (r.h >= SLOT_MIN) panel(r, sunken = true)
    if (hovering(r)) Draw.fill(g, r.inset(1), Palette.alpha(Palette.washLight, 0x60))
    if (stacks.isNotEmpty()) drawStack(r, stacks[((wallMillis / CYCLE_MS) % stacks.size).toInt()])
    tooltip(key, r) { Tip.text(tr("kami_libs.item.tag_members", stacks.size), tag) }
    return pressed(r) != null
}

fun Ui.itemCell(c: Rect, stack: ItemStack, name: String, key: String, color: Int = Palette.text): Boolean {
    val slot = Rect(c.x, c.y, c.h, c.h)
    val clicked = if (c.h >= SLOT_MIN) itemSlot(slot, stack, key = key) else {
        drawStack(slot, stack)
        if (hovering(slot) && !stack.isEmpty) itemTooltip(stack)
        pressed(slot) != null
    }
    cellName(c, name, color)
    return clicked
}

fun Ui.specCell(c: Rect, spec: String, stack: ItemStack, name: String, key: String, color: Int = Palette.text): Boolean {
    if (!spec.startsWith("#")) return itemCell(c, stack, name, key, color)
    val clicked = itemCycle(Rect(c.x, c.y, c.h, c.h), spec, key)
    cellName(c, name, color)
    return clicked
}

private fun Ui.cellName(c: Rect, name: String, color: Int) {
    val shown = Draw.fit(name, c.w - c.h - 4)
    Draw.text(g, shown, c.x + c.h + 4, Draw.textY(c), color)
    if (shown != name) tooltip("name:${c.x},${c.y}", c.dropLeft(c.h)) { Tip.text(name) }
}
