package kami.essentials.trade

import kami.libs.claims.ClaimsApi
import kami.libs.claims.Locks
import kami.libs.text.Phrase
import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Perms
import kami.essentials.Store
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
    private const val XP_PAIR_COOLDOWN_MS = 10 * 60_000L
    private val xpPairs = HashMap<Pair<UUID, UUID>, Long>()

    /** Trade XP is granted once per player pair per 10 minutes, so two alts cannot farm it by swapping items. */
    fun claimXp(a: UUID, b: UUID): Boolean {
        val key = if (a < b) a to b else b to a
        val t = now()
        if (xpPairs[key]?.let { t - it < XP_PAIR_COOLDOWN_MS } == true) return false
        xpPairs.values.removeIf { t - it >= XP_PAIR_COOLDOWN_MS }
        xpPairs[key] = t
        return true
    }

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
        from.tell(Talk.chat.ok(Phrase.of("kami_essentials.trade.request.sent", Phrase.value(to.gameProfile.name), Phrase.value(seconds))))
        to.tell(Talk.chat.msg {
            add(Phrase.of("kami_essentials.trade.request.received", Phrase.value(from.gameProfile.name)))
            text("  ")
            button(Phrase.of("kami_libs.common.accept"), "/trade accept ${from.gameProfile.name}", Phrase.of("kami_essentials.trade.request.accept.tooltip"))
            text(" ")
            button(Phrase.of("kami_libs.common.decline"), "/trade deny ${from.gameProfile.name}", Phrase.of("kami_essentials.trade.request.deny.tooltip"))
        })
    }

    fun accept(me: ServerPlayer, from: ServerPlayer) {
        requests[from.uuid]?.takeIf { it.to == me.uuid && it.until > now() } ?: fail(Phrase.of("kami_essentials.trade.no_request", Phrase.value(from.gameProfile.name)))
        check(me, from)
        requests.remove(from.uuid)
        start(from, me)
    }

    fun deny(me: ServerPlayer, from: ServerPlayer) {
        if (requests[from.uuid]?.to != me.uuid) fail(Phrase.of("kami_essentials.trade.no_request", Phrase.value(from.gameProfile.name)))
        requests.remove(from.uuid)
        me.tell(Talk.chat.ok(Phrase.of("kami_essentials.trade.declined", Phrase.value(from.gameProfile.name))))
        from.tell(Talk.chat.warn(Phrase.of("kami_essentials.trade.declined.by", Phrase.value(me.gameProfile.name))))
    }

    fun cancel(me: ServerPlayer) {
        val trade = active[me.uuid]
        if (trade != null) return trade.cancel(me)
        if (requests.remove(me.uuid) == null) fail(Phrase.of("kami_essentials.trade.nothing_open"))
        me.tell(Talk.chat.ok(Phrase.of("kami_essentials.trade.withdrawn")))
    }

    fun drop(p: ServerPlayer, why: Phrase) {
        requests.remove(p.uuid)
        active[p.uuid]?.cancel(null, why)
    }

    fun remove(trade: Trade) = trade.players.forEach { active.remove(it.uuid, trade) }

    fun tick() {
        active.values.toSet().forEach(Trade::tick)
        requests.values.removeIf { it.until < now() }
    }

    fun stop() {
        active.values.toSet().forEach { it.cancel(null, Phrase.of("kami_essentials.trade.reason.stopping")) }
        xpPairs.clear()
    }

    private fun check(me: ServerPlayer, other: ServerPlayer) {
        val name = other.gameProfile.name
        if (me === other) fail(Phrase.of("kami_essentials.trade.self"))
        if (!ClaimsApi.isCitizen(me.uuid)) fail(Phrase.of("kami_libs.economy.no_country"))
        if (!ClaimsApi.isCitizen(other.uuid)) fail(Phrase.of("kami_essentials.trade.other_no_country", Phrase.value(name)))
        val mine = ClaimsApi.countryOf(me.uuid)
        val theirs = ClaimsApi.countryOf(other.uuid)
        if (mine != null && theirs != null && !ClaimsApi.canTrade(mine, theirs)) fail(Locks.embargo(ClaimsApi.country(theirs)?.name ?: theirs))
        if (Store[Flag.NO_TRADES, other.uuid]) fail(Phrase.of("kami_essentials.trade.closed", Phrase.value(name)))
        if (busy(me)) fail(Phrase.of("kami_essentials.trade.busy"))
        if (busy(other)) fail(Phrase.of("kami_essentials.trade.other_busy", Phrase.value(name)))
        if (!inRange(me, other)) fail(
            if (Config.s.tradeDistance == -2) Phrase.of("kami_essentials.trade.same_dimension", Phrase.value(name))
            else Phrase.of("kami_essentials.trade.distance", Phrase.value(Config.s.tradeDistance), Phrase.value(name))
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
