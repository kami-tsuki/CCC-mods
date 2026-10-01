package kami.essentials.trade

import kami.libs.text.Text
import kami.libs.chat.Theme
import kami.libs.menu.GridMenu
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
            slot == CANCEL -> trade.cancel(trade.players[side])
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
            if (trade.accepted(side)) icon(Items.LIME_CONCRETE, text("kami_essentials.trade.menu.accepted", color = Theme.OK), text("kami_essentials.trade.menu.accepted.tooltip"))
            else icon(Items.YELLOW_CONCRETE, text("kami_libs.common.accept", color = Theme.WARN), text("kami_essentials.trade.menu.accept.tooltip"), text("kami_essentials.trade.menu.accept.reset"))
        )
        display.setItem(CANCEL, icon(Items.BARRIER, text("kami_libs.common.cancel", color = Theme.BAD), text("kami_essentials.trade.menu.cancel.tooltip")))
        display.setItem(
            STATUS,
            if (seconds > 0) icon(Items.CLOCK, text("kami_essentials.trade.menu.countdown", seconds, color = Theme.OK), text("kami_essentials.trade.menu.countdown.tooltip"), count = seconds)
            else icon(Items.PAPER, text("kami_essentials.trade.menu.yours", color = Theme.VALUE), text("kami_essentials.trade.menu.theirs", other), text("kami_essentials.trade.menu.both"))
        )
        display.setItem(
            THEIRS,
            if (trade.accepted(1 - side)) icon(Items.LIME_DYE, text("kami_essentials.trade.menu.other_accepted", other, color = Theme.OK))
            else icon(Items.GRAY_DYE, text("kami_essentials.trade.menu.waiting", other, color = Theme.MUTED))
        )
    }

    private fun text(key: String, vararg args: Any, color: Int = Theme.TEXT): Component = Text.msg(key, *args).withColor(color)

    companion object {
        private const val ACCEPT = 45
        private const val CANCEL = 47
        private const val STATUS = 49
        private const val THEIRS = 53
        private val BUTTONS = setOf(ACCEPT, CANCEL, STATUS, THEIRS)
    }
}
