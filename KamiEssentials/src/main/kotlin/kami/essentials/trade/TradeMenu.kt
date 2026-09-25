package kami.essentials.trade

import kami.essentials.inv.GridMenu
import kami.libs.chat.Theme
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class TradeMenu(id: Int, inv: Inventory, private val trade: Trade, private val side: Int) : GridMenu(6, id) {
    private val display = SimpleContainer(54)
    private val own = mutableListOf<Int>()

    init {
        for (at in 0 until 54) {
            val row = at / 9
            val col = at % 9
            when {
                row < 5 && col < 4 -> addSlot(Gate(trade.offers[side], row * 4 + col, at) { trade.open }).also { own += at }
                row < 5 && col > 4 -> addSlot(Gate(trade.offers[1 - side], row * 4 + col - 5, at) { false })
                at in BUTTONS -> addSlot(Gate(display, at, at) { false })
                else -> locked(at)
            }
        }
        playerSlots(inv)
        refresh()
    }

    override fun clicked(slot: Int, button: Int, type: ClickType, player: Player) {
        when {
            !trade.open -> return
            slot == ACCEPT -> trade.toggle(side)
            slot == CANCEL -> trade.cancel(trade.players[side], "cancelled the trade")
            else -> super.clicked(slot, button, type, player)
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = if (trade.open) quickMove(player, index, own) else ItemStack.EMPTY

    override fun stillValid(player: Player) = trade.open

    override fun broadcastChanges() {
        refresh()
        super.broadcastChanges()
    }

    override fun removed(player: Player) {
        super.removed(player)
        trade.closed(side)
    }

    private fun refresh() {
        val other = trade.name(1 - side)
        val seconds = (trade.countdown + 19) / 20
        display.setItem(
            ACCEPT,
            if (trade.accepted(side)) icon(Items.LIME_CONCRETE, text("Accepted", Theme.OK), text("Click to take it back"))
            else icon(Items.YELLOW_CONCRETE, text("Accept", Theme.WARN), text("Click when both offers look right"), text("Any change resets both sides"))
        )
        display.setItem(CANCEL, icon(Items.BARRIER, text("Cancel", Theme.BAD), text("Close the trade, your items come back")))
        display.setItem(
            STATUS,
            if (seconds > 0) icon(Items.CLOCK, text("Trading in $seconds...", Theme.OK), text("Change anything to stop"), count = seconds)
            else icon(Items.PAPER, text("Your offer is on the left", Theme.VALUE), text("$other's offer is on the right"), text("Both of you must accept"))
        )
        display.setItem(
            THEIRS,
            if (trade.accepted(1 - side)) icon(Items.LIME_DYE, text("$other accepted", Theme.OK))
            else icon(Items.GRAY_DYE, text("Waiting for $other", Theme.MUTED))
        )
    }

    private fun text(s: String, color: Int = Theme.TEXT): Component = Component.literal(s).withColor(color)

    companion object {
        private const val ACCEPT = 45
        private const val CANCEL = 47
        private const val STATUS = 49
        private const val THEIRS = 53
        private val BUTTONS = setOf(ACCEPT, CANCEL, STATUS, THEIRS)
    }
}
