package kami.libs.menu

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.SimpleMenuProvider

private class ItemPeekMenu(id: Int, inv: Inventory, stack: ItemStack) : GridMenu(3, id) {
    private val display = SimpleContainer(27)

    init {
        for (i in 0 until 27) {
            if (i == 13) addSlot(Gate(display, 13, i) { false }) else locked(i)
        }
        display.setItem(13, stack.copy())
        playerSlots(inv)
    }

    override fun clicked(slot: Int, button: Int, type: ClickType, player: Player) {}

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player) = true
}

object ItemPeek {
    fun open(viewer: ServerPlayer, title: Component, stack: ItemStack) {
        viewer.openMenu(SimpleMenuProvider({ id, inv, _ -> ItemPeekMenu(id, inv, stack) }, title))
    }
}
