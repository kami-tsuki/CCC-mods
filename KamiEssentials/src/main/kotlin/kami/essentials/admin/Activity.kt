package kami.essentials.admin

import kami.essentials.vanish.Vanish
import kami.libs.stats.Staff
import kami.libs.stats.Stats
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.entity.EntityTeleportEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

object Activity {
    private class Track(var dim: String, var x: Double, var y: Double, var z: Double, var at: Long)

    private val joined = ConcurrentHashMap<UUID, Long>()
    private val tracks = HashMap<UUID, Track>()

    fun register() {
        Staff.hidden += { Vanish.active(it) }
        Stats.gauge("server.online") { mapOf("" to (ServerLifecycleHooks.getCurrentServer()?.playerCount ?: 0).toLong()) }
        Stats.gauge("server.tps") { ServerLifecycleHooks.getCurrentServer()?.let { mapOf("" to (ServerStats.tps(it) * 100).toLong()) }.orEmpty() }
        Stats.gauge("server.mspt") { ServerLifecycleHooks.getCurrentServer()?.let { mapOf("" to (ServerStats.mspt(it) * 100).toLong()) }.orEmpty() }
        Stats.gauge("server.memory") { val r = Runtime.getRuntime(); mapOf("" to (r.totalMemory() - r.freeMemory()) / 1_048_576) }
        Stats.gauge("server.ping") { ServerLifecycleHooks.getCurrentServer()?.playerList?.players?.associate { "p:${it.uuid}" to it.connection.latency().toLong() }.orEmpty() }
        FORGE_BUS.addListener<ServerTickEvent.Post> { if (it.server.tickCount % 20 == 0) runCatching { sample(it.server.playerList.players) } }
        FORGE_BUS.addListener<LivingDeathEvent> { runCatching { death(it) } }
        FORGE_BUS.addListener<LivingDamageEvent.Post> { runCatching { damage(it) } }
        FORGE_BUS.addListener<EntityTeleportEvent>(EventPriority.LOWEST) { if (!it.isCanceled) player(it.entity)?.let(::jumped) }
        FORGE_BUS.addListener<BlockEvent.BreakEvent>(EventPriority.LOWEST) { e ->
            if (!e.isCanceled) player(e.player)?.let { Stats.add("block.mine", id(it), BuiltInRegistries.BLOCK.getKey(e.state.block).toString()) }
        }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent>(EventPriority.LOWEST) { e ->
            if (!e.isCanceled) player(e.entity)?.let { Stats.add("block.place", id(it), BuiltInRegistries.BLOCK.getKey(e.placedBlock.block).toString()) }
        }
        FORGE_BUS.addListener<PlayerEvent.ItemCraftedEvent> { e ->
            player(e.entity)?.let { Stats.add("craft.item", id(it), BuiltInRegistries.ITEM.getKey(e.crafting.item).toString(), e.crafting.count.toLong()) }
        }
        FORGE_BUS.addListener<PlayerEvent.ItemSmeltedEvent> { e ->
            player(e.entity)?.let { Stats.add("craft.smelt", id(it), BuiltInRegistries.ITEM.getKey(e.smelting.item).toString(), e.smelting.count.toLong()) }
        }
    }

    fun login(p: ServerPlayer, first: Boolean) {
        if (p is FakePlayer) return
        joined[p.uuid] = System.currentTimeMillis()
        Stats.event("session.login", id(p), if (first) "new" else "")
    }

    fun logout(p: ServerPlayer) {
        tracks.remove(p.uuid)
        val at = joined.remove(p.uuid) ?: return
        Stats.event("session.logout", id(p), v = System.currentTimeMillis() - at)
    }

    fun jumped(p: ServerPlayer) {
        tracks.remove(p.uuid)
    }

    private fun sample(players: List<ServerPlayer>) {
        val now = System.currentTimeMillis()
        for (p in players) {
            if (p is FakePlayer) continue
            val dim = p.level().dimension().location().toString()
            val t = tracks.getOrPut(p.uuid) { Track(dim, p.x, p.y, p.z, now) }
            val dt = now - t.at
            Stats.add("session.time", id(p), dim, dt)
            if (t.dim == dim && dt in 1..5000) {
                val dx = p.x - t.x
                val dz = p.z - t.z
                val d = sqrt(dx * dx + dz * dz) + abs(p.y - t.y)
                if (d <= 100) Stats.add("move.dist", id(p), dim, d.toLong())
            }
            t.dim = dim; t.x = p.x; t.y = p.y; t.z = p.z; t.at = now
        }
    }

    private fun death(e: LivingDeathEvent) {
        val victim = player(e.entity)
        val killer = player(e.source.entity)
        if (victim != null) {
            Stats.add(staffed("combat.death", e.source), id(victim), e.source.entity?.type?.let(::typeId) ?: damageId(e.source))
            if (killer != null && killer !== victim) {
                Stats.add("combat.kill", id(killer), id(victim))
                Stats.event("combat.pvp", id(killer), id(victim))
            }
        } else if (killer != null) Stats.add("mob.kill", id(killer), typeId(e.entity.type))
    }

    private fun damage(e: LivingDamageEvent.Post) {
        val dmg = minOf(e.newDamage, e.entity.maxHealth)
        if (!dmg.isFinite() || dmg <= 0f) return
        val n = (dmg * 10).toLong()
        player(e.entity)?.let { Stats.add(staffed("combat.taken", e.source), id(it), damageId(e.source), n) }
        player(e.source.entity)?.let { Stats.add("combat.dealt", id(it), typeId(e.entity.type), n) }
    }

    private fun staffed(key: String, s: DamageSource) =
        if (s.`is`(DamageTypes.GENERIC_KILL) || player(s.entity)?.let(Staff::active) == true) Staff.PREFIX + key else key

    private fun player(e: Entity?) = (e as? ServerPlayer)?.takeUnless { it is FakePlayer }

    private fun id(p: ServerPlayer) = "p:${p.uuid}"

    private fun typeId(t: EntityType<*>) = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()

    private fun damageId(s: DamageSource) = s.typeHolder().unwrapKey().map { it.location().toString() }.orElse("")
}
