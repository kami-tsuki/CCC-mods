package kami.claims.admin

import kami.claims.Country
import kami.claims.LendState
import kami.claims.Realm
import kami.claims.research.Levels
import kami.claims.research.Loans
import kami.claims.war.Fame
import kami.claims.war.Power
import kami.libs.stats.Stats
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object ClaimStats {
    private fun countries() = Realm.data.countries.values.filter { it.active }

    private fun each(value: (Country) -> Long) = countries().associate { "c:${it.id}" to value(it) }

    private fun offers(state: LendState) = each { c -> c.lendOffers.count { it.state == state }.toLong() }

    private fun online() = ServerLifecycleHooks.getCurrentServer()?.playerList?.players.orEmpty().filter { it !is FakePlayer }

    private fun onlineBy(): Map<String, Int> = online().mapNotNull { Realm.home[it.stringUUID] }.groupingBy { it }.eachCount()

    fun register() {
        Stats.gauge("country.count") { mapOf("" to countries().size.toLong()) }
        Stats.gauge("country.active") { mapOf("" to onlineBy().size.toLong()) }
        Stats.gauge("country.treasury") { each { it.treasury } }
        Stats.gauge("country.chunks") { Realm.data.claims.groupingBy { it.country }.eachCount().let { by -> each { by[it.id]?.toLong() ?: 0 } } }
        Stats.gauge("country.members") { each { it.members.size.toLong() } }
        Stats.gauge("country.online") { onlineBy().let { by -> each { by[it.id]?.toLong() ?: 0 } } }
        Stats.gauge("country.provinces") { each { it.provinces.size.toLong() } }
        Stats.gauge("country.level") { each { it.level.toLong() } }
        Stats.gauge("country.fame") { Fame.scores().filterValues { it != null }.entries.associate { (id, v) -> "c:$id" to (v!! * 100).toLong() } }
        Stats.gauge("country.power") { each { (Power.score(it) * 100).toLong() } }
        Stats.gauge("loan.debt") { each { c -> c.loans.sumOf(Loans::remaining) } }
        Stats.gauge("loan.reserved") { each { c -> c.lendOffers.filter { it.state == LendState.ACTIVE }.sumOf { it.amount } } }
        Stats.gauge("loan.lent") {
            val by = countries().flatMap { it.loans }.filter { it.lender != null }.groupBy({ it.lender!! }, Loans::remaining)
            each { by[it.id]?.sum() ?: 0 }
        }
        Stats.gauge("loan.offers.active") { offers(LendState.ACTIVE) }
        Stats.gauge("loan.offers.disabled") { offers(LendState.DISABLED) }
        Stats.gauge("loan.offers.lent") { offers(LendState.LENT) }
        Stats.gauge("loan.block") {
            val day = Levels.day()
            each { Loans.blockDays(it, day).toLong() }
        }
        FORGE_BUS.addListener<ServerTickEvent.Post> { if (it.server.tickCount % 20 == 0) runCatching { territory(it.server.playerList.players) } }
    }

    private fun territory(players: List<ServerPlayer>) {
        for (p in players) {
            if (p is FakePlayer) continue
            val at = p.chunkPosition()
            val owner = Realm.at(p.level().dimension().location().toString(), at.x, at.z)?.country
            Stats.add("move.territory", "p:${p.uuid}", owner?.let { "c:$it" } ?: "wild", 1)
        }
    }
}
