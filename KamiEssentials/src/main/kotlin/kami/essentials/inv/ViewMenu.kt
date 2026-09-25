package kami.essentials.inv

import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack

class ViewMenu(
    id: Int,
    inv: Inventory,
    source: Container,
    private val layout: IntArray,
    private val edit: Boolean,
    private val valid: () -> Boolean,
    private val closed: () -> Unit,
) : GridMenu(layout.size / 9, id) {
    private val open = { edit && valid() }
    private val targets = layout.indices.filter { layout[it] >= 0 }

    init {
        layout.forEachIndexed { i, at -> if (at >= 0) addSlot(Gate(source, at, i, open)) else locked(i) }
        playerSlots(inv)
    }

    override fun clicked(slot: Int, button: Int, type: ClickType, player: Player) {
        if (open() && (slot !in 0 until size || layout[slot] >= 0)) super.clicked(slot, button, type, player)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = if (open()) quickMove(player, index, targets) else ItemStack.EMPTY

    override fun stillValid(player: Player) = valid()

    override fun removed(player: Player) {
        super.removed(player)
        closed()
    }
}
