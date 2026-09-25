package kami.essentials.trade

import kami.essentials.Config
import kami.essentials.Perms
import kami.essentials.chat.Talk
import kami.libs.chat.tell
import kami.libs.command.fail
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import java.util.UUID

object Trades {
    private class Request(val to: UUID, val until: Long)

    private val requests = HashMap<UUID, Request>()
    private val active = HashMap<UUID, Trade>()

    fun busy(p: Player) = p.uuid in active

    fun inRange(a: ServerPlayer, b: ServerPlayer) = Perms.has(a, Perms.TRADE_ANYWHERE) || Perms.has(b, Perms.TRADE_ANYWHERE) ||
        when (val d = Config.s.tradeDistance) {
            -1 -> true
            -2 -> a.level() === b.level()
            else -> a.level() === b.level() && a.distanceToSqr(b) <= d.toDouble() * d
        }

    fun request(from: ServerPlayer, to: ServerPlayer) {
        check(from, to)
        val back = requests[to.uuid]
        if (back != null && back.to == from.uuid && back.until > now()) {
            requests.remove(to.uuid)
            return start(to, from)
        }
        val seconds = Config.s.tradeRequestSeconds
        requests[from.uuid] = Request(to.uuid, now() + seconds * 1000L)
        from.tell(Talk.chat.ok("Trade request sent to {${to.gameProfile.name}}. It runs out in {$seconds} seconds."))
        to.tell(Talk.chat.msg {
            markup("{${from.gameProfile.name}} wants to trade.  ")
            button("Accept", "/trade accept ${from.gameProfile.name}", "Open the trade window")
            text(" ")
            button("Deny", "/trade deny ${from.gameProfile.name}", "Say no")
        })
    }

    fun accept(me: ServerPlayer, from: ServerPlayer) {
        requests[from.uuid]?.takeIf { it.to == me.uuid && it.until > now() } ?: fail("No open trade request from {${from.gameProfile.name}}.")
        check(me, from)
        requests.remove(from.uuid)
        start(from, me)
    }

    fun deny(me: ServerPlayer, from: ServerPlayer) {
        if (requests[from.uuid]?.to != me.uuid) fail("No open trade request from {${from.gameProfile.name}}.")
        requests.remove(from.uuid)
        me.tell(Talk.chat.ok("Declined the trade with {${from.gameProfile.name}}."))
        from.tell(Talk.chat.warn("{${me.gameProfile.name}} declined your trade."))
    }

    fun cancel(me: ServerPlayer) {
        val trade = active[me.uuid]
        if (trade != null) return trade.cancel(me, "cancelled the trade")
        if (requests.remove(me.uuid) == null) fail("You have no trade or request open.")
        me.tell(Talk.chat.ok("Trade request withdrawn."))
    }

    fun drop(p: ServerPlayer, why: String) {
        requests.remove(p.uuid)
        active[p.uuid]?.cancel(null, why)
    }

    fun remove(trade: Trade) = trade.players.forEach { active.remove(it.uuid, trade) }

    fun tick() {
        active.values.toSet().forEach(Trade::tick)
        requests.values.removeIf { it.until < now() }
    }

    fun stop() = active.values.toSet().forEach { it.cancel(null, "the server is stopping") }

    private fun check(me: ServerPlayer, other: ServerPlayer) {
        val name = other.gameProfile.name
        if (me === other) fail("You can't trade with yourself.")
        if (busy(me)) fail("Finish your current trade first.")
        if (busy(other)) fail("{$name} is already trading.")
        if (!inRange(me, other)) fail(
            if (Config.s.tradeDistance == -2) "You need to be in the same dimension as {$name}."
            else "You need to be within {${Config.s.tradeDistance}} blocks of {$name}."
        )
    }

    private fun start(a: ServerPlayer, b: ServerPlayer) {
        val trade = Trade(listOf(a, b))
        active[a.uuid] = trade
        active[b.uuid] = trade
        trade.start()
    }

    private fun now() = System.currentTimeMillis()
}
