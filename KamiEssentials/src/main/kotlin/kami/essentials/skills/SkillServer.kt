package kami.essentials.skills

import kami.essentials.Config
import kami.essentials.chat.Names
import kami.essentials.net.Net
import kami.libs.db.Db
import kami.libs.skills.SkillClass
import kami.libs.skills.SkillProvider
import kami.libs.skills.Skills
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats as Vanilla
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

class SkillState(var cls: SkillClass? = null, var xp: Double = 0.0, var active: Long = 0, val nodes: MutableMap<Int, Int> = HashMap(), var refunds: Int = 0) {
    @Volatile var values: Map<String, Double> = emptyMap()
    var level = 0
    var dirty = false
    var reset = false
    var carry = 0.0
    var seconds = 0.0
    val crafted: MutableSet<String> = HashSet()

    fun refresh() {
        values = SkillTree.values(nodes)
        level = Curve.level(xp, active)
    }
}

@Serializable
class SkillView(
    val cls: String?, val level: Int, val maxLevel: Int, val xp: Long, val levelXp: Long, val nextXp: Long, val active: Long, val nextGate: Long,
    val points: Int, val spent: Int, val refunds: Int, val refundsUsed: Int, val nodes: Map<Int, Int>, val classBonus: Int
)

object Curve {
    private val s get() = Config.s.skills

    fun need(level: Int) = s.xpBase + s.xpScale * level.toDouble().pow(s.xpPower)

    fun total(level: Int) = (0 until level).sumOf { need(it) }

    fun gate(level: Int) = (s.gateScale * level.toDouble().pow(s.gatePower) * 3600).toLong()

    fun level(xp: Double, active: Long): Int {
        var level = 0
        var sum = 0.0
        while (level < s.maxLevel) {
            sum += need(level)
            if (sum > xp || gate(level + 1) > active) break
            level++
        }
        return level
    }

    fun points(level: Int) = level + level / 10

    fun refunds(level: Int) = level / 5
}

object SkillServer : SkillProvider {
    private class Grant(val player: UUID, val cls: SkillClass, val xp: Double, val bucket: String, val source: String)

    private const val IDLE_MS = 60_000L
    private const val SAVE_TICKS = 1200
    private const val MILLI = 1000.0
    private const val MAX_STEP_MS = 2000L
    private const val HEALTH = "kami_skill_health"
    private val states = ConcurrentHashMap<UUID, SkillState>()
    private val grants = ConcurrentLinkedQueue<Grant>()
    private val buckets = HashMap<String, Int>()
    private var bucketHour = 0L
    private val moved = HashMap<UUID, Long>()
    private val last = HashMap<UUID, Long>()
    private val looked = HashMap<UUID, Int>()
    private val stamp = HashMap<UUID, Long>()
    private val carried = HashMap<UUID, Float>()
    private val backfilled = HashSet<UUID>()

    val enabled get() = Config.s.skills.enabled

    fun load() {
        states.clear()
        Db.read {
            query("SELECT uuid, class, xp, active, nodes, refunds FROM ess_skill") { r ->
                val cls = SkillClass.of(r.stringOrNull("class"))
                val text = r.string("nodes")
                val stale = text.substringBefore('|', "") != SkillTree.signature
                UUID.fromString(r.string("uuid")) to SkillState(cls, r.double("xp"), r.long("active"), parse(text, cls), if (stale) 0 else r.int("refunds")).also { it.dirty = stale; it.reset = stale && cls != null }
            }.forEach { (id, s) -> states[id] = s }
            query("SELECT uuid FROM ess_skill_backfill") { r -> UUID.fromString(r.string("uuid")) }.forEach { backfilled += it }
            query("SELECT uuid, item FROM ess_skill_craft") { r -> UUID.fromString(r.string("uuid")) to r.string("item") }
                .forEach { (id, item) -> states[id]?.crafted?.add(item) }
        }
        states.values.forEach { it.refresh() }
        Skills.provider = this
        gauges()
    }

    fun unload() {
        save()
        Skills.provider = null
        states.clear()
        grants.clear()
        moved.clear()
        last.clear()
        looked.clear()
        stamp.clear()
        carried.clear()
        backfilled.clear()
        buckets.clear()
        subjects.clear()
        bucketHour = 0
        SkillEvents.reset()
        Bonus.reset()
    }

    private fun parse(text: String, cls: SkillClass?): MutableMap<Int, Int> {
        val (sig, body) = text.substringBefore('|', "") to text.substringAfter('|')
        if (sig == SkillTree.signature) return read(body)
        return cls?.let { hashMapOf(SkillTree.starts.getValue(it) to 1) } ?: HashMap()
    }

    private fun read(text: String): MutableMap<Int, Int> = text.split(',').mapNotNull { e ->
        val (id, rank) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        id.toIntOrNull()?.let { i -> rank.toIntOrNull()?.takeIf { it > 0 && i in SkillTree.nodes.indices }?.let { i to it } }
    }.toMap(HashMap())

    fun peek(id: UUID): SkillState? = states[id]

    fun all(): Map<UUID, SkillState> = states

    fun owners(): IntArray = IntArray(SkillTree.nodes.size).also { out -> states.values.forEach { s -> s.nodes.keys.forEach { if (it in out.indices) out[it]++ } } }

    private val subjects = HashMap<UUID, String>()
    private val playtime = HashMap<String, String>()

    private fun subject(id: UUID) = subjects.getOrPut(id) { "p:$id" }

    private fun tag(s: SkillState) = s.cls?.id ?: "none"

    private fun byClass(): Map<String, List<SkillState>> = states.values.mapNotNull { s -> s.cls?.let { it.id to s } }.groupBy({ it.first }, { it.second }).let { m -> SkillClass.entries.associate { it.id to m[it.id].orEmpty() } }

    private fun gauges() {
        Stats.gauge("skill.g.class") { byClass().mapValues { it.value.size.toLong() } }
        Stats.gauge("skill.g.avg") { byClass().mapValues { (_, v) -> if (v.isEmpty()) 0L else v.sumOf { it.level } * 100L / v.size } }
    }

    fun state(id: UUID): SkillState = states.getOrPut(id) { SkillState().also { it.refresh() } }

    override fun value(player: UUID, key: String): Double = if (enabled) Effects.capped(key, states[player]?.values?.get(key) ?: 0.0) else 0.0

    fun value(p: ServerPlayer, key: String) = if (p is FakePlayer) 0.0 else value(p.uuid, key)

    override fun grant(player: UUID, cls: SkillClass, source: String, scale: Double, bucket: String) {
        val base = Config.s.skills.xp[source] ?: return
        if (enabled && base > 0) grants += Grant(player, cls, base * scale, bucket, source)
    }

    fun tick(server: MinecraftServer) {
        if (!enabled) return
        val hour = System.currentTimeMillis() / 3_600_000L
        if (hour != bucketHour) { bucketHour = hour; buckets.clear() }
        while (true) apply(server, grants.poll() ?: break)
        if (server.tickCount % 20 == 0) server.playerList.players.forEach(::presence)
        if (server.tickCount % 40 == 0) server.playerList.players.forEach { if (active(it)) SkillEvents.pulse(it) }
        if (server.tickCount % SAVE_TICKS == 0) save()
    }

    fun active(p: ServerPlayer) = System.currentTimeMillis() - (moved[p.uuid] ?: 0L) <= IDLE_MS

    private fun presence(p: ServerPlayer) {
        val now = System.currentTimeMillis()
        val delta = min(now - (stamp.put(p.uuid, now) ?: now), MAX_STEP_MS)
        val acted = p.lastActionTime
        if (last.put(p.uuid, acted) != acted) moved[p.uuid] = now
        val look = p.yRot.toBits() * 31 + p.xRot.toBits()
        if (looked.put(p.uuid, look) != look) moved[p.uuid] = now
        val s = state(p.uuid)
        if (s.reset) {
            s.reset = false
            p.displayClientMessage(Component.translatable("kami_essentials.skill.tree_reset"), false)
            Net.push(p)
        }
        if (staff(p) || delta <= 0 || now - (moved[p.uuid] ?: 0L) > IDLE_MS) return
        s.seconds += delta / MILLI
        val whole = s.seconds.toLong()
        s.seconds -= whole
        s.active += whole
        val gain = Config.s.skills.playtimeXpPerHour / 3600.0 * delta / MILLI
        s.xp += gain
        s.carry += gain * MILLI
        val milli = s.carry.toLong()
        s.carry -= milli
        val who = subject(p.uuid)
        val t = tag(s)
        if (whole > 0) Stats.add("skill.active", who, t, whole)
        Stats.add("skill.xp.src", who, playtime.computeIfAbsent(t) { "$it:playtime" }, milli)
        Stats.add("skill.xp.cls", who, t, milli)
        s.dirty = true
        levelCheck(p, s)
    }

    fun staff(p: ServerPlayer) = p.isCreative || p.isSpectator || p is FakePlayer

    fun used(p: ServerPlayer, key: String) {
        if (staff(p)) return
        Stats.add("skill.use", "", "${tag(state(p.uuid))}|$key")
    }

    private fun apply(server: MinecraftServer, g: Grant) {
        val player = server.playerList.getPlayer(g.player)
        if (player != null && staff(player)) return
        val s = state(g.player)
        val full = Config.s.skills.hourlyFull
        val local = buckets.merge("${g.player}|${g.bucket}", 1, Int::plus) ?: 1
        val total = buckets.merge("${g.player}|*${g.source}", 1, Int::plus) ?: 1
        val fade = min(fade(local, full.toLong()), fade(total, full * 4L))
        val bonus = (if (s.cls == g.cls) Config.s.skills.classBonus else 0.0) + Effects.capped(Effects.CLASS_XP, s.values[Effects.CLASS_XP] ?: 0.0)
        val potential = g.xp * (1 + bonus)
        val who = subject(g.player)
        val src = "${tag(s)}:${g.source}"
        Stats.add("skill.xp.lost", who, src, (potential * (1 - fade) * MILLI).roundToLong())
        if (fade <= 0.0) return
        val xp = potential * fade
        s.xp += xp
        s.dirty = true
        Stats.add("skill.xp.src", who, src, (xp * MILLI).roundToLong())
        Stats.add("skill.xp.cls", who, tag(s), (xp * MILLI).roundToLong())
        player?.let { levelCheck(it, s) }
    }

    private fun fade(n: Int, full: Long) = when { n <= full -> 1.0; n <= full * 2 -> 0.5; n <= full * 3 -> 0.1; else -> 0.0 }

    private fun levelCheck(p: ServerPlayer, s: SkillState) {
        val before = s.level
        s.level = Curve.level(s.xp, s.active)
        if (s.level <= before) return
        p.displayClientMessage(Component.translatable("kami_essentials.skill.level_up", s.level), false)
        p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.2f)
        for (level in before + 1..s.level) Stats.event("skill.level", subject(p.uuid), "${tag(s)}:$level", s.active)
        Net.push(p)
    }

    fun crafted(p: ServerPlayer, item: String): Boolean {
        val s = state(p.uuid)
        if (!s.crafted.add(item)) return false
        Db.write { exec("INSERT OR IGNORE INTO ess_skill_craft (uuid, item) VALUES (?, ?)", p.stringUUID, item) }
        return true
    }

    private fun save() {
        val dirty = states.entries.filter { it.value.dirty }
        if (dirty.isEmpty()) return
        val rows = dirty.map { (id, s) ->
            s.dirty = false
            listOf(id.toString(), s.cls?.id, s.xp, s.active, SkillTree.signature + "|" + s.nodes.entries.joinToString(",") { "${it.key}:${it.value}" }, s.refunds)
        }
        Db.write { batch("INSERT OR REPLACE INTO ess_skill (uuid, class, xp, active, nodes, refunds) VALUES (?, ?, ?, ?, ?, ?)", rows) }
    }

    fun saveNow(id: UUID) {
        val s = states[id] ?: return
        s.dirty = true
        save()
    }

    fun reapply() {
        ServerLifecycleHooks.getCurrentServer()?.playerList?.players?.forEach { attributes(it); Net.push(it) }
    }

    fun forget(p: ServerPlayer) {
        saveNow(p.uuid)
        moved.remove(p.uuid)
        last.remove(p.uuid)
        looked.remove(p.uuid)
        stamp.remove(p.uuid)
        carried.remove(p.uuid)
        if (p.isAlive) p.persistentData.putFloat(HEALTH, p.health)
        SkillEvents.forget(p)
    }

    fun act(p: ServerPlayer, name: String, arg: String) {
        if (!enabled) return
        val s = state(p.uuid)
        val error = when (name) {
            "skill_class" -> choose(p, s, SkillClass.of(arg))
            "skill_take" -> take(p, s, arg.toIntOrNull())
            "skill_refund" -> refund(p, s, arg.toIntOrNull())
            else -> return
        }
        if (error != null) return p.displayClientMessage(Component.translatable(error), true)
        s.refresh()
        saveNow(p.uuid)
        attributes(p)
    }

    private fun choose(p: ServerPlayer, s: SkillState, cls: SkillClass?): String? {
        if (cls == null) return "kami_essentials.skill.error.class"
        if (s.cls != null) return "kami_essentials.skill.error.class_set"
        s.cls = cls
        s.nodes[SkillTree.starts.getValue(cls)] = 1
        Stats.event("skill.class", subject(p.uuid), cls.id)
        return null
    }

    private fun track(key: String, p: ServerPlayer, node: SkillNode, rank: Int) {
        Stats.event(key, subject(p.uuid), "${node.name}|${node.kind.name.lowercase()}|${node.cls.id}", rank.toLong())
        Stats.add(key, "", "${node.cls.id}|${node.name}")
    }

    private fun error(d: Denial) = when (d) {
        Denial.MAXED -> "kami_essentials.skill.error.maxed"
        Denial.NO_CLASS -> "kami_essentials.skill.error.no_class"
        Denial.PATH -> "kami_essentials.skill.error.path"
        Denial.KEYSTONE -> "kami_essentials.skill.error.keystone"
        Denial.ALT -> "kami_essentials.skill.error.alt"
        Denial.LEVEL -> "kami_essentials.skill.error.level"
        Denial.SECTOR -> "kami_essentials.skill.error.sector"
        Denial.POINTS -> "kami_essentials.skill.error.points"
    }

    private fun take(p: ServerPlayer, s: SkillState, id: Int?): String? {
        val node = id?.let { SkillTree.nodes.getOrNull(it) } ?: return "kami_essentials.skill.error.node"
        SkillTree.denial(s.nodes, s.cls, s.level, Curve.points(s.level) - SkillTree.spent(s.nodes, s.cls), node)?.let { return error(it) }
        val rank = s.nodes[node.id] ?: 0
        s.nodes[node.id] = rank + 1
        track("skill.take", p, node, rank + 1)
        return null
    }

    private fun refund(p: ServerPlayer, s: SkillState, id: Int?): String? {
        val cls = s.cls ?: return "kami_essentials.skill.error.no_class"
        val node = id?.let { SkillTree.nodes.getOrNull(it) } ?: return "kami_essentials.skill.error.node"
        val rank = s.nodes[node.id] ?: return "kami_essentials.skill.error.node"
        if (node.id == SkillTree.starts[cls]) return "kami_essentials.skill.error.start"
        if (s.refunds >= Curve.refunds(s.level)) return "kami_essentials.skill.error.refunds"
        val next = HashMap(s.nodes)
        if (rank > 1) next[node.id] = rank - 1 else next.remove(node.id)
        if (!SkillTree.connected(next.keys, SkillTree.starts.getValue(cls))) return "kami_essentials.skill.error.split"
        val gated = next.any { (i, r) -> SkillTree.nodes[i].gate?.let { g -> SkillTree.sector(next, SkillTree.nodes[i].cls) < g.sector(r) } == true }
        if (gated) return "kami_essentials.skill.error.gated"
        s.nodes.clear()
        s.nodes.putAll(next)
        s.refunds++
        track("skill.refund", p, node, rank)
        return null
    }

    fun attributes(p: ServerPlayer) {
        val max = p.maxHealth
        val ratio = p.health / max
        modifiers(p)
        if (p.maxHealth != max) p.health = ratio * p.maxHealth
    }

    fun joined(p: ServerPlayer) {
        backfill(p)
        modifiers(p)
        val tag = p.persistentData
        if (!tag.contains(HEALTH)) return
        p.health = tag.getFloat(HEALTH)
        tag.remove(HEALTH)
    }

    private fun backfill(p: ServerPlayer) {
        if (!enabled || staff(p) || !backfilled.add(p.uuid)) return
        val c = Config.s.skills
        val s = state(p.uuid)
        val hours = min(Names.stat(p, Vanilla.PLAY_TIME) / 72_000.0 * c.backfillShare, c.backfillCapHours)
        val seconds = ((hours * 3600).toLong() - s.active).coerceAtLeast(0)
        Db.write { exec("INSERT OR IGNORE INTO ess_skill_backfill (uuid, hours, at) VALUES (?, ?, ?)", p.stringUUID, seconds / 3600.0, System.currentTimeMillis()) }
        if (seconds == 0L) return
        val xp = seconds / 3600.0 * c.playtimeXpPerHour
        s.active += seconds
        s.xp += xp
        s.dirty = true
        Stats.add("skill.xp.src", subject(p.uuid), "${tag(s)}:backfill", (xp * MILLI).roundToLong())
        p.displayClientMessage(Component.translatable("kami_essentials.skill.backfill", seconds / 3600, xp.roundToLong()), false)
        levelCheck(p, s)
        Net.push(p)
    }

    fun carry(p: ServerPlayer, health: Float) {
        carried[p.uuid] = health
    }

    fun respawned(p: ServerPlayer, full: Boolean) {
        modifiers(p)
        val old = carried.remove(p.uuid)
        if (full) p.health = p.maxHealth else if (old != null) p.health = min(old, p.maxHealth)
    }

    private fun modifiers(p: ServerPlayer) {
        val values = if (enabled && p !is FakePlayer) state(p.uuid).values else emptyMap()
        Effects.all.forEach { (key, e) ->
            val attr = e.attribute ?: return@forEach
            val inst = p.getAttribute(attr) ?: return@forEach
            val id = ResourceLocation.fromNamespaceAndPath("kami_essentials", "skill_$key")
            val v = Effects.capped(key, values[key] ?: 0.0) * e.sign
            if (v == 0.0) inst.removeModifier(id) else inst.addOrUpdateTransientModifier(AttributeModifier(id, v, e.op))
        }
        if (p.health > p.maxHealth) p.health = p.maxHealth
    }

    fun view(id: UUID): SkillView? {
        if (!enabled) return null
        val s = states[id] ?: SkillState().also { it.refresh() }
        val c = Config.s.skills
        return SkillView(
            s.cls?.id, s.level, c.maxLevel, s.xp.toLong(), Curve.total(s.level).toLong(), Curve.total(s.level + 1).toLong(), s.active, Curve.gate(s.level + 1),
            Curve.points(s.level), SkillTree.spent(s.nodes, s.cls), Curve.refunds(s.level), s.refunds, HashMap(s.nodes), (c.classBonus * 100).toInt()
        )
    }
}
