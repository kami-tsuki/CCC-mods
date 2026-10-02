package kami.claims.service

import kami.claims.Cap
import kami.claims.Config
import kami.claims.Country
import kami.claims.Rank
import kami.claims.Realm
import kami.claims.Tenancy
import kami.claims.now
import kami.claims.research.Buffs
import kami.claims.research.Loans
import kami.claims.today
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer

@Serializable
class AlertLine(
    val id: String, val severity: String, val title: String, val body: String,
    val action: String = "", val act: String = "", val args: List<String> = emptyList(),
    val page: String = "", val focus: String = ""
)

object Alerts {
    private val s get() = Config.s

    private fun t(key: String, vararg args: Any) = Phrase.of(key, *args)
    private fun days(n: Number) = Phrase.plural("kami_claims.unit.day", n.toLong())
    private fun money(n: Long) = Phrase.money(n)

    private fun alert(
        id: String, severity: String, title: Phrase, body: Phrase, action: Phrase? = null,
        act: String = "", args: List<String> = emptyList(), page: String = "", focus: String = ""
    ) = AlertLine(id, severity, title.json(), body.json(), action?.json() ?: "", act, args, page, focus)

    fun nextBill(c: Country): Long {
        val tomorrow = today() + 1
        val borderTax = Buffs.tax(c)
        return Realm.claims(c.id).filter { !it.free && tomorrow > it.since && (tomorrow - it.since) % Realm.period(it) == 0L }
            .sumOf { Buffs.price(c, it, borderTax).toLong() * (1 + it.debt) }
    }

    fun of(p: ServerPlayer, c: Country?, delegated: Boolean): List<AlertLine> {
        val me = p.stringUUID
        val out = ArrayList<AlertLine>()
        if (c == null) {
            Realm.data.countries.values.filter { it.active && (it.invites[me] ?: 0) > now() }.forEach {
                out += alert("invite:${it.id}", "INFO", t("kami_claims.alert.invite.title", it.name), t("kami_claims.alert.invite.body"),
                    t("kami_claims.common.join_x", it.name), "accept", listOf(it.name), "welcome")
            }
            return out
        }
        val rank = if (delegated) Rank.CHANCELLOR else Service.rankOf(c, p)
        fun can(cap: Cap) = delegated || rank >= s.min(cap)
        val claims = Realm.claims(c.id)
        val sum = Upkeep.summary(c)
        if (can(Cap.DETAILS)) {
            val debt = claims.filter { it.debt > 0 }
            if (debt.isNotEmpty()) {
                val worst = debt.maxOf { it.debt }
                val shortfall = (nextBill(c) - c.treasury).coerceAtLeast(1)
                val detail = if (worst >= s.maxDebt - 1) t("kami_claims.alert.debt.body_final")
                else t("kami_claims.alert.debt.body", days(s.maxDebt), worst.toString(), s.maxDebt.toString())
                out += alert("debt", "DANGER", t("kami_claims.alert.debt.title", debt.size.toString()), detail,
                    t("kami_claims.common.deposit_x", money(shortfall)), "deposit", listOf(shortfall.toString()), "chunks", "debt")
            } else {
                val bill = nextBill(c)
                if (bill > c.treasury) out += alert("bill", "WARNING", t("kami_claims.alert.bill.title"), t("kami_claims.alert.bill.body", money(bill), money(c.treasury)),
                    t("kami_claims.common.deposit_x", money(bill - c.treasury)), "deposit", listOf((bill - c.treasury).toString()), "budget")
            }
            if (Loans.inDefault(c)) out += alert("loan_default", "DANGER", t("kami_claims.loans.reason.default"),
                t("kami_claims.alert.loan_default.body", money(c.loans.sumOf { it.overdue })), t("kami_claims.alert.open.loans"), page = "loans")
            else if (c.loans.isNotEmpty() && Loans.dueNext(c) > c.treasury) out += alert("loan_due", "WARNING", t("kami_claims.alert.loan_due.title"),
                t("kami_claims.alert.loan_due.body", money(Loans.dueNext(c)), money(c.treasury)), t("kami_claims.common.deposit_x", money(Loans.dueNext(c) - c.treasury)),
                "deposit", listOf((Loans.dueNext(c) - c.treasury).toString()), "loans")
            val net = sum.upkeep + sum.jobs - sum.income
            if (net > 0 && claims.any { !it.free }) {
                val left = c.treasury / net
                if (left < 14) out += alert("runway", if (left < 3) "DANGER" else "WARNING", t("kami_claims.alert.runway.title", days(left)),
                    t("kami_claims.alert.runway.body", money(net)), t("kami_claims.alert.open.budget"), page = "budget")
            }
            val wageBudget = (sum.income * s.jobShare).toLong()
            if (sum.jobs > wageBudget && sum.jobs > 0) out += alert("wages", "WARNING", t("kami_claims.alert.wages.title"),
                t("kami_claims.alert.wages.body", money(sum.jobs), "${(s.jobShare * 100).toInt()}", money(wageBudget)), t("kami_claims.alert.open.jobs"), page = "jobs")
            if (claims.isNotEmpty() && claims.none { it.capital }) out += alert("capital", "DANGER", t("kami_claims.alert.capital.title"),
                t("kami_claims.alert.capital.body"), t("kami_claims.alert.open.map"), page = "map")
            val lapsing = claims.count { it.owner != null && it.rentDebt > 0 }
            if (lapsing > 0) out += alert("plots", "INFO", t("kami_claims.alert.plots.title", lapsing.toString()), t("kami_claims.alert.plots.body", money(c.rentDebtLimit)),
                t("kami_claims.alert.open.plots"), page = "plots", focus = "moving")
        }
        if (!delegated) claims.filter { it.owner == me && it.rentDebt > 0 }.forEach { cl ->
            val locked = cl.state == Tenancy.MOVING_OUT
            out += alert(
                "myplot:${cl.x}:${cl.z}", if (locked) "DANGER" else "WARNING",
                t(if (locked) "kami_claims.alert.myplot.locked" else "kami_claims.alert.myplot.unpaid", cl.x.toString(), cl.z.toString()),
                t("kami_claims.alert.myplot.body", money(cl.rentDebt), money(c.rentDebtLimit)),
                t("kami_claims.alert.myplot.action"), page = "plots", focus = "${cl.x}:${cl.z}"
            )
        }
        if (!delegated && can(Cap.INVITE)) {
            val requests = c.requests.count { it.value > now() }
            if (requests > 0) out += alert("requests", "INFO", t("kami_claims.alert.requests.title", requests.toString()), t("kami_claims.alert.requests.body"),
                t("kami_claims.alert.review"), page = "citizens", focus = "requests")
        }
        if (!delegated && can(Cap.TRADE)) c.allianceOffers.filterValues { it > now() }.keys.mapNotNull { Realm.country(it) }.forEach { from ->
            out += alert("alliance:${from.id}", "INFO", t("kami_claims.alert.alliance.title", from.name), t("kami_claims.alert.alliance.body"),
                t("kami_claims.alert.review"), page = "relations", focus = "country:${from.name}")
        }
        if (!delegated && can(Cap.PROVINCE)) {
            c.provinceInvites.filterValues { it.until > now() }.forEach { (pid, offer) ->
                val parent = Realm.country(pid) ?: return@forEach
                out += alert(
                    "offer:$pid", "WARNING",
                    t(if (offer.answered) "kami_claims.alert.offer.answered" else "kami_claims.alert.offer.title", parent.name),
                    t("kami_claims.alert.offer.body"), t("kami_claims.alert.offer.action"), page = "provinces", focus = "offer:${parent.name}"
                )
            }
            c.provinceRequests.filterValues { it > now() }.keys.mapNotNull { Realm.country(it) }.forEach { child ->
                out += alert("request:${child.id}", "INFO", t("kami_claims.alert.province_request.title", child.name), t("kami_claims.alert.province_request.body"),
                    t("kami_claims.alert.review"), page = "provinces", focus = "request:${child.name}")
            }
            c.provinces.mapNotNull { Realm.country(it) }.forEach { child ->
                if (child.independenceRequested) out += alert("independence:${child.id}", "WARNING", t("kami_claims.alert.independence.title", child.name),
                    t("kami_claims.alert.independence.body"), t("kami_claims.alert.independence.action"), page = "provinces", focus = "province:${child.name}")
                if (child.provinceDebt > 0) out += alert("tributeDebt:${child.id}", "WARNING", t("kami_claims.alert.tribute_debt.title", child.name, child.provinceDebt.toString()),
                    t("kami_claims.alert.tribute_debt.body"), t("kami_claims.alert.review"), page = "provinces", focus = "province:${child.name}")
            }
            if (c.provinceDebt > 0) Realm.country(c.parent)?.let { parent ->
                out += alert("owedTribute", "WARNING", t("kami_claims.alert.owed_tribute.title", parent.name, c.provinceDebt.toString()),
                    t("kami_claims.alert.owed_tribute.body", parent.name), t("kami_claims.alert.open.budget"), page = "budget")
            }
        }
        if (!delegated) {
            val president = c.president()?.let { c.members[it] }
            val idle = president?.let { (now() - it.seen) / s.dayMillis } ?: 0
            if (president != null && idle >= s.successionDays - 7) out += alert("succession", "WARNING", t("kami_claims.alert.succession.title", days(idle)),
                t("kami_claims.alert.succession.body", days(s.successionDays)), page = "citizens")
            val quiet = (now() - c.lastActive) / s.dayMillis
            if (quiet >= s.inactiveDays - 7) out += alert("inactive", "DANGER", t("kami_claims.alert.inactive.title"),
                t("kami_claims.alert.inactive.body", days(quiet), days(s.inactiveDays)))
        }
        return out.sortedBy { listOf("DANGER", "WARNING", "INFO").indexOf(it.severity) }
    }
}
