package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.TradePolicy
import kami.claims.economy.Treasury
import kami.claims.now
import kami.claims.research.Loans
import kami.claims.service.Announce
import kami.claims.service.Diplomacy
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.stats.Stats
import kami.libs.text.Phrase
import kami.libs.util.Time.HOUR_MS
import java.util.UUID

object Wars {
    private val s get() = Config.s.war
    private val day get() = Config.s.dayMillis
    private const val KEEP_DAYS = 30

    fun all(c: Country) = Realm.data.wars.filter { it.active && c.id in it.countries }

    fun of(c: Country) = all(c).firstOrNull { it.kind == WarKind.REGULAR }

    fun involved(c: Country) = all(c).isNotEmpty()

    fun fighting() = Realm.data.wars.any { it.phase == WarPhase.WAR }

    fun fighting(c: Country) = Realm.data.wars.any { it.phase == WarPhase.WAR && c.id in it.countries }

    fun enemies(a: String, b: String) = a != b && Realm.data.wars.any { w -> w.active && w.side(a)?.let { b in w.opponent(it).countries } == true }

    fun combatant(war: War, c: Country, uuid: String) = c.id in war.countries && (c.members[uuid]?.since ?: Long.MAX_VALUE) < war.declaredAt

    fun imposed(a: String, b: String) = Realm.data.wars.any { it.active && "$a|$b" in it.embargoes }

    internal fun pairUntil(a: Country, b: Country) = listOfNotNull(a.warPairs[b.id], a.president()?.let { b.warPairsBy[it] }).maxOrNull() ?: 0L

    fun pending(c: Country) = Realm.data.wars.filter { it.phase == WarPhase.DECLARATION && it.defender.main == c.id }

    fun hours(ms: Long) = Words.count("kami_claims.unit.hour", ((ms + HOUR_MS - 1) / HOUR_MS).coerceAtLeast(1))

    fun declare(attacker: Country, target: Country, kind: WarKind = WarKind.REGULAR, stakes: Stakes = Stakes()): Phrase {
        val fee = checkDeclare(attacker, target, kind, stakes)
        val t = now()
        val id = generateSequence { UUID.randomUUID().toString().take(8) }.first { id -> Realm.data.wars.none { it.id == id } }
        val ends = t + s.declarationDays * day
        val war = War(id, kind, WarSide(attacker.id), WarSide(target.id), t, phaseEnds = ends, stakes = stakes, fee = fee, escrow = stakes.money, remindedAt = t)
        Treasury.bank(attacker, LedgerKind.WAR_FEE, -fee, note = target.name)
        WarDebt.escrow(attacker, LedgerKind.WAR_STAKE, -stakes.money, target.name)
        Joining.snapshot(war)
        Realm.data.wars += war
        Stats.event("war.declare", "c:${attacker.id}", "c:${target.id}")
        attacker.warDeclared.removeAll { it < t - s.feeDoublingDays * day }
        attacker.warDeclared += t
        if (kind == WarKind.REGULAR) Reputation.declared(attacker, target) else Reputation.rebellion(attacker, target)
        val until = t + s.pairCooldownDays * day
        attacker.warPairs.values.removeAll { it <= t }
        attacker.warPairs[target.id] = until
        target.warPairsBy.values.removeAll { it <= t }
        attacker.president()?.let { target.warPairsBy[it] = until }
        tell(target.id, Phrase.of("kami_claims.mail.war.declared", v(attacker.name), hours(war.phaseEnds - t)), Tone.WARN)
        if (stakes.vassal && kind == WarKind.REGULAR) target.parent?.let {
            tell(it, Phrase.of("kami_claims.mail.war.province_target", v(attacker.name), v(target.name)), Tone.WARN)
        }
        Announce.fire("warDeclared", attacker, mapOf("country" to attacker.name, "other" to target.name))
        Realm.changed()
        return Phrase.of("kami_claims.done.war.declared", v(target.name))
    }

    fun checkDeclare(attacker: Country, target: Country, kind: WarKind, stakes: Stakes): Long {
        val t = now()
        if (attacker.id == target.id) throw Fail("kami_claims.error.war.self")
        WarLocks.warProvince(attacker)
        if (kind == WarKind.REGULAR && Diplomacy.sameFamily(attacker, target)) throw Fail("kami_claims.error.war.family")
        if (kind == WarKind.REBELLION && attacker.parent != target.id) throw Fail("kami_claims.error.war.not_overlord")
        if (!attacker.active || !target.active) throw Fail("kami_claims.error.war.inactive")
        listOf(attacker, target).forEach {
            if (t - it.created < s.foundingGraceDays * day) throw Fail("kami_claims.error.war.young", v(it.name))
            if (it.warCooldownUntil > t) throw Fail("kami_claims.error.war.cooldown", v(it.name), hours(it.warCooldownUntil - t))
        }
        val pair = pairUntil(attacker, target)
        if (pair > t) throw Fail("kami_claims.error.war.pair_cooldown", v(target.name), hours(pair - t))
        if (all(attacker).any { target.id in it.countries }) throw Fail("kami_claims.error.war.already", v(target.name))
        if (kind == WarKind.REGULAR) listOf(attacker, target).forEach { if (of(it) != null) throw Fail("kami_claims.error.war.busy", v(it.name)) }
        if (stakes.money > 0) Loans.requireNoDebt(attacker, "kami_claims.loans.error.disband")
        if (stakes.vassal && target.parent != null) Loans.requireNoResearchLoans(target, "kami_claims.error.war.province_loans")
        return StakeRules.validate(attacker, target, kind, stakes)
    }

    fun fight(defender: Country, attacker: Country? = null): Phrase {
        val war = answerable(defender, attacker)
        val t = now()
        war.phase = WarPhase.PREPARATION
        Power.snapshot(war)
        war.phaseEnds = t + s.preparationDays * day
        war.extendedDays = 0
        war.offer = null
        val other = name(war.attacker.main)
        tell(war.attacker.main, Phrase.of("kami_claims.mail.war.fight", v(defender.name), hours(war.phaseEnds - t)), Tone.WARN)
        answered(war, defender, "fight back", "fight")
        Realm.changed()
        return Phrase.of("kami_claims.done.war.fight", v(other))
    }

    fun agree(defender: Country, attacker: Country? = null): Phrase = agree(defender, agreeable(defender, attacker))

    internal fun agree(defender: Country, war: War): Phrase {
        val other = name(war.attacker.main)
        tell(war.attacker.main, Phrase.of("kami_claims.mail.war.agreed", v(defender.name)))
        answered(war, defender, "agreed to the demands", "agree")
        end(war, WarResult.ATTACKER)
        return Phrase.of("kami_claims.done.war.agreed", v(other))
    }

    fun withdraw(attacker: Country, defender: Country? = null): Phrase = withdraw(attacker, withdrawable(attacker, defender))

    internal fun withdraw(attacker: Country, war: War): Phrase {
        tell(war.defender.main, Phrase.of("kami_claims.mail.war.withdrawn", v(attacker.name)))
        end(war, WarResult.WITHDRAWN)
        return Phrase.of("kami_claims.done.war.withdrawn", v(name(war.defender.main)))
    }

    fun capitulate(c: Country, other: Country? = null): Phrase = capitulate(c, surrenderable(c, other))

    internal fun capitulate(c: Country, war: War): Phrase {
        val winner = if (war.attacker.main == c.id) war.defender else war.attacker
        tell(winner.main, Phrase.of("kami_claims.mail.war.capitulated", v(c.name)))
        war.surrendered = true
        end(war, if (winner === war.attacker) WarResult.ATTACKER else WarResult.DEFENDER)
        return Phrase.of("kami_claims.done.war.capitulated", v(name(winner.main)))
    }

    internal fun end(war: War, result: WarResult) {
        war.result = result
        Stats.event("war.end", "c:${war.attacker.main}", "c:${war.defender.main}", result.ordinal.toLong())
        war.offer = null
        war.extendedDays = 0
        if (result == WarResult.WITHDRAWN) return close(war)
        war.phase = WarPhase.SETTLEMENT
        war.phaseEnds = now() + s.settlementDays * day
        Realm.changed()
    }

    fun resume() {
        val d = Realm.data
        val t = now()
        val gap = t - d.warClock
        if (d.warClock > 0 && gap > s.downtimeGapMinutes * 60_000L) pause(gap, d.warClock)
        d.warClock = t
    }

    fun tick() {
        resume()
        val d = Realm.data
        val t = now()
        d.wars.filter { it.active && it.phase != WarPhase.SETTLEMENT && it.sides.any { side -> Realm.country(side.main)?.active != true } }.forEach {
            if (it.phase == WarPhase.DECLARATION || it.phase == WarPhase.PREPARATION) { it.result = WarResult.WHITE; close(it, false) } else end(it, WarResult.WHITE)
        }
        d.wars.filter { it.active }.forEach(::prune)
        d.wars.filter { it.active && it.phaseEnds in 1..t }.forEach(::expire)
        d.wars.filter { it.phase == WarPhase.WAR }.forEach {
            Exhaustion.update(it)
            if (it.sides.any { side -> side.exhaustion >= 100.0 }) exhausted(it)
        }
        d.wars.filter { it.phase == WarPhase.DECLARATION && t - it.remindedAt >= s.reminderHours * HOUR_MS }.forEach {
            it.remindedAt = t
            tell(it.defender.main, Phrase.of("kami_claims.mail.war.reminder", v(name(it.attacker.main)), hours(it.phaseEnds - t)), Tone.WARN)
        }
        d.wars.removeAll { !it.active && t - it.endedAt > KEEP_DAYS * day }
    }

    private fun prune(war: War) = war.sides.forEach { side ->
        side.joined.filter { Realm.country(it)?.active != true }.forEach { id ->
            side.joined -= id
            side.powers -= id
            side.bill -= id
            side.deaths -= id
            war.eligible -= id
            war.rebels.entries.removeAll { it.key == id || it.value == id }
        }
    }

    private fun exhausted(war: War) {
        val msg = Phrase.of("kami_claims.mail.war.exhausted", v(name(war.attacker.main)), v(name(war.defender.main)))
        mail(war.sides.map { it.main }, msg)
        end(war, WarResult.WHITE)
    }

    private fun pause(gap: Long, since: Long) {
        Realm.data.wars.filter { it.active }.forEach { w ->
            if (w.phaseEnds > 0) w.phaseEnds += gap
            if (w.phase == WarPhase.CEASEFIRE) w.ceasefireStart += gap
            if (w.phase == WarPhase.WAR) w.warSince += gap
            w.remindedAt += gap
            w.offer?.let { o -> o.at += gap }
            w.offeredAt.replaceAll { _, at -> at + gap }
        }
        Realm.data.countries.values.forEach { c ->
            c.lastActive += gap
            c.members.values.forEach { it.seen += gap }
            if (c.warCooldownUntil > since) c.warCooldownUntil += gap
            listOf(c.warPairs, c.warPairsBy).forEach { m -> m.replaceAll { _, until -> if (until > since) until + gap else until } }
        }
    }

    private fun expire(war: War) {
        when (war.phase) {
            WarPhase.DECLARATION -> {
                val msg = Phrase.of("kami_claims.mail.war.silence", v(name(war.defender.main)), v(name(war.attacker.main)))
                mail(war.sides.map { it.main }, msg, Tone.WARN)
                war.silent = true
                end(war, WarResult.ATTACKER)
            }
            WarPhase.PREPARATION, WarPhase.CEASEFIRE -> {
                war.phase = WarPhase.WAR
                war.warSince = now()
                war.phaseEnds = 0
                war.extendedDays = 0
                war.offer = null
                val msg = Phrase.of("kami_claims.mail.war.started", v(name(war.attacker.main)), v(name(war.defender.main)))
                mail(war.sides.map { it.main }, msg, Tone.BAD)
                Realm.country(war.attacker.main)?.let {
                    val vars = mapOf("country" to it.name, "other" to name(war.defender.main), "phase" to "war")
                    Announce.fire("warPhase", it, vars, mapOf("phase" to Phrase.of("kami_claims.war.phase.war")))
                }
                Realm.changed()
            }
            WarPhase.SETTLEMENT -> close(war)
            else -> Unit
        }
    }

    internal fun close(war: War, cooldown: Boolean = true) {
        val t = now()
        war.phase = WarPhase.ENDED
        war.phaseEnds = 0
        war.endedAt = t
        val mains = war.sides.mapNotNull { Realm.country(it.main) }
        lift(war)
        Reputation.ended(war)
        Spoils.apply(war)
        if (cooldown) mains.filter { war.result != WarResult.WITHDRAWN || it.id == war.defender.main }.forEach { it.warCooldownUntil = t + s.cooldownDays * day }
        val msg = Phrase.of("kami_claims.mail.war.ended", v(name(war.attacker.main)), v(name(war.defender.main)))
        mail(mains.map { it.id }, msg)
        val (result, local) = outcome(war)
        val vars = mapOf("country" to name(war.attacker.main), "other" to name(war.defender.main), "result" to result)
        mains.firstOrNull()?.let { Announce.fire("warEnded", it, vars, mapOf("result" to local)) }
        Realm.changed()
    }

    private fun lift(war: War) = war.embargoes.forEach { e ->
        val (a, b) = e.split('|').mapNotNull(Realm::country).takeIf { it.size == 2 } ?: return@forEach
        if (!imposed(a.id, b.id)) Diplomacy.store(a, b, TradePolicy(Diplomacy.policy(a, b).tariffPct, false))
    }

    private fun outcome(war: War) = when (val r = war.result) {
        WarResult.ATTACKER, WarResult.DEFENDER -> {
            val winner = name((if (r == WarResult.ATTACKER) war.attacker else war.defender).main)
            "$winner won" to Phrase.of("kami_claims.war.result.won", v(winner))
        }
        else -> (r?.name?.lowercase() ?: "ended").let { (if (r == WarResult.WHITE) "white peace" else it) to Phrase.of("kami_claims.war.result.$it") }
    }

    internal fun answered(war: War, defender: Country, choice: String, key: String) = Announce.fire("warAnswered", defender,
        mapOf("country" to name(war.attacker.main), "other" to defender.name, "choice" to choice), mapOf("choice" to Phrase.of("kami_claims.war.choice.$key")))

    internal fun answerable(c: Country, other: Country?) = pick(c, other, setOf(WarPhase.DECLARATION)) { it.defender }

    internal fun agreeable(c: Country, other: Country?) = answerable(c, other).also { if (c.parent != null) throw Fail("kami_claims.error.war.province_agree") }

    internal fun withdrawable(c: Country, other: Country?) = pick(c, other, setOf(WarPhase.DECLARATION)) { it.attacker }
        .also { if (it.rebels.isNotEmpty()) throw Fail("kami_claims.error.war.withdraw_rebels") }

    internal fun surrenderable(c: Country, other: Country?) = pick(c, other, setOf(WarPhase.WAR, WarPhase.CEASEFIRE)) { if (it.attacker.main == c.id) it.attacker else it.defender }

    internal fun opponent(war: War, c: Country) = country(if (war.attacker.main == c.id) war.defender.main else war.attacker.main)

    private fun pick(c: Country, other: Country?, phases: Set<WarPhase>, role: (War) -> WarSide): War {
        val t = now()
        val hits = Realm.data.wars.filter {
            val live = it.phase in phases && (it.phaseEnds == 0L || it.phaseEnds > t)
            live && role(it).main == c.id && (other == null || it.opponent(role(it)).main == other.id)
        }
        return hits.singleOrNull() ?: throw Fail(if (hits.isEmpty()) "kami_claims.error.war.none" else "kami_claims.error.war.which")
    }

    fun rebel(c: Country) = Realm.data.wars.any { it.active && it.kind == WarKind.REBELLION && it.attacker.main == c.id }

    internal fun name(id: String) = Realm.country(id)?.name ?: id

    internal fun country(id: String) = Realm.country(id) ?: throw Fail("kami_claims.error.war.none")

    internal fun mail(ids: Iterable<String>, text: Phrase, tone: Tone = Tone.INFO) = ids.mapNotNull(Realm::country).forEach { Mail.officers(it, text, tone) }

    private fun tell(id: String, text: Phrase, tone: Tone = Tone.INFO) = mail(listOf(id), text, tone)
}
