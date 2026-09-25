package kami.essentials.inv

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Unit
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

abstract class GridMenu(private val rows: Int, id: Int) : AbstractContainerMenu(TYPES[rows - 1], id) {
    val size = rows * 9

    protected open inner class Gate(c: Container, index: Int, at: Int, private val open: () -> Boolean) : Slot(c, index, 8 + at % 9 * 18, 18 + at / 9 * 18) {
        override fun mayPickup(player: Player) = open()
        override fun mayPlace(stack: ItemStack) = open()
    }

    protected fun locked(at: Int) = addSlot(Gate(FILLER, 0, at) { false })

    protected fun playerSlots(inv: Inventory) {
        val shift = (rows - 4) * 18
        for (r in 0..2) for (c in 0..8) addSlot(Slot(inv, c + r * 9 + 9, 8 + c * 18, 103 + r * 18 + shift))
        for (c in 0..8) addSlot(Slot(inv, c, 8 + c * 18, 161 + shift))
    }

    protected fun moveInto(stack: ItemStack, targets: List<Int>) = targets.any { moveItemStackTo(stack, it, it + 1, false) && stack.isEmpty }

    protected fun quickMove(player: Player, index: Int, targets: List<Int>): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        if (index < size) moveItemStackTo(stack, size, slots.size, true) else moveInto(stack, targets)
        if (stack.count == copy.count) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    companion object {
        private val TYPES = listOf(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6)
        private val FILLER = SimpleContainer(icon(Items.GRAY_STAINED_GLASS_PANE, null))

        fun icon(item: Item, name: Component?, vararg lore: Component, count: Int = 1): ItemStack = ItemStack(item, count).apply {
            if (name == null) {
                set(DataComponents.HIDE_TOOLTIP, Unit.INSTANCE)
            } else {
                set(DataComponents.ITEM_NAME, name)
                if (lore.isNotEmpty()) set(DataComponents.LORE, ItemLore(lore.toList()))
            }
        }
    }
}
