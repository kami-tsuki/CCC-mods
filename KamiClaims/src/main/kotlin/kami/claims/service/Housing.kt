package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Levels
import kami.claims.research.Progress
import kami.claims.research.Research
import kami.claims.service.Words.days
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class PlotBlock(val key: String) {
    COUNTRY("unknown_country"), TYPE("plot_type"), TAKEN("plot_taken"), BANISHED("you_banished"), LOCKED("plot_locked"),
    RANK("rank"), LEVEL("plot_not_offered"), OFFER("plot_not_offered"), LIMIT("plot_limit"), ADJACENT("plot_adjacent");

    val wire get() = name.lowercase()
}

object Housing {
    const val RESIDENTIAL = "residential"

    internal var pay: (String, Int) -> Boolean = { id, n -> Bank.take(UUID.fromString(id), n) }

    val features = mapOf(
        Claimant.PARENT to Features.PLOTS_FAMILY, Claimant.PROVINCE to Features.PLOTS_FAMILY,
        Claimant.ALLIED to Features.PLOTS_ALLIES, Claimant.RANDOM to Features.PLOTS_PUBLIC
    )

    fun rent(c: Country, category: Claimant = Claimant.CITIZEN): Int = c.offer.rent[category] ?: Config.s.rentDefaults[category] ?: 0

    fun rent(c: Country, cl: Claim, category: Claimant = Claimant.CITIZEN): Int = cl.offer?.rent?.get(category) ?: rent(c, category)

    fun effective(cl: Claim): Claimant = if (cl.state == Tenancy.REMOVED) Claimant.RANDOM else cl.category ?: Claimant.CITIZEN

    fun rate(c: Country, cl: Claim): Int = rent(c, cl, effective(cl))

    fun income(c: Country): Long = Realm.claims(c.id).filter { it.owner != null && it.state != Tenancy.MOVING_OUT }.sumOf { rate(c, it).toLong() }

    fun tenanted(cl: Claim) = cl.owner != null && !(cl.state == Tenancy.MOVING_OUT && now() >= cl.until)

    fun plotAccess(cl: Claim, me: String, action: Action, banned: Boolean = false): Boolean {
        if (banned && me != cl.owner) return false
        val role = if (me == cl.owner) Role.OWNER else cl.roles[me]
        if (cl.state == Tenancy.MOVING_OUT) return (role == Role.OWNER || role == Role.HOUSEHOLD) && action != Action.PLACE
        return when (role) {
            Role.OWNER, Role.HOUSEHOLD -> true
            Role.ALLIED -> action in Config.s.plotAlliedSet
            else -> false
        }
    }

    fun fluidFlows(from: Claim?, to: Claim?, wild: Boolean): Boolean = when {
        from == null && to == null -> wild
        from == null || to == null -> false
        from.country != to.country -> false
        else -> from.tenant == to.tenant
    }

    fun category(c: Country, id: String): Claimant? {
        if (id in c.members) return Claimant.CITIZEN
        if (c.outsiders[id] == Rank.BANISHED) return null
        val home = Realm.of(id)
        if (home != null && home.id != c.id) when {
            c.parent == home.id -> return Claimant.PARENT
            home.parent == c.id || (c.parent != null && c.parent == home.parent) -> return Claimant.PROVINCE
            home.id in c.alliances -> return Claimant.ALLIED
        }
        return if (c.outsiders[id] == Rank.ALLIED && id !in c.autoAllies) Claimant.ALLIED else Claimant.RANDOM
    }

    fun offer(c: Country, cl: Claim): Offer = cl.offer ?: c.offer

    fun open(c: Country, cl: Claim, cat: Claimant): Boolean =
        cat == Claimant.CITIZEN || (cat in offer(c, cl).open && features[cat]?.let { Features.unlocked(c, it) } != false)

    fun limit(c: Country, id: String): Int {
        val own = c.playerPlots[id] ?: c.members[id]?.let { c.rankPlots[it.rank] } ?: c.guestPlots[category(c, id) ?: Claimant.RANDOM]
        return min(Levels.capacity(c, Capacity.PLOTS), own ?: 0)
    }

    fun blocker(c: Country, cl: Claim, id: String, held: Collection<Key> = Realm.held(c.id)[id].orEmpty()): PlotBlock? {
        val cat = category(c, id)
        val feature = cat?.let { features[it] }
        val rank = c.members[id]?.rank
        return when {
            !c.active -> PlotBlock.COUNTRY
            !cl.plot -> PlotBlock.TYPE
            tenanted(cl) -> PlotBlock.TAKEN
            cat == null -> PlotBlock.BANISHED
            (c.reclaimLocks[id] ?: 0) > now() -> PlotBlock.LOCKED
            rank != null && !Config.s.can(rank, Cap.PLOT) -> PlotBlock.RANK
            feature != null && !Features.unlocked(c, feature) -> PlotBlock.LEVEL
            cat != Claimant.CITIZEN && cat !in offer(c, cl).open -> PlotBlock.OFFER
            held.size >= limit(c, id) -> PlotBlock.LIMIT
            held.isNotEmpty() && Realm.neighbors(cl).none { it in held } -> PlotBlock.ADJACENT
            else -> null
        }
    }

    fun claim(c: Country, cl: Claim, id: String) {
        val held = Realm.held(c.id)[id].orEmpty()
        val cat = category(c, id)
        val block = blocker(c, cl, id, held)
        if (block == PlotBlock.LEVEL) Features.require(c, features.getValue(cat!!))
        if (block != null) {
            val args: Array<Any> = when (block) {
                PlotBlock.BANISHED -> arrayOf(v(c.name))
                PlotBlock.LOCKED -> arrayOf(days(max(1L, ((c.reclaimLocks[id] ?: 0) - now()) / Config.s.dayMillis + 1)))
                PlotBlock.RANK -> arrayOf(Words.rank(Config.s.min(Cap.PLOT)))
                PlotBlock.LIMIT -> arrayOf(num(held.size), num(limit(c, id)))
                else -> emptyArray()
            }
            throw Fail("kami_claims.error.${block.key}", *args)
        }
        cl.vacate()
        cl.owner = id
        cl.category = cat
        Progress.report(c, "plot", "", 1)
    }

    fun release(c: Country, cl: Claim) {
        val moving = cl.state == Tenancy.MOVING_OUT
        if (cl.rentDebt > 0 && !moving) throw Fail("kami_claims.error.plot_debt")
        if (moving) lock(c, cl)
        cl.vacate()
        if (!c.active) finish(c)
    }

    private fun lock(c: Country, cl: Claim) {
        c.reclaimLocks[cl.owner!!] = cl.until + c.moveOutDays * Config.s.dayMillis
    }

    fun checkRemove(c: Country, cl: Claim, actor: Rank): String {
        val owner = cl.owner ?: throw Fail("kami_claims.error.plot_free")
        if (cl.state == Tenancy.MOVING_OUT) throw Fail("kami_claims.error.plot_moving")
        if ((c.rank(owner) ?: Rank.BANISHED) >= actor) throw Fail("kami_claims.error.lower_ranks")
        return owner
    }

    private fun repriced(c: Country, cl: Claim?, change: () -> Unit) {
        val plots = Realm.claims(c.id).filter { it.owner != null && it.state != Tenancy.MOVING_OUT && (cl == null || it === cl) }
        val before = plots.associateWith { rate(c, it) }
        change()
        plots.forEach { if (rate(c, it) != before[it]) Mail.direct(it.owner!!, Phrase.of("kami_claims.mail.plot_rent", coords(it), money(rate(c, it))), Tone.WARN) }
    }

    fun reset(c: Country, cl: Claim) = repriced(c, cl) { cl.offer = null }

    fun edit(c: Country, cl: Claim?, cat: Claimant, on: Boolean, rent: Int?) {
        if (cat == Claimant.CITIZEN && !on) throw Fail("kami_claims.error.plot_citizen")
        if (on) features[cat]?.let { Features.require(c, it) }
        repriced(c, cl) {
            val offer = if (cl == null) c.offer else cl.offer ?: Offer(c.offer.open.toMutableSet()).also { cl.offer = it }
            if (on) offer.open += cat else offer.open -= cat
            if (rent == null) offer.rent.remove(cat) else offer.rent[cat] = rent.coerceIn(0, Config.s.maxRent)
        }
    }

    fun departed(c: Country, id: String, reason: Leave) {
        Realm.claims(c.id).filter { it.owner == id && it.state != Tenancy.MOVING_OUT }.forEach {
            if (reason == Leave.LEAVE) refresh(c, it) else moveOut(c, it, reason.name.lowercase())
        }
    }

    fun moveOut(c: Country, cl: Claim, reason: String) {
        val owner = cl.owner ?: return
        if (cl.state == Tenancy.MOVING_OUT) return
        cl.state = Tenancy.MOVING_OUT
        cl.until = now() + c.moveOutDays * Config.s.dayMillis
        lock(c, cl)
        Mail.direct(owner, Phrase.of("kami_claims.mail.moveout.$reason", v(c.name), coords(cl), days(c.moveOutDays)), Tone.BAD)
    }

    internal fun refresh(c: Country, cl: Claim): Boolean {
        val owner = cl.owner ?: return true
        val cat = category(c, owner)
        if (cat == null) {
            moveOut(c, cl, "ban")
            return false
        }
        val state = if (open(c, cl, cat)) Tenancy.ACTIVE else Tenancy.REMOVED
        if (cat == cl.category && state == cl.state) return true
        cl.category = cat
        cl.state = state
        val rate = money(rate(c, cl))
        Mail.direct(owner, if (state == Tenancy.REMOVED) Phrase.of("kami_claims.mail.plot_removed", coords(cl), rate) else Phrase.of("kami_claims.mail.plot_category", coords(cl), Words.claimant(cat), rate), Tone.WARN)
        return true
    }

    fun collect(c: Country): Long {
        if (!c.active) return 0
        var income = 0L
        var room = Treasury.room(c)
        Realm.claims(c.id).filter { it.owner != null && it.state != Tenancy.MOVING_OUT }.forEach { cl ->
            val owner = cl.owner!!
            if (!refresh(c, cl)) return@forEach
            val base = min(rate(c, cl).toLong(), room)
            val extra = min(cl.rentDebt, room - base)
            val charged = when {
                pay(owner, (base + extra).toInt()) -> base + extra
                extra > 0 && pay(owner, base.toInt()) -> base
                else -> -1L
            }
            if (charged >= 0) {
                cl.rentDebt -= charged - base
                income += charged
                room -= charged
                if (charged > 0) xp(c, cl)
            } else cl.rentDebt += base
            if (cl.rentDebt > c.rentDebtLimit) moveOut(c, cl, "debt")
            else if (charged < 0) Mail.direct(owner, Phrase.of("kami_claims.mail.plot_tax_unpaid", money(base), money(cl.rentDebt), money(c.rentDebtLimit)), Tone.BAD)
        }
        Treasury.move(c, LedgerKind.PLOT_TAX, income)
        Progress.report(c, "taxes", "", income)
        return income
    }

    private fun xp(c: Country, cl: Claim) {
        val rate = Research.defs.levels.sources["rent"]?.rate ?: return
        val category = effective(cl)
        Progress.report(c, "rent", category.name.lowercase(), 1, units = (Config.s.rentXp[category] ?: 0) / rate)
    }

    fun warn(c: Country) {
        val t = now()
        Realm.claims(c.id).filter { it.owner != null && it.state == Tenancy.MOVING_OUT && it.until - t in 1..Config.s.dayMillis }
            .forEach { Mail.direct(it.owner!!, Phrase.of("kami_claims.mail.plot_one_day", coords(it)), Tone.WARN) }
    }

    fun sweep(): Boolean {
        val t = now()
        var freed = false
        Realm.data.countries.values.toList().forEach { c ->
            Realm.claims(c.id).filter { it.owner != null && it.state == Tenancy.MOVING_OUT && t >= it.until }.forEach {
                Mail.direct(it.owner!!, Phrase.of("kami_claims.mail.plot_lost", coords(it)), Tone.BAD)
                it.vacate()
                freed = true
            }
            if (!c.active) finish(c)
        }
        return freed
    }

    private fun finish(c: Country) {
        if (Realm.claims(c.id).none { it.owner != null }) Realm.erase(c)
    }

    private fun coords(cl: Claim) = v("${cl.x}, ${cl.z}")
}
