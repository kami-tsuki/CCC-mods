package kami.essentials.trade

import kami.essentials.Config
import kami.essentials.chat.Talk
import kami.libs.chat.tell
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Relation
import kami.libs.economy.MarketApi
import kami.libs.progress.KamiProgress
import kami.libs.text.Phrase
import kami.libs.text.Text
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.item.ItemStack

class Trade(val players: List<ServerPlayer>) {
    val offers = List(2) { SimpleContainer(20) }
    private val accepted = BooleanArray(2)
    var countdown = -1
        private set
    var open = true
        private set

    init {
        offers.forEach { it.addListener { changed() } }
    }

    fun name(side: Int): String = players[side].gameProfile.name

    fun accepted(side: Int) = accepted[side]

    fun start() {
        players.forEachIndexed { side, p ->
            val menu = p.openMenu(SimpleMenuProvider({ id, inv, _ -> TradeMenu(id, inv, this, side) }, Text.msg("kami_essentials.trade.window", name(1 - side))))
            if (menu.isEmpty) return cancel(null, Phrase.of("kami_essentials.trade.reason.window"))
        }
    }

    fun toggle(side: Int) {
        if (!open) return
        accepted[side] = !accepted[side]
        countdown = if (accepted.all { it }) Config.s.tradeConfirmSeconds * 20 else -1
        if (countdown == 0) finish()
    }

    fun tick() {
        if (!open) return
        when {
            players.any { it.hasDisconnected() || !it.isAlive } -> cancel(null, Phrase.of("kami_essentials.trade.reason.player_left"))
            !Trades.inRange(players[0], players[1]) -> cancel(null, Phrase.of("kami_essentials.trade.reason.distance"))
            countdown > 0 && --countdown == 0 -> finish()
        }
    }

    fun closed(side: Int) = cancel(players[side])

    fun cancel(by: ServerPlayer?, why: Phrase? = null) {
        if (!open) return
        open = false
        players.forEachIndexed { side, p -> give(p, offers[side].removeAllItems()) }
        end()
        players.forEach { p ->
            p.tell(Talk.chat.warn(when {
                by == null && why != null -> Phrase.of("kami_essentials.trade.cancelled.reason", why)
                by == null || by === p -> Phrase.of("kami_essentials.trade.cancelled")
                else -> Phrase.of("kami_essentials.trade.cancelled.by", Phrase.value(by.gameProfile.name))
            }))
        }
    }

    private fun changed() {
        if (!open) return
        accepted.fill(false)
        countdown = -1
    }

    private fun finish() {
        if (embargoed()) return cancel(null, Phrase.of("kami_essentials.trade.reason.embargo"))
        val full = players.indices.firstOrNull { side -> !fits(players[side], offers[1 - side].items) }
        if (full != null) {
            changed()
            return players.forEach { it.tell(Talk.chat.warn(Phrase.of("kami_essentials.trade.no_room", Phrase.value(name(full))))) }
        }
        open = false
        val items = offers.map { it.removeAllItems() }
        players.forEachIndexed { side, p -> give(p, items[1 - side]) }
        end()
        if (items.none { it.isEmpty() } && neutralCountries()) {
            val value = items.flatten().sumOf { MarketApi.value(it) }
            val count = items.sumOf { list -> list.sumOf { it.count } }.toLong()
            players.forEach {
                KamiProgress.post(it, "trade", "", count)
                if (value > 0) KamiProgress.post(it, "trade_value", "", value)
            }
        }
        players.forEachIndexed { side, p ->
            p.tell(Talk.chat.ok(Phrase.of("kami_essentials.trade.done", Phrase.value(name(1 - side)), Phrase.value(items[1 - side].sumOf { it.count }), Phrase.value(items[side].sumOf { it.count }))))
        }
    }

    private fun neutralCountries(): Boolean {
        val (a, b) = players.map { ClaimsApi.countryOf(it.uuid) }
        return a != null && b != null && a != b && ClaimsApi.relation(a, b) == Relation.NEUTRAL
    }

    private fun embargoed(): Boolean {
        val (a, b) = players.map { ClaimsApi.countryOf(it.uuid) }
        return a != null && b != null && !ClaimsApi.canTrade(a, b)
    }

    private fun end() {
        Trades.remove(this)
        players.forEach { if (it.containerMenu is TradeMenu) it.closeContainer() }
    }

    private fun give(p: ServerPlayer, items: List<ItemStack>) = items.forEach { p.inventory.placeItemBackInInventory(it) }

    private fun fits(p: ServerPlayer, incoming: List<ItemStack>): Boolean {
        val slots = MutableList(36) { p.inventory.getItem(it).copy() }
        return (listOf(p.containerMenu.carried) + incoming).filterNot { it.isEmpty }.all { stack ->
            var left = stack.count
            slots.filter { !it.isEmpty && ItemStack.isSameItemSameComponents(it, stack) }.forEach {
                val add = minOf(left, it.maxStackSize - it.count)
                if (add > 0) {
                    it.grow(add)
                    left -= add
                }
            }
            while (left > 0) {
                val free = slots.indexOfFirst { it.isEmpty }
                if (free < 0) return false
                val put = minOf(left, stack.maxStackSize)
                slots[free] = stack.copyWithCount(put)
                left -= put
            }
            true
        }
    }
}
