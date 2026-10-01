package kami.claims.research

import kami.claims.Claim
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.net.BuffView
import kami.claims.net.BuffsView
import kami.claims.net.ResearchSync
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.libs.claims.Locks
import kami.libs.text.Phrase
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import java.util.UUID

class Buff(val key: String, val node: Node, val unlock: BuffUnlock) {
    val cost get() = unlock.amplifier + 1
    val tax get() = 1 shl unlock.amplifier
}

class PulseTracker {
    private val inside = HashMap<UUID, Boolean>()
    private val readyAt = HashMap<Pair<UUID, String>, Long>()

    fun entered(player: UUID, nowInside: Boolean) = inside.put(player, nowInside) == false && nowInside

    fun ready(player: UUID, buff: String, at: Long, cooldownMs: Long): Boolean {
        if ((readyAt[player to buff] ?: 0) > at) return false
        readyAt[player to buff] = at + cooldownMs
        return true
    }

    fun forget(player: UUID) {
        inside.remove(player)
    }

    fun prune(at: Long) = readyAt.values.removeAll { it <= at }
}

object Buffs {
    private const val WATCH_TICKS = 10
    private const val AURA_TICKS = 80
    private const val AURA_DURATION = 220

    private val tracker = PulseTracker()
    private val borders = HashMap<String, Set<Key>>()

    fun unlocked(country: Country): List<Buff> = Research.defs.nodes.values.filter { it.key in country.research.done }.mapNotNull { node ->
        node.unlocks.filterIsInstance<BuffUnlock>().firstOrNull()?.let { Buff(node.key, node, it) }
    }

    fun enabled(country: Country): List<Buff> = unlocked(country).filter { it.key in country.buffs.enabled }

    fun points(country: Country): Int = Research.unlocks<BuffPointsUnlock>(country).sumOf { it.add }

    fun used(country: Country): Int = enabled(country).sumOf { it.cost }

    fun prune(country: Country) {
        country.buffs.enabled.retainAll(unlocked(country).map { it.key }.toSet())
        val points = points(country)
        enabled(country).reversed().forEach { if (used(country) > points) country.buffs.enabled.remove(it.key) }
    }

    fun toggle(country: Country, key: String): Phrase {
        Features.require(country, Features.BUFFS)
        fun unknown() = Fail("kami_claims.research.error.unknown", Words.v(key))
        val node = Research.defs.nodeFor(country, key) ?: throw unknown()
        val unlock = node.unlocks.filterIsInstance<BuffUnlock>().firstOrNull() ?: throw unknown()
        if (key !in country.research.done) throw Fail(Locks.research(node.label().asValue()))
        val buff = Buff(key, node, unlock)
        val on = country.buffs.enabled.add(key)
        if (!on) country.buffs.enabled.remove(key)
        else (points(country) - used(country)).takeIf { it < 0 }?.let { free ->
            country.buffs.enabled.remove(key)
            throw Fail("kami_claims.buffs.error.points", Words.num(free), Words.num(buff.cost))
        }
        if (on) country.buffs.billed += key
        Realm.changed()
        ResearchSync.refresh(country)
        return Phrase.of(if (on) "kami_claims.buffs.done.on" else "kami_claims.buffs.done.off", node.label().asValue())
    }

    fun borderChunks(country: Country): Set<Key> {
        borders[country.id]?.let { return it }
        return Realm.claims(country.id).filter { claim -> Realm.neighbors(claim).any { Realm.index[it]?.country != country.id } }.map { it.key }.toSet().also { borders[country.id] = it }
    }

    fun tax(country: Country): Int = enabled(country).sumOf { it.tax }

    fun billedTax(country: Country): Int = unlocked(country).filter { it.key in country.buffs.enabled || it.key in country.buffs.billed }.sumOf { it.tax }

    fun settle(country: Country) = country.buffs.billed.clear()

    fun price(country: Country, claim: Claim, borderTax: Int = tax(country)): Int = Realm.price(claim) + if (claim.key in borderChunks(country)) borderTax else 0

    fun dropBorders(country: String) = borders.remove(country)

    fun clearBorders() = borders.clear()

    fun view(country: Country): BuffsView {
        if (!Features.unlocked(country, Features.BUFFS)) return BuffsView.NONE
        val list = unlocked(country).map {
            BuffView(it.key, it.unlock.effect, it.unlock.amplifier, it.unlock.mode.name.lowercase(), it.unlock.cooldownSeconds, it.cost, it.tax, it.key in country.buffs.enabled)
        }
        return BuffsView(true, points(country), used(country), tax(country), borderChunks(country).size, list)
    }

    fun forget(player: UUID) = tracker.forget(player)

    fun tick(server: MinecraftServer) {
        val tick = server.tickCount
        if (tick % WATCH_TICKS != 0) return
        if (tick % AURA_TICKS == 0) tracker.prune(System.currentTimeMillis())
        server.playerList.players.forEach { p ->
            val home = Realm.of(p.stringUUID)
            val standing = Realm.at(p.level().dimension().location().toString(), p.chunkPosition().x, p.chunkPosition().z)?.country
            val inside = home != null && standing == home.id && p.isAlive
            val entered = tracker.entered(p.uuid, inside)
            if (home == null || !inside) return@forEach
            val active = enabled(home)
            if (entered) active.filter { it.unlock.mode == BuffMode.PULSE }.forEach { pulse(p, it) }
            if (tick % AURA_TICKS == 0) strongest(active.map { it.unlock }).forEach { (effect, amplifier) -> aura(p, effect, amplifier) }
        }
    }

    fun strongest(unlocks: List<BuffUnlock>): Map<String, Int> =
        unlocks.filter { it.mode == BuffMode.AURA }.groupBy({ it.effect }, { it.amplifier }).mapValues { it.value.max() }

    private fun holder(id: String): Holder<MobEffect>? =
        ResourceLocation.tryParse(id)?.let { BuiltInRegistries.MOB_EFFECT.getHolder(it).orElse(null) }

    private fun pulse(player: ServerPlayer, buff: Buff) {
        if (player.health >= player.maxHealth) return
        val effect = holder(buff.unlock.effect) ?: return
        if (!tracker.ready(player.uuid, buff.key, System.currentTimeMillis(), buff.unlock.cooldownSeconds * 1000L)) return
        effect.value().applyInstantenousEffect(null, null, player, buff.unlock.amplifier, 1.0)
    }

    private fun aura(player: ServerPlayer, effect: String, amplifier: Int) {
        val holder = holder(effect) ?: return
        val current = player.getEffect(holder)
        if (current != null && (current.amplifier > amplifier || current.amplifier == amplifier && current.duration > AURA_DURATION)) return
        player.addEffect(MobEffectInstance(holder, AURA_DURATION, amplifier, true, false))
    }
}
