package kami.claims.war

import kami.claims.Claim
import kami.claims.Config
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.TaxMode
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import kotlin.math.log2
import kotlin.math.pow

object Reputation {
    private val s get() = Config.s.war
    private const val LOSS = "loss"
    private const val THROTTLE = 10_000L
    private const val WITHER_RANGE = 16.0
    private const val POLICY_LOG = 5
    private const val FRONT = 8
    private val used = HashMap<String, Long>()
    private object Tags {
        val items: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("kami_claims", "war_material"))
        val entities: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("kami_claims", "war_material"))
    }

    fun change(c: Country, source: String, delta: Double) {
        if (delta == 0.0 || delta.isNaN()) return
        val t = now()
        if (t - c.repWeekStart >= 7 * Config.s.dayMillis) {
            c.repWeek.clear()
            c.repWeekStart = t
        }
        val d = if (delta > 0) delta.coerceAtMost(s.repWeeklyGainCap - (c.repWeek[source] ?: 0.0)) else delta.coerceAtLeast(-s.repWeeklyLossCap - (c.repWeek[LOSS] ?: 0.0))
        if (d * delta <= 0) return
        c.repWeek.merge(if (d > 0) source else LOSS, d) { x, y -> x + y }
        c.reputation = (c.reputation + d).coerceIn(0.0, s.repMax)
        Realm.changed()
    }

    private fun expected(r: Double, opp: Double) = 1 / (1 + 10.0.pow((opp - r) / s.repScale))

    private fun recent(log: MutableMap<String, MutableList<Long>>, key: String, days: Int): MutableList<Long> {
        val cut = now() - days * Config.s.dayMillis
        log.values.forEach { l -> l.removeAll { it <= cut } }
        log.values.removeAll { it.isEmpty() }
        return log.getOrPut(key) { mutableListOf() }
    }

    fun trade(a: Country, b: Country, value: Long) {
        if (a.id == b.id || value < s.repTradeMinValue) return
        val ea = expected(a.reputation, b.reputation)
        val eb = expected(b.reputation, a.reputation)
        traded(a, b, ea)
        traded(b, a, eb)
    }

    private fun traded(c: Country, o: Country, e: Double) {
        val log = recent(c.tradeLog, o.id, s.repTradeDays)
        if (log.size >= s.repTradeMax) return
        change(c, "trade", s.repTrade * (if (log.isEmpty()) 1.0 else s.repTradeRepeat) * (1 - e))
        log += now()
    }

    fun policy(c: Country, target: Country) {
        val log = recent(c.policyLog, target.id, s.repEmbargoDays)
        log += now()
        if (log.size > POLICY_LOG) log.subList(0, log.size - POLICY_LOG).clear()
        if (log.size > 1) change(c, "embargo", -s.repEmbargoSpam)
    }

    fun tribute(c: Country, parent: Country, income: Long) {
        val rate = if (c.taxMode == TaxMode.PERCENT) c.taxAmount else if (income > 0) c.taxAmount / income else return
        val (lo, hi) = Config.s.provinceTaxRateBounds
        val third = (hi - lo) / 3
        val t = now()
        val window = s.repProvinceKeptDays * Config.s.dayMillis
        if (c.tributeOf != parent.id) c.tributeHeld = t
        if (c.tributeOf != parent.id || rate > lo + third) {
            c.tributeOf = parent.id
            c.tributeSince = t
        } else if (t - c.tributeSince >= window) {
            c.tributeSince = t
            change(parent, "province", s.repProvinceKept)
        }
        if (rate >= hi - third && held(c, parent) && t - c.tributeHigh >= window) {
            c.tributeHigh = t
            change(parent, "tribute", -s.repHighTribute)
        }
    }

    private fun held(c: Country, parent: Country) = c.tributeOf == parent.id && now() - c.tributeHeld >= s.repProvinceKeptDays * Config.s.dayMillis

    fun rebellion(province: Country, overlord: Country) {
        if (held(province, overlord)) change(overlord, "rebellion", -s.repRebellion)
    }

    fun instalment(c: Country) = change(c, "loan", s.repInstalment)

    fun overdueLoan(c: Country) = change(c, "loan", -s.repOverdueLoan)

    fun payoff(c: Country) = change(c, "loan_payoff", s.repPayoff)

    fun upkeepLoss(c: Country) = change(c, "upkeep", -s.repUpkeepLoss)

    fun decay() {
        Realm.data.countries.values.filter { it.active }.forEach { it.reputation = (it.reputation + (s.repStart - it.reputation) * s.repDecay).coerceIn(0.0, s.repMax) }
    }

    fun factor(c: Country) = (1 - (c.reputation - s.repStart) / s.repMax).coerceIn(s.repFeeMin, s.repFeeMax)

    fun tradeFactor(c: Country): Double {
        val r = c.reputation.coerceIn(0.0, s.repMax)
        val f = if (r >= s.repStart) 1 - s.repTradeFee * (r - s.repStart) / (s.repMax - s.repStart).coerceAtLeast(1.0)
        else 1 + s.repTradeFee * (s.repStart - r) / s.repStart
        return f.coerceIn(1 - s.repTradeFee, 1 + s.repTradeFee)
    }

    fun requireMin(receiver: Country, other: Country) {
        if (other.reputation < receiver.minReputation) throw Fail("kami_claims.error.reputation.min", v(receiver.name), v(receiver.minReputation))
    }

    fun setMin(c: Country, value: Int): Phrase {
        if (value !in 0..s.repMax.toInt()) throw Fail("kami_claims.error.reputation.min_range", v(s.repMax.toInt()))
        c.minReputation = value
        return Phrase.of("kami_claims.done.reputation.min", v(value))
    }

    private fun guarded(a: Country, b: Country) = a.level >= s.repGuardLevel && b.level >= s.repGuardLevel

    fun declared(attacker: Country, defender: Country) {
        val ratio = Power.score(attacker) / Power.score(defender)
        if (ratio > 1) change(attacker, "weaker", -(s.repWeaker * log2(ratio)).coerceAtMost(s.repWeakerMax))
    }

    fun ended(war: War) {
        val a = Realm.country(war.attacker.main)?.takeIf { it.active } ?: return
        val d = Realm.country(war.defender.main)?.takeIf { it.active } ?: return
        val fought = war.warSince > 0
        val guard = fought || guarded(a, d)
        when (war.result) {
            WarResult.ATTACKER, WarResult.DEFENDER -> if (fought) {
                val (winner, loser) = if (war.result == WarResult.ATTACKER) a to d else d to a
                val e = expected(winner.reputation, loser.reputation)
                change(winner, "war", s.repWin * (1 - e))
                change(loser, "war", -s.repLoss * (1 - e))
            }
            WarResult.WITHDRAWN -> if (guard) change(a, "withdraw", -s.repWithdraw)
            else -> Unit
        }
        if (!fought && guard && !war.silent && (war.result == WarResult.PEACE || war.result == WarResult.ATTACKER)) listOf(a, d).forEach { change(it, "peaceful", s.repPeaceful) }
        if (!fought) return
        if (war.result != WarResult.ATTACKER) war.defender.joined.filter { (war.defender.bill[it] ?: 0L) > 0 }.mapNotNull(Realm::country).filter { it.active }.forEach { change(it, "ally", s.repDefendAlly) }
        val value = StakeRules.value(war.stakes, d)
        if (value <= 0) return
        war.sides.filter { side -> side.bill.values.sum().let { it > 0 && it < value * s.repCleanWarPct } }.forEach { side -> side.countries.mapNotNull(Realm::country).forEach { change(it, "clean", s.repCleanWar) } }
    }

    fun material(p: Player, target: String?) {
        if (!Wars.fighting() || p is FakePlayer) return
        val c = Realm.of(p.stringUUID) ?: return
        val war = (if (target != null) WarGuard.wars(p, c, target) else Realm.data.wars.filter { it.phase == WarPhase.WAR && Wars.combatant(it, c, p.stringUUID) }).firstOrNull() ?: return
        val t = now()
        if (t - (used[p.stringUUID] ?: 0L) < THROTTLE) return
        if (used.size > 256) used.values.removeAll { t - it >= THROTTLE }
        used[p.stringUUID] = t
        val side = war.side(c.id) ?: return
        if (side.materialFirst == 0L) side.materialFirst = t
        val first = war.opponent(side).materialFirst
        val cost = if (first in 1 until side.materialFirst) s.repMaterial / 2 else s.repMaterial
        val spent = side.material[c.id] ?: 0.0
        val loss = cost.coerceAtMost(s.repMaterialMax - spent)
        if (loss <= 0) return
        side.material[c.id] = spent + loss
        change(c, "material", -loss)
    }

    private fun fighter(p: Player) = Realm.of(p.stringUUID)?.let { c -> Realm.data.wars.any { it.phase == WarPhase.WAR && Wars.combatant(it, c, p.stringUUID) } } == true

    fun hit(attacker: Player, victim: Player, direct: Entity?) {
        if (direct == null || direct === attacker || !direct.type.`is`(Tags.entities) && !attacker.mainHandItem.`is`(Tags.items)) return
        Realm.of(victim.stringUUID)?.let { material(attacker, it.id) }
    }

    fun far(p: Player, cl: Claim) {
        val c = Realm.of(p.stringUUID) ?: return
        val side = WarGuard.wars(p, c, cl.country).firstOrNull()?.side(c.id) ?: return
        val loss = s.repFar.coerceAtMost(s.repFarDamage - side.farDamage)
        if (loss <= 0 || front(side, cl)) return
        side.farDamage += loss
        change(c, "far", -loss)
    }

    private fun front(side: WarSide, cl: Claim): Boolean {
        val own = side.countries
        for (x in cl.x - FRONT..cl.x + FRONT) for (z in cl.z - FRONT..cl.z + FRONT) if (Realm.index[Key(cl.dim, x, z)]?.country in own) return true
        return false
    }

    fun onJoin(e: EntityJoinLevelEvent) {
        if (e.entity !is WitherBoss || e.loadedFromDisk() || e.level.isClientSide || !Wars.fighting()) return
        val w = e.entity
        e.level.getNearestPlayer(w.x, w.y, w.z, WITHER_RANGE) { p -> p is Player && fighter(p) }?.let { material(it, null) }
    }
}
