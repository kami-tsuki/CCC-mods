package kami.claims.service

import kami.claims.Cap
import kami.claims.Config
import kami.claims.Country
import kami.claims.Rank
import kami.claims.Realm
import kami.claims.now
import kami.claims.today
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer

@Serializable
class AlertLine(
    val id: String, val severity: String, val title: String, val body: String,
    val action: String = "", val act: String = "", val args: List<String> = emptyList(),
    val page: String = "", val focus: String = ""
)

@Serializable
class StepLine(val id: String, val label: String, val hint: String, val done: Boolean, val page: String)

object Alerts {
    private val s get() = Config.s
    private const val DAY = 86_400_000L

    private fun plural(n: Int, word: String) = "$n ${if (n == 1) word else word + "s"}"

    fun nextBill(c: Country): Long {
        val tomorrow = today() + 1
        return Realm.claims(c.id).filter { !it.free && tomorrow > it.since && (tomorrow - it.since) % Realm.period(it) == 0L }
            .sumOf { Realm.price(it).toLong() * (1 + it.debt) }
    }

    fun of(p: ServerPlayer, c: Country?, delegated: Boolean): List<AlertLine> {
        val me = p.stringUUID
        val out = ArrayList<AlertLine>()
        if (c == null) {
            Realm.data.countries.values.filter { (it.invites[me] ?: 0) > now() }.forEach {
                out += AlertLine("invite:${it.id}", "INFO", "${it.name} invites you", "Join to share their land, protection and jobs.", "Join ${it.name}", "accept", listOf(it.name), "welcome")
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
                out += AlertLine(
                    "debt", "DANGER", "${plural(debt.size, "chunk")} in debt",
                    "Unpaid chunks are lost after ${s.maxDebt} days. The worst is at $worst/${s.maxDebt}${if (worst >= s.maxDebt - 1) " and is lost at the next bill" else ""}.",
                    "Deposit $shortfall", "deposit", listOf(shortfall.toString()), "chunks", "debt"
                )
            } else {
                val bill = nextBill(c)
                if (bill > c.treasury) out += AlertLine(
                    "bill", "WARNING", "The treasury can't pay the next bill",
                    "The next bill is $bill spur but the treasury holds ${c.treasury}. Unpaid chunks go into debt.",
                    "Deposit ${bill - c.treasury}", "deposit", listOf((bill - c.treasury).toString()), "budget"
                )
            }
            val net = sum.upkeep + sum.jobs - sum.income
            if (net > 0 && claims.any { !it.free }) {
                val days = c.treasury / net
                if (days < 14) out += AlertLine(
                    "runway", if (days < 3) "DANGER" else "WARNING", "Money runs out in $days ${if (days == 1L) "day" else "days"}",
                    "You spend $net spur more per day than you earn. Raise plot tax, cut wages or release land.", "Open budget", page = "budget"
                )
            }
            val wageBudget = (sum.income * s.jobShare).toLong()
            if (sum.jobs > wageBudget && sum.jobs > 0) out += AlertLine(
                "wages", "WARNING", "Wages exceed the wage budget",
                "Jobs cost ${sum.jobs} spur a day, but only ${(s.jobShare * 100).toInt()}% of income ($wageBudget) may go to wages. Some workers won't be paid.", "Open jobs", page = "jobs"
            )
            if (claims.isNotEmpty() && claims.none { it.capital }) out += AlertLine("capital", "DANGER", "Your country has no capital", "Pick a chunk on the map and make it the capital.", "Open map", page = "map")
            val lapsing = claims.count { it.owner != null && it.lapse > 0 }
            if (lapsing > 0) out += AlertLine("plots", "INFO", "${plural(lapsing, "plot")} behind on tax", "Owners are locked out after ${c.shutdown} unpaid days and lose the plot ${c.release} days later.", "Open plots", page = "plots", focus = "lapse")
        }
        if (!delegated) claims.filter { it.owner == me && it.lapse > 0 }.forEach { cl ->
            val locked = cl.lapse >= c.shutdown
            out += AlertLine(
                "myplot:${cl.x}:${cl.z}", if (locked) "DANGER" else "WARNING",
                if (locked) "Your plot at ${cl.x}, ${cl.z} is locked" else "Your plot tax at ${cl.x}, ${cl.z} is unpaid",
                "Unpaid for ${cl.lapse} ${if (cl.lapse == 1) "day" else "days"}. You lose it after ${c.shutdown + c.release} days. Keep coins in your bank so the tax can be paid.",
                "Show plot", page = "plots", focus = "${cl.x}:${cl.z}"
            )
        }
        if (!delegated && can(Cap.INVITE)) {
            val requests = c.requests.count { it.value > now() }
            if (requests > 0) out += AlertLine("requests", "INFO", "${plural(requests, "player")} want to join", "Approve or deny them in Citizens.", "Review", page = "citizens", focus = "requests")
        }
        if (!delegated && can(Cap.PROVINCE)) {
            c.provinceInvites.filterValues { it.until > now() }.forEach { (pid, offer) ->
                val parent = Realm.country(pid) ?: return@forEach
                out += AlertLine(
                    "offer:$pid", "WARNING",
                    if (offer.answered) "${parent.name} accepted your province request" else "${parent.name} wants you as their province",
                    "Read the terms carefully: a province gives up authority over its land and can't leave on its own.", "Review terms", page = "provinces", focus = "offer:${parent.name}"
                )
            }
            c.provinceRequests.filterValues { it > now() }.keys.mapNotNull { Realm.country(it) }.forEach { child ->
                out += AlertLine("request:${child.id}", "INFO", "${child.name} asks to become your province", "Set the tribute and send them your terms.", "Review", page = "provinces", focus = "request:${child.name}")
            }
            c.provinces.mapNotNull { Realm.country(it) }.forEach { child ->
                if (child.independenceRequested) out += AlertLine("independence:${child.id}", "WARNING", "${child.name} asks for independence", "Grant it to let them go, or decline to keep them as your province.", "Decide", page = "provinces", focus = "province:${child.name}")
                if (child.provinceDebt > 0) out += AlertLine("tributeDebt:${child.id}", "WARNING", "${child.name} missed tribute ${child.provinceDebt} times", "Forgive the debt or release them.", "Review", page = "provinces", focus = "province:${child.name}")
            }
            if (c.provinceDebt > 0) Realm.country(c.parent)?.let { parent ->
                out += AlertLine("owedTribute", "WARNING", "You missed tribute to ${parent.name} ${c.provinceDebt} times", "Deposit coins so the next tribute can be paid. ${parent.name} decides what happens to unpaid tribute.", "Open budget", page = "budget")
            }
        }
        if (!delegated) {
            val president = c.president()?.let { c.members[it] }
            val idle = president?.let { (now() - it.seen) / DAY } ?: 0
            if (president != null && idle >= s.successionDays - 7) out += AlertLine("succession", "WARNING", "The president has been away $idle days", "After ${s.successionDays} days the presidency passes to the most senior member.", page = "citizens")
            val quiet = (now() - c.lastActive) / DAY
            if (quiet >= s.inactiveDays - 7) out += AlertLine("inactive", "DANGER", "Free chunks vanish soon", "No member was online for $quiet days. After ${s.inactiveDays} days the free chunks are released.")
        }
        return out.sortedBy { listOf("DANGER", "WARNING", "INFO").indexOf(it.severity) }
    }

    fun steps(c: Country?, canLead: Boolean): List<StepLine> {
        if (c == null || !canLead) return emptyList()
        val claims = Realm.claims(c.id)
        val upkeep = Upkeep.summary(c).upkeep
        return listOf(
            StepLine("claim", "Claim land around your capital", "Your first ${Realm.freeAllowed(c)} chunks are free.", claims.size > 1, "map"),
            StepLine("residential", "Create residential land", "Citizens rent residential plots and pay tax into the treasury.", claims.any { it.type == "residential" }, "map"),
            StepLine("treasury", "Fill the treasury for a week", "Keep at least 7 days of upkeep so land never goes into debt.", upkeep == 0L || c.treasury >= upkeep * 7, "budget"),
            StepLine("invite", "Invite a citizen", "Every ${s.freeBonusMembers} members add a free chunk.", c.members.size > 1, "citizens"),
            StepLine("job", "Give someone a job", "Workers get paid for mining, farming and forestry in your land.", c.members.values.any { it.job != null }, "jobs"),
            StepLine("identity", "Pick a colour and flag", "Your colour shows on every map.", c.color != 0, "identity")
        )
    }
}
