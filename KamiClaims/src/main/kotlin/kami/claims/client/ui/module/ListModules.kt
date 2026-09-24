package kami.claims.client.ui.module

import kami.claims.Access
import kami.claims.Action
import kami.claims.Rank
import kami.claims.client.ClientClaims
import kami.claims.client.ClientHooks
import kami.claims.client.ui.*
import kami.claims.net.ClaimLine
import kami.claims.net.Mem
import kami.claims.net.TypeLine
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.max

private fun Module.me() = Minecraft.getInstance().player?.name?.string ?: ""

class ClaimsModule(s: ClaimsScreen) : Module(s) {
    private var search = ""
    private var type = ""
    private val list = ScrollList<ClaimLine>(0, 0, 0, 0) { g, c, x, y, w, _ ->
        g.fill(x, y + 3, x + 6, y + 9, Ui.typeColor(c.type))
        g.drawString(s.text, "${c.x}, ${c.z}", x + 10, y + 2, Ui.TEXT, false)
        g.drawString(s.text, c.type, x + 70, y + 2, Ui.typeColor(c.type), false)
        val flags = listOfNotNull(if (c.capital) "capital" else null, if (c.free) "free" else null, if (c.debt > 0) "debt ${c.debt}" else null, c.owner.ifEmpty { null }, if (c.tax >= 0) "${c.tax}/d" else null)
        g.drawString(s.text, Ui.fit(flags.joinToString("  "), w - 140), x + 140, y + 2, if (c.debt > 0) Ui.BAD else Ui.DIM, false)
    }

    override fun snapshot() {
        list.items = (s.info?.claimList ?: emptyList()).filter {
            (type.isEmpty() || it.type == type) && (search.isBlank() || "${it.x},${it.z} ${it.owner} ${it.type}".contains(search, true))
        }
    }

    override fun init() {
        val x = s.ax
        val y = s.ay
        val rx = x + s.aw - 138
        s.edit(x, y, 130, search, 20, "Search x,z / owner") { search = it; snapshot() }
        val types = listOf("") + ClientClaims.types
        s.btn(if (type.isEmpty()) "All types" else type, x + 136, y, 96, tip = "Click to cycle the type filter") {
            type = types[(types.indexOf(type) + 1) % types.size]
            snapshot()
            s.show(ClaimsScreen.Tab.CLAIMS)
        }
        list.x = x; list.y = y + 22; list.w = s.aw - 146; list.h = s.ah - 22
        snapshot()
        val claim = s.can("claim")
        val sel = { list.current }
        s.btn("Show on map", rx, y + 22, 132) { sel()?.let { s.openMap(it.x, it.z) } }
        s.btn("Set type: ${type.ifEmpty { ClientClaims.types.firstOrNull() ?: "" }}", rx, y + 44, 132, claim, "Uses the type filter above") {
            sel()?.let { c -> s.act("type", type.ifEmpty { ClientClaims.types.first() }, c.x.toString(), c.z.toString()) }
        }
        s.btn("Unclaim", rx, y + 66, 132, claim) { sel()?.let { c -> s.act("unclaim", c.x.toString(), c.z.toString()) } }
        s.btn("Make capital", rx, y + 88, 132, s.can("capital")) { sel()?.let { c -> s.act("capital", c.x.toString(), c.z.toString()) } }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        list.draw(g, mx, my)
        val i = s.info ?: return
        val rx = s.ax + s.aw - 138
        s.label(g, "${list.items.size} of ${i.claimList.size} chunks", rx, s.ay + 116, Ui.DIM)
        i.breakdown.take(5).forEachIndexed { n, b -> s.label(g, "${b.type} x${b.count}", rx, s.ay + 132 + n * 11, Ui.typeColor(b.type)) }
    }

    override fun click(mx: Double, my: Double, button: Int) = list.click(mx, my)
    override fun scroll(mx: Double, my: Double, dy: Double) = list.scroll(mx, my, dy)
}

class PlotsModule(s: ClaimsScreen) : Module(s) {
    private var mode = 0
    private var who = ""
    private var role = "household"
    private var last: ClaimLine? = null
    private val list = ScrollList<ClaimLine>(0, 0, 0, 0) { g, c, x, y, w, _ ->
        g.drawString(s.text, "${c.x}, ${c.z}", x, y + 2, Ui.TEXT, false)
        g.drawString(s.text, Ui.fit(c.owner.ifEmpty { "free" }, 90), x + 62, y + 2, if (c.owner.isEmpty()) Ui.GOOD else Ui.GOLD, false)
        if (c.tax >= 0) g.drawString(s.text, "${c.tax}/d${if (c.lapse > 0) "  late ${c.lapse}" else ""}${if (c.roles > 0) "  ${c.roles} roles" else ""}", x + 158, y + 2, if (c.lapse > 0) Ui.BAD else Ui.DIM, false)
    }

    override fun snapshot() {
        val plots = (s.info?.claimList ?: emptyList()).filter { it.type == "residential" }
        list.items = when (mode) {
            0 -> plots.filter { it.owner == me() }
            1 -> plots.filter { it.owner.isEmpty() }
            else -> plots.filter { it.owner.isNotEmpty() }
        }
    }

    override fun init() {
        val x = s.ax
        val y = s.ay
        val staff = s.info?.details == true
        listOf("Mine" to 0, "Free" to 1, "All" to 2).forEach { (label, m) ->
            s.btn(label, x + m * 62, y, 60, mode != m && (m < 2 || staff), if (m == 2 && !staff) "Requires ${s.snap.caps["details"]} or higher" else null) { mode = m; s.show(ClaimsScreen.Tab.PLOTS) }
        }
        list.x = x; list.y = y + 22; list.w = s.aw - 156; list.h = s.ah - 22
        snapshot()
        val rx = x + s.aw - 150
        val sel = { list.current }
        s.btn("Show on map", rx, y, 150) { sel()?.let { s.openMap(it.x, it.z) } }
        when (mode) {
            0 -> {
                s.btn("Release plot", rx, y + 22, 150) { sel()?.let { c -> s.act("plot_release", c.x.toString(), c.z.toString()) } }
                s.edit(rx, y + 84, 150, who, 16, "Player name") { who = it }
                s.btn("Role: $role", rx, y + 104, 150, tip = "Household builds, allied only interacts, banished is locked out") {
                    val roles = listOf("household", "allied", "banished", "owner")
                    role = roles[(roles.indexOf(role) + 1) % roles.size]
                    s.show(ClaimsScreen.Tab.PLOTS)
                }
                s.btn("Trust", rx, y + 124, 72) { sel()?.let { c -> s.act("plot_trust", who, role, c.x.toString(), c.z.toString()) } }
                s.btn("Untrust", rx + 78, y + 124, 72) { sel()?.let { c -> s.act("plot_untrust", who, c.x.toString(), c.z.toString()) } }
            }
            1 -> s.btn("Claim this plot", rx, y + 22, 150, s.can("plot"), "You pay the plot tax every day") { sel()?.let { c -> s.act("plot_claim", c.x.toString(), c.z.toString()) } }
            else -> {
                val claim = s.can("claim")
                s.btn("Evict owner", rx, y + 22, 150, claim) { sel()?.let { c -> s.act("plot_evict", c.x.toString(), c.z.toString()) } }
                s.btn("Tax -1", rx, y + 44, 72, s.can("tax")) { sel()?.let { c -> s.act("plot_tax", max(0, c.tax - 1).toString(), c.x.toString(), c.z.toString()) } }
                s.btn("Tax +1", rx + 78, y + 44, 72, s.can("tax")) { sel()?.let { c -> s.act("plot_tax", (c.tax + 1).toString(), c.x.toString(), c.z.toString()) } }
            }
        }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        list.draw(g, mx, my)
        val i = s.info ?: return
        val rx = s.ax + s.aw - 150
        s.label(g, "You own ${i.plots}/${s.snap.maxPlots} plots", rx, s.ay + 44 + if (mode == 0) 0 else 22, Ui.DIM)
        val d = s.snap.detail
        val c = list.current
        if (mode == 0 && c != null && d != null && d.x == c.x && d.z == c.z) {
            d.roles.take(3).forEachIndexed { n, r -> s.label(g, Ui.fit(r, 148), rx, s.ay + 58 + n * 8, Ui.DIM) }
        }
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        val hit = list.click(mx, my)
        val c = list.current
        if (hit && c != null && c !== last) {
            last = c
            ClientHooks.request("chunk", c.x.toString(), c.z.toString())
        }
        return hit
    }

    override fun scroll(mx: Double, my: Double, dy: Double) = list.scroll(mx, my, dy)
}

class MembersModule(s: ClaimsScreen) : Module(s) {
    private var mode = 0
    private var invite = ""
    private var search = ""
    private val members = ScrollList<Mem>(0, 0, 0, 0, 14) { g, m, x, y, w, _ ->
        g.drawString(s.text, Ui.fit(m.name, 90), x, y + 3, Ui.TEXT, false)
        g.drawString(s.text, m.rank, x + 94, y + 3, Ui.rankColor(m.rank), false)
        if (m.job.isNotEmpty()) {
            val quota = s.info?.jobList?.firstOrNull { it.name == m.job }?.quota ?: 1
            g.drawString(s.text, m.job, x + 150, y + 3, Ui.DIM, false)
            Ui.bar(g, x + 200, y + 5, 40, 4, m.progress.toDouble() / max(1, quota), if (m.progress >= quota) Ui.GOOD else Ui.ACCENT)
        }
        g.drawString(s.text, Ui.ago(m.seen), x + w - 44, y + 3, Ui.DIM, false)
    }
    private val others = ScrollList<Mem>(0, 0, 0, 0, 14) { g, m, x, y, _, _ ->
        g.drawString(s.text, Ui.fit(m.name, 120), x, y + 3, Ui.TEXT, false)
        if (m.rank.isNotEmpty()) g.drawString(s.text, m.rank, x + 130, y + 3, Ui.rankColor(m.rank), false)
    }

    private val list get() = if (mode == 0) members else others

    override fun snapshot() {
        val i = s.info
        members.items = (i?.members ?: emptyList()).filter { search.isBlank() || it.name.contains(search, true) }
        others.items = if (mode == 1) i?.requests ?: emptyList() else i?.relations ?: emptyList()
    }

    override fun init() {
        val i = s.info ?: return
        val x = s.ax
        val y = s.ay
        val staff = s.can("invite")
        listOf("Members" to 0, "Requests" to 1, "Allies / banished" to 2).forEach { (label, m) ->
            s.btn(if (m == 1) "Requests ${s.info?.requests?.size ?: 0}" else label, x + m * 84, y, if (m == 2) 110 else 82, mode != m && (m == 0 || staff)) { mode = m; s.show(ClaimsScreen.Tab.MEMBERS) }
        }
        val lw = s.aw - 150
        list.x = x; list.y = y + 42; list.w = lw; list.h = s.ah - 42 - 22
        if (mode == 0) s.edit(x, y + 20, lw, search, 16, "Search members") { search = it; snapshot() }
        snapshot()
        val rx = x + s.aw - 144
        val target = { list.current }
        when (mode) {
            0 -> {
                val t = members.current
                val rank = s.can("rank")
                s.btn("Promote", rx, y + 42, 144, rank && t != null) { target()?.let { m -> s.act("rank", m.id, if (m.rank == "citizen") "officer" else "chancellor") } }
                s.btn("Demote", rx, y + 62, 144, rank && t != null) { target()?.let { m -> s.act("rank", m.id, if (m.rank == "chancellor") "officer" else "citizen") } }
                val jobs = listOf("") + s.snap.jobNames
                s.btn("Job: ${t?.job?.ifEmpty { "none" } ?: "-"}", rx, y + 82, 144, s.can("jobs") && t != null, "Click to cycle the job") {
                    target()?.let { m ->
                        val next = jobs[(jobs.indexOf(m.job) + 1) % jobs.size]
                        if (next.isEmpty()) s.act("job_unassign", m.id) else s.act("job_assign", m.id, next)
                    }
                }
                s.btn("Kick", rx, y + 102, 70, s.can("members") && t != null) { target()?.let { m -> s.act("kick", m.id) } }
                s.btn("Banish", rx + 74, y + 102, 70, s.can("members") && t != null) { target()?.let { m -> s.act("banish", m.id) } }
                s.btn("Make president", rx, y + 122, 144, s.rank == Rank.PRESIDENT && !i.delegated && t != null) { target()?.let { m -> s.act("president", m.id) } }
                s.edit(x, y + s.ah - 18, 130, invite, 16, "Invite player") { invite = it }
                s.btn("Invite", x + 134, y + s.ah - 18, 50, s.can("invite")) { s.act("invite", invite) }
            }
            1 -> {
                s.btn("${Ui.CHECK} Approve", rx, y + 42, 144, s.can("invite") && others.current != null) { target()?.let { m -> s.act("approve", m.id) } }
                s.btn("${Ui.CROSS} Deny", rx, y + 62, 144, s.can("invite") && others.current != null) { target()?.let { m -> s.act("deny", m.id) } }
            }
            else -> {
                s.btn("Remove relation", rx, y + 42, 144, s.can("members") && others.current != null, "Un-ally or un-banish") { target()?.let { m -> s.act("clear", m.id) } }
                s.edit(x, y + s.ah - 18, 130, invite, 16, "Player name") { invite = it }
                s.btn("Ally", x + 134, y + s.ah - 18, 50, s.can("members")) { s.act("ally", invite) }
                s.btn("Banish", x + 188, y + s.ah - 18, 56, s.can("members")) { s.act("banish", invite) }
            }
        }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        val header = when (mode) { 0 -> "Name / rank / job / progress / last seen"; 1 -> "People asking to join"; else -> "Allies may interact, banished are locked out" }
        val count = if (mode == 0) "${members.items.size} players" else ""
        val countW = if (count.isEmpty()) 0 else s.text.width(count) + 8
        s.label(g, Ui.fit(header, list.w - countW), s.ax, s.ay + 28, Ui.DIM)
        if (count.isNotEmpty()) s.label(g, count, s.ax + list.w - s.text.width(count), s.ay + 28, Ui.DIM)
        list.draw(g, mx, my)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        val before = list.selected
        val hit = list.click(mx, my)
        if (hit && before != list.selected) s.show(ClaimsScreen.Tab.MEMBERS)
        return hit
    }

    override fun scroll(mx: Double, my: Double, dy: Double) = list.scroll(mx, my, dy)
}

class JobsModule(s: ClaimsScreen) : Module(s) {
    private val rowH = 44
    private val scroll = RowScroll(rowH)
    private val contentH get() = s.ah - 26
    private val labelW get() = (s.aw * 0.32).toInt().coerceIn(90, 150)
    private fun colW() = (s.aw - labelW) / 3
    private fun colX(n: Int) = s.ax + labelW + n * colW()

    override fun init() {
        val i = s.info ?: return
        scroll.clamp(i.jobList.size, contentH)
        val staff = s.can("jobs")
        for (row in scroll.range(i.jobList.size, contentH)) {
            val j = i.jobList[row]
            val y = s.ay + 16 + (row - scroll.offset) * rowH
            listOf(Triple("pay", j.pay, 1), Triple("quota", j.quota, 8), Triple("period", j.period, 1)).forEachIndexed { n, (field, value, step) ->
                s.stepper(colX(n), y, staff, dec = { s.act("job_set", j.name, field, max(0, value - step).toString()) }, inc = { s.act("job_set", j.name, field, (value + step).toString()) })
            }
        }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        val i = s.info ?: return
        listOf("Pay/day" to 0, "Quota" to 1, "Period" to 2).forEach { (t, n) -> s.label(g, Ui.fit(t, colW() - 4), colX(n) + 4, s.ay + 2, Ui.DIM) }
        g.enableScissor(s.ax, s.ay + 14, s.ax + s.aw, s.ay + 14 + contentH)
        for (row in scroll.range(i.jobList.size, contentH)) {
            val j = i.jobList[row]
            val y = s.ay + 16 + (row - scroll.offset) * rowH
            g.fill(s.ax, y - 2, s.ax + s.aw - 6, y + 40, Ui.PANEL)
            g.fill(s.ax, y - 2, s.ax + 3, y + 40, Ui.typeColor(j.type))
            s.label(g, Ui.fit(j.name, labelW - 12), s.ax + 8, y + 2, Ui.TEXT)
            s.label(g, Ui.fit(j.type, labelW - 12), s.ax + 8, y + 14, Ui.typeColor(j.type))
            listOf(j.pay, j.quota, j.period).forEachIndexed { n, v -> s.label(g, v.toString(), colX(n) + s.stepperWidth + 6, y + 4, Ui.GOLD) }
            val workers = i.members.filter { it.job == j.name }
            val text = if (workers.isEmpty()) "no workers" else workers.joinToString("  ") { "${it.name} ${it.progress}/${j.quota}" }
            s.label(g, Ui.fit(text, s.aw - labelW - 4), s.ax + labelW, y + 24, if (workers.isEmpty()) Ui.DIM else Ui.TEXT)
        }
        g.disableScissor()
        scroll.bar(g, s.ax + s.aw - 4, s.ay + 14, contentH, i.jobList.size)
        s.label(g, Ui.fit("Pay comes from the treasury and is capped at ${s.snap.jobShare}% of the daily income.", s.aw), s.ax, s.ay + s.ah - 10, Ui.DIM)
    }

    override fun scroll(mx: Double, my: Double, dy: Double): Boolean {
        val i = s.info ?: return false
        val hit = scroll.scroll(s.ax, s.ay + 14, s.aw, contentH, mx, my, dy, i.jobList.size)
        if (hit) s.show(ClaimsScreen.Tab.JOBS)
        return hit
    }
}

class RanksModule(s: ClaimsScreen) : Module(s) {
    private val text = mapOf(
        "claim" to "Claim, unclaim and retype chunks", "capital" to "Move the capital", "tax" to "Plot taxes and lapse timers",
        "rules" to "Chunk rules and country colour", "withdraw" to "Withdraw from the treasury", "invite" to "Invite, approve and deny",
        "members" to "Kick, banish and ally", "rank" to "Change member ranks", "jobs" to "Manage jobs and workers",
        "plot" to "Claim residential plots", "details" to "See debt, taxes and free chunks"
    )
    private val ranks = listOf("citizen", "officer", "chancellor", "president")

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        val i = s.info ?: return
        val labelW = s.aw * 2 / 5
        val x0 = s.ax + labelW
        val colW = (s.aw - labelW) / ranks.size
        ranks.forEachIndexed { n, r ->
            s.label(g, Ui.fit(r, colW - 4), x0 + n * colW, s.ay + 2, Ui.rankColor(r))
            s.label(g, "${i.members.count { it.rank == r }}", x0 + n * colW, s.ay + 13, Ui.DIM)
        }
        text.entries.forEachIndexed { row, (cap, desc) ->
            val y = s.ay + 30 + row * 14
            g.fill(s.ax, y - 2, s.ax + s.aw, y + 11, if (row % 2 == 0) Ui.PANEL else Ui.ROW)
            s.label(g, Ui.fit(desc, labelW - 8), s.ax + 4, y, Ui.TEXT)
            val min = Rank.valueOf((s.snap.caps[cap] ?: "president").uppercase())
            ranks.forEachIndexed { n, r ->
                val ok = Rank.valueOf(r.uppercase()) >= min
                s.label(g, if (ok) Ui.CHECK else Ui.MINUS, x0 + n * colW, y, if (ok) Ui.GOOD else Ui.DIM)
            }
        }
        s.label(g, "You are ${i.rank}. Minimum ranks come from the server config.", s.ax, s.ay + s.ah - 10, Ui.DIM)
    }
}

class RulesModule(s: ClaimsScreen) : Module(s) {
    private val labelW get() = (s.aw * 0.3).toInt().coerceIn(90, 150)
    private val flags = listOf(
        Triple("machines", "Whether machines such as drills may work here") { t: TypeLine -> t.machines },
        Triple("fire", "Whether fire may spread here") { t: TypeLine -> t.fire },
        Triple("fluid", "Whether fluids may flow here") { t: TypeLine -> t.fluid }
    )
    private val cols get() = Action.values().size + flags.size
    private fun colW() = (s.aw - labelW) / cols
    private fun colX(col: Int) = s.ax + labelW + col * colW()

    override fun init() {
        val staff = s.can("rules")
        val cw = colW() - 2
        s.snap.types.forEachIndexed { row, t ->
            val y = s.ay + 16 + row * 18
            Action.values().forEachIndexed { col, a ->
                s.btn(t.access[col], colX(col), y, cw, staff, "Click to cycle who may ${a.name.lowercase()}") {
                    val all = Access.values()
                    val next = all[(all.indexOfFirst { it.name.equals(t.access[col], true) } + 1) % all.size]
                    s.act("rule", t.name, a.name.lowercase(), next.name.lowercase())
                }
            }
            flags.forEachIndexed { n, (field, tip, get) ->
                val on = get(t)
                s.btn(if (on) "yes" else "no", colX(Action.values().size + n), y, cw, staff, tip) { s.act("rule", t.name, field, (!on).toString()) }
            }
        }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        Action.values().forEachIndexed { col, a -> s.label(g, Ui.fit(a.name.lowercase(), colW() - 4), colX(col) + 4, s.ay + 2, Ui.DIM) }
        flags.forEachIndexed { n, (field, _, _) -> s.label(g, Ui.fit(field, colW() - 4), colX(Action.values().size + n) + 4, s.ay + 2, Ui.DIM) }
        s.snap.types.forEachIndexed { row, t ->
            val y = s.ay + 20 + row * 18
            g.fill(s.ax, y - 3, s.ax + 6, y + 6, Ui.typeColor(t.name))
            s.label(g, Ui.fit("${t.name} ${t.price}${if (t.period > 1) "/${t.period}d" else ""}", labelW - 12), s.ax + 10, y - 1, Ui.TEXT)
        }
    }
}
