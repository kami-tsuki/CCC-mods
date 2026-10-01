package kami.claims.client.app.pages

import kami.claims.client.rankOf
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.Rank
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.capacityLine
import kami.claims.research.Capacity
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.net.JobLine
import kami.claims.net.Info
import kami.claims.net.Mem
import kami.claims.client.store.ClientResearch
import kami.libs.ui.core.Stack
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import kotlin.math.max

class CitizensPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.citizens")
    override val help get() = listOf(
        Callout("citizens:tabs", tr("kami_claims.citizens.help.tabs"), tr("kami_claims.citizens.help.tabs.desc")),
        Callout("citizens:table", tr("kami_libs.common.members"), tr("kami_claims.citizens.help.table.desc")),
        Callout("citizens:profile", tr("kami_claims.citizens.help.profile"), tr("kami_claims.citizens.help.profile.desc"))
    )
    private var tab = 0
    private var details = false
    private val table = TableState<Mem>()
    private val search = TextState()
    private val invite = TextState()
    private val jobText = HashMap<List<String>, String>()
    private var jobLocale: Any? = null
    private val plotLimits = HashMap<String, NumberState>()

    override fun opened(route: Route) {
        jobText.clear()
        if (route.focus == "requests") tab = 1
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        if (jobLocale !== Format.locale) { jobText.clear(); jobLocale = Format.locale }
        val tabs = r.top(CONTROL_H)
        ui.anchor("citizens:tabs", tabs)
        val staff = lock("invite")
        ui.subTabs(tabs, listOf(
            TabItem(tr("kami_libs.common.members"), Icons.PEOPLE, info.members.size, Severity.NEUTRAL),
            TabItem(tr("kami_claims.citizens.tab.requests"), Icons.INVITE, info.requests.size, Severity.INFO, staff),
            TabItem(tr("kami_claims.citizens.tab.sent"), Icons.SCROLL, info.invitesSent.size, Severity.NEUTRAL, staff)
        ), tab, "cit-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        when (tab) {
            0 -> members(ui, body)
            1 -> requests(ui, body)
            else -> sent(ui, body)
        }
    }

    private fun members(ui: Ui, r: Rect) {
        val info = info ?: return
        val profileW = if (app.compact) 0 else (r.w * 0.42).toInt().coerceIn(170, 240)
        val left = r.dropRight(profileW, if (profileW > 0) 8 else 0)
        var rows: List<Mem> = emptyList()
        val tableRect = ui.filterBar(left, "citizens:filters") { barRect ->
            val bar = Row(barRect, 6)
            ui.searchField(bar.take(140), search, tr("kami_claims.citizens.search"), key = "mem-search")
            val inviteLabel = tr("kami_libs.common.invite")
            val invited = ui.edgeButton(bar, inviteLabel, Icons.INVITE, ButtonStyle.PRIMARY, can("invite") && invite.text.length >= 3, lock("invite") ?: tr("kami_claims.field.player.disabled"), pending = pending("invite"), key = "invite-go")
            ui.textField(bar.takeFromRight(120), invite, tr("kami_claims.field.player"), Icons.PERSON, maxLength = 16, key = "invite-name")
            if (invited) {
                act("invite", invite.text, key = "invite")
                invite.set("")
            }
            rows = ui.filtered("citizens-filtered", listOf(search.text, info.members)) { info.members.filter { search.text.isBlank() || it.name.contains(search.text, true) } }
        }
        ui.anchor("citizens:table", tableRect)
        ui.table(tableRect, listOf(
            Column<Mem>(tr("kami_libs.common.name"), -1, sort = compareBy { it.name.lowercase() }) { _, c, m ->
                avatar(m.id, c.x, c.y + 1, 12, m.online)
                Draw.text(g, Draw.fit(m.name, c.w - 16), c.x + 16, c.y + 3, Palette.text)
            },
            Column<Mem>(tr("kami_libs.common.rank"), 84, sort = compareBy { Vocabulary.rankOrder.indexOf(it.rank) }) { _, c, m ->
                val look = Vocabulary.rank(m.rank)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                Draw.text(g, Draw.fit(look.label, c.right - x), x, c.y + 3, look.color)
            },
            Column<Mem>(tr("kami_libs.common.job"), 100, sort = compareBy { it.jobs.size }) { _, c, m ->
                if (m.jobs.isEmpty()) Draw.text(g, "-", c.x, c.y + 3, Palette.textMuted)
                else Draw.text(g, Draw.fit(jobText.getOrPut(m.jobs) { m.jobs.joinToString(", ") { Vocabulary.job(it) } }, c.w), c.x, c.y + 3, Palette.textSecondary)
            },
            Column<Mem>(tr("kami_claims.citizens.col.seen"), 60, Align.RIGHT, compareBy { -it.seen }) { _, c, m -> Draw.textRight(g, if (m.online) tr("kami_libs.common.online") else Format.ago(m.seen), c.right, c.y + 3, if (m.online) Palette.success else Palette.textMuted) }
        ), rows, table, { it.id })
        if (profileW > 0) profile(ui, r.right(profileW), rows.firstOrNull { it.id in table.selected } ?: info.members.firstOrNull { it.id == snap.me })
    }

    private fun profile(ui: Ui, r: Rect, m: Mem?) {
        ui.anchor("citizens:profile", r)
        if (m == null) return
        val info = info ?: return
        val f = ui.sidePanel(r, 4, 8)
        val head = f.take(26)
        header(ui, m, head)
        val rank = rankOf(m.rank) ?: Rank.CITIZEN
        ui.property(f.take(11), tr("kami_claims.nav.plots"), if (m.plotLimit > 0) tr("kami_libs.format.ratio", m.plotsHeld, m.plotLimit) else Format.number(m.plotsHeld))
        val me = m.id == snap.me
        val myRank = ClaimsStore.rank
        f.skip(2)
        val targets = listOf(Rank.CITIZEN, Rank.OFFICER, Rank.CHANCELLOR)
        val rankLock = lock("rank") ?: when {
            me -> tr("kami_claims.citizens.rank.disabled.self")
            rank >= myRank -> tr("kami_claims.citizens.rank.disabled.higher")
            else -> null
        }
        ui.segmented(f.take(CONTROL_H), targets.map { t ->
            Option(t, Vocabulary.rank(t.name).label, Vocabulary.rank(t.name).icon, lock = ClientLocks.rank(t, rank), disabledReason = rankLock ?: if (t >= myRank) tr("kami_claims.citizens.rank.disabled.above") else if (t == Rank.CHANCELLOR && info.members.any { it.rank == "chancellor" && it.id != m.id }) tr("kami_claims.error.one_chancellor") else null)
        }, rank, key = "rank:${m.id}")?.let { next -> rankDialog(m, rank, next) }
        f.skip(2)
        jobs(ui, f, m, info)
        f.skip(2)
        if (ui.disclosure(f.take(12), tr("kami_libs.common.details"), details, key = "cit-details")) details = !details
        if (details) {
            ui.property(f.take(11), tr("kami_claims.citizens.since"), if (m.since > 0) Format.ago(m.since) else "-")
            quotas(ui, f, m, info)
            plotLimit(ui, f, m, info)
        }
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        val memberLock = lock("members") ?: if (me) tr("kami_claims.citizens.member.disabled.self") else if (rank >= myRank) tr("kami_claims.citizens.member.disabled.higher") else null
        if (me && !info.delegated) {
            val presidentAlone = rank == Rank.PRESIDENT && info.members.size > 1
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.leave", info.name), Icons.BACK, ButtonStyle.DANGER, !presidentAlone, tr("kami_claims.error.transfer_first"), key = "leave")) {
                Dialogs.confirm(app, tr("kami_claims.citizens.leave.confirm.title", info.name), null, Icons.BACK, listOfNotNull(
                    if (info.members.size == 1) Consequence(tr("kami_claims.citizens.leave.last"), Severity.DANGER) else null,
                    Consequence(tr("kami_claims.citizens.leave.loses"), Severity.DANGER),
                    Consequence(tr("kami_claims.citizens.leave.after"))
                ), tr("kami_claims.citizens.leave.action"), "leave", emptyArray(), danger = true, hold = true, typed = if (info.members.size == 1) info.name else null)
            }
            y -= CONTROL_H + 3
            if (rank == Rank.PRESIDENT) {
                if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.disband"), Icons.DANGER, ButtonStyle.DANGER, key = "disband")) {
                    Dialogs.confirm(app, tr("kami_claims.common.disband_x", info.name), null, Icons.DANGER, listOf(
                        Consequence(tr("kami_claims.citizens.disband.land", trn("kami_claims.unit.chunk", info.chunks)), Severity.DANGER),
                        Consequence(tr("kami_claims.citizens.disband.members", trn("kami_claims.unit.member", info.members.size)), Severity.DANGER),
                        Consequence(tr("kami_claims.citizens.disband.treasury", Format.money(info.treasury)), Severity.DANGER),
                        Consequence(tr("kami_claims.citizens.disband.provinces"))
                    ), tr("kami_claims.citizens.disband.action"), "disband", arrayOf("confirm"), danger = true, hold = true, typed = info.name)
                }
            }
            return
        }
        val half = (rest.w - 4) / 2
        if (ui.button(Rect(rest.x, y, half, CONTROL_H), tr("kami_libs.common.remove"), Icons.BACK, ButtonStyle.DANGER, memberLock == null, memberLock, key = "kick:${m.id}")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.kick.confirm.title", m.name), info.name, Icons.BACK, listOf(
                Consequence(tr("kami_claims.citizens.kick.loses", m.name), Severity.DANGER),
                Consequence(tr("kami_claims.citizens.kick.rejoin"))
            ), tr("kami_libs.common.remove"), "kick", arrayOf(m.id), danger = true, hold = true)
        }
        if (ui.lockedButton(Rect(rest.x + half + 4, y, half, CONTROL_H), tr("kami_claims.citizens.banish"), ClientLocks.feature("banish"), Icons.BAN, ButtonStyle.DANGER, memberLock == null, memberLock, key = "ban:${m.id}")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.banish.confirm.title", m.name), info.name, Icons.BAN, listOf(
                Consequence(tr("kami_claims.citizens.banish.locked", m.name), Severity.DANGER),
                Consequence(tr("kami_claims.citizens.banish.lift"), Severity.WARNING)
            ), tr("kami_claims.citizens.banish"), "banish", arrayOf(m.id), danger = true, hold = true)
        }
        y -= CONTROL_H + 3
        if (myRank == Rank.PRESIDENT && !info.delegated) {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.president"), Icons.CROWN, key = "pres:${m.id}")) {
                Dialogs.confirm(app, tr("kami_claims.citizens.president.confirm.title", m.name), null, Icons.CROWN, listOf(
                    Consequence(tr("kami_claims.citizens.president.rights", m.name), Severity.DANGER),
                    Consequence(tr("kami_claims.citizens.president.you", Vocabulary.rank(if (rank >= Rank.OFFICER) rank.name else Rank.OFFICER.name).label), Severity.WARNING),
                    Consequence(tr("kami_claims.citizens.president.final"), Severity.DANGER)
                ), tr("kami_claims.citizens.president.action"), "president", arrayOf(m.id, "confirm"), danger = true, hold = true, typed = m.name)
            }
        }
    }

    private fun jobs(ui: Ui, f: Flow, m: Mem, info: Info) {
        ui.section(f.take(14), tr("kami_claims.cap.jobs"), "${m.jobs.size}/${info.jobSlots}")
        val jobLock = lock("jobs")
        if (m.jobs.isEmpty()) {
            Draw.text(ui.g, tr("kami_claims.citizens.no_job"), f.rest.x, f.rest.y + 2, Palette.textMuted)
            f.skip(14)
        } else {
            val chips = ui.filtered("job-chips:${m.id}", listOf(m.jobs, jobLock == null, Format.locale, info.jobList)) {
                m.jobs.map { name ->
                    val type = info.jobList.firstOrNull { it.name == name }?.type
                    ChipSpec(Vocabulary.job(name), Palette.textSecondary, if (jobLock == null) Icons.CROSS else type?.let { Vocabulary.type(it).icon })
                }
            }
            val rest = f.rest
            val stack = Stack(rest.x, rest.y, rest.w, 3)
            val clicked = ui.chipFlow(stack, chips, "job-chip:${m.id}")
            f.skip(stack.bottom - rest.y + 3)
            if (clicked != null && jobLock == null) act("job_remove", m.id, m.jobs[clicked], key = "job")
        }
        val full = m.jobs.size >= info.jobSlots
        val options = ui.filtered("job-options:${m.id}", listOf(m.jobs, Format.locale, info.jobList, ClientResearch.state)) {
            info.jobList.filter { it.name !in m.jobs }.map { j ->
                val lock = ClientLocks.claimType(j.type)
                val type = Vocabulary.type(j.type)
                Option(j.name, Vocabulary.job(j.name), type.icon, tr("kami_claims.citizens.job.desc", type.label, Format.money(j.pay.toLong()), Format.number(j.quota)), lock = lock,
                    disabledReason = lock?.let { tr("kami_claims.citizens.job.needs", Vocabulary.job(j.name), type.label, ClientResearch.unlockLevel(ClientLocks.CLAIM_TYPE + j.type) ?: 0) })
            }
        }
        val reason = jobLock ?: if (full) ClientLocks.raise(Capacity.JOB_SLOTS)?.reason ?: tr("kami_claims.citizens.job.full") else null
        ui.select(f.take(CONTROL_H), options, null, tr("kami_claims.citizens.job.add"), reason == null && options.isNotEmpty(), reason, key = "job-add:${m.id}")?.let { next ->
            act("job_add", m.id, next, key = "job")
        }
    }

    private fun quotas(ui: Ui, f: Flow, m: Mem, info: Info) {
        m.jobs.forEach { name ->
            val quota = info.jobList.firstOrNull { it.name == name }?.quota ?: return@forEach
            val done = m.progress[name] ?: 0
            val row = f.take(10)
            Draw.text(ui.g, Draw.fit(Vocabulary.job(name), 64), row.x, row.y + 1, Palette.textSecondary)
            ui.progress(Rect(row.x + 68, row.y + 3, row.w - 68 - 44, 4), done.toDouble() / max(1, quota), if (done >= quota) Palette.success else Palette.brass)
            Draw.textRight(ui.g, "$done/$quota", row.right, row.y + 1, Palette.textMuted)
        }
    }

    private fun plotLimit(ui: Ui, f: Flow, m: Mem, info: Info) {
        ui.fieldLabel(f.take(9), tr("kami_claims.citizens.plots.limit"))
        val editLock = lock("tax")
        val state = plotLimits.getOrPut(m.id) { NumberState(m.plotLimit.toLong()) }
        val fallback = info.rankPlots[m.rank] ?: 0
        val r = f.take(CONTROL_H)
        val label = tr("kami_claims.citizens.plots.reset")
        val bw = buttonWidth(label, Icons.UNDO)
        val ceiling = ClientResearch.state.max(Capacity.PLOTS).coerceAtLeast(1).toLong()
        if (!pending("plot_limit:${m.id}")) state.sync(m.plotLimit.toLong())
        ui.numberField(r.left(r.w - bw - 4), state, 0, ceiling, enabled = editLock == null, key = "plot-limit:${m.id}")?.let { act("plot_limit", m.id, it.toString(), key = "plot_limit:${m.id}") }
        if (ui.edgeButton(r, label, Icons.UNDO, enabled = editLock == null && m.plotLimit != fallback, disabledReason = editLock ?: tr("kami_claims.citizens.plots.reset.disabled"), key = "plot-reset:${m.id}")) {
            act("plot_limit", m.id, "-1", key = "plot_limit:${m.id}")
        }
    }

    private fun header(ui: Ui, m: Mem, r: Rect) {
        ui.avatar(m.id, r.x, r.y, 24, m.online)
        Draw.text(ui.g, Draw.fit(m.name, r.w - 30), r.x + 30, r.y + 2, TextStyle.HEADING)
        val look = Vocabulary.rank(m.rank)
        Draw.text(ui.g, Draw.fit(look.label + " · " + if (m.online) tr("kami_libs.common.online") else Format.ago(m.seen), r.w - 30), r.x + 30, r.y + 13, look.color)
    }

    private fun rankDialog(m: Mem, from: Rank, to: Rank) {
        if (from == to) return
        val caps = snap.caps
        fun rights(r: Rank) = caps.filter { (_, min) -> rankOf(min)?.let { r >= it } == true }.keys
        val gained = rights(to) - rights(from)
        val lost = rights(from) - rights(to)
        val up = to > from
        Dialogs.confirm(app, tr(if (up) "kami_claims.citizens.promote.confirm.title" else "kami_claims.citizens.demote.confirm.title", m.name, Vocabulary.rank(to.name).label), null, Vocabulary.rank(to.name).icon,
            gained.map { Consequence(tr("kami_claims.citizens.rank.gains", Vocabulary.caps[it]?.description ?: it), Severity.WARNING) } +
                lost.map { Consequence(tr("kami_claims.citizens.rank.loses", Vocabulary.caps[it]?.description ?: it)) } +
                listOf(Consequence(tr("kami_claims.citizens.rank.notified", m.name))),
            tr(if (up) "kami_claims.citizens.promote" else "kami_claims.citizens.demote"), "rank", arrayOf(m.id, to.name.lowercase()))
    }

    private fun requests(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.requests.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.citizens.requests.empty.title"), tr("kami_claims.citizens.requests.empty.desc"), Illustrations.CITIZENS)
            return
        }
        ui.scroll("requests", r, info.requests.size * 30) { area ->
            info.requests.forEachIndexed { i, m ->
                val row = Rect(area.x, area.y + i * 30, area.w, 26)
                Draw.sprite(ui.g, Sprites.CARD, row)
                ui.attention(row, app.isFocus("requests") && i == 0)
                ui.avatar(m.id, row.x + 5, row.y + 5, 16)
                val approve = tr("kami_claims.chat.approve")
                val deny = tr("kami_claims.chat.deny")
                val aw = buttonWidth(approve, Icons.CHECK)
                val dw = buttonWidth(deny)
                val textW = row.w - 26 - aw - dw - 16
                Draw.text(ui.g, Draw.fit(m.name, textW, TextStyle.HEADING), row.x + 26, row.y + 5, TextStyle.HEADING)
                val citizenship = snap.players.firstOrNull { it.id == m.id }?.citizenships?.firstOrNull { it.via.isEmpty() }
                Draw.text(ui.g, Draw.fit(citizenship?.let { tr("kami_claims.citizens.member_of", it.country) } ?: tr("kami_libs.common.no_country"), textW), row.x + 26, row.y + 15, Palette.textMuted)
                val staff = lock("invite")
                val actions = Row(Rect(row.x, row.y + 4, row.w - 6, CONTROL_H))
                if (ui.edgeButton(actions, deny, enabled = staff == null, disabledReason = staff, key = "deny:${m.id}")) act("deny", m.id, key = "deny:${m.id}")
                if (ui.edgeButton(actions, approve, Icons.CHECK, ButtonStyle.PRIMARY, staff == null, staff, pending = pending("approve:${m.id}"), key = "approve:${m.id}")) act("approve", m.id, key = "approve:${m.id}")
            }
        }
    }

    private fun sent(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.invitesSent.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.citizens.sent.empty.title"), tr("kami_claims.citizens.sent.empty.desc"), Illustrations.CITIZENS)
            return
        }
        ui.scroll("invites-sent", r, info.invitesSent.size * 20) { area ->
            info.invitesSent.forEachIndexed { i, m ->
                val row = Rect(area.x, area.y + i * 20, area.w, 18)
                ui.avatar(m.id, row.x, row.y + 3, 12)
                Draw.text(ui.g, m.name, row.x + 16, row.y + 5, Palette.text)
                Draw.textRight(ui.g, tr("kami_claims.citizens.sent.pending"), row.right, row.y + 5, Palette.textMuted)
            }
        }
    }
}

class JobsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.jobs")
    override val help get() = listOf(
        Callout("jobs:cards", tr("kami_claims.nav.jobs"), tr("kami_claims.jobs.help.cards.desc")),
        Callout("jobs:payroll", tr("kami_claims.jobs.budget"), tr("kami_claims.jobs.help.budget.desc"))
    )
    private val edits = HashMap<String, Triple<NumberState, NumberState, NumberState>>()
    private val table = TableState<JobLine>()

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val payroll = r.bottom(22)
        ui.anchor("jobs:payroll", payroll)
        val cap = info.income * snap.jobShare / 100
        val over = info.jobs > cap
        Draw.text(ui.g, tr("kami_claims.jobs.budget"), payroll.x, payroll.y + 1, TextStyle.LABEL)
        Draw.textRight(ui.g, tr("kami_libs.format.of", Format.number(info.jobs), Format.perDay(Format.money(cap))), payroll.right, payroll.y + 1, if (over) Palette.danger else Palette.textMuted)
        ui.progress(Rect(payroll.x, payroll.y + 13, payroll.w, 5), info.jobs.toDouble() / max(1, cap), if (over) Palette.danger else Palette.brass)
        val cards = r.dropBottom(22, 8)
        ui.anchor("jobs:cards", cards)
        if (info.jobList.isEmpty()) {
            ui.emptyState(cards, tr("kami_claims.jobs.empty.title"), tr("kami_claims.jobs.empty.desc"), Illustrations.JOBS)
            return
        }
        val lockReason = lock("jobs")
        val selected = info.jobList.firstOrNull { it.name in table.selected } ?: info.jobList.first()
        val editor = cards.bottom(48)
        val tableRect = cards.dropBottom(48, 8)
        ui.table(tableRect, listOf(
            Column<JobLine>(tr("kami_libs.common.job"), -1, sort = compareBy { Vocabulary.job(it.name).lowercase() }) { _, c, j ->
                val look = Vocabulary.type(j.type)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                Draw.text(g, Draw.fit(Vocabulary.job(j.name), c.right - x), x, c.y + 3, Palette.text)
            },
            Column<JobLine>(tr("kami_claims.jobs.field.pay"), 56, Align.RIGHT, compareBy { it.pay }) { _, c, j -> Draw.textRight(g, Format.money(j.pay.toLong()), c.right, c.y + 3, Palette.text) },
            Column<JobLine>(tr("kami_claims.jobs.field.quota"), 46, Align.RIGHT, compareBy { it.quota }) { _, c, j -> Draw.textRight(g, Format.number(j.quota), c.right, c.y + 3, Palette.textSecondary) },
            Column<JobLine>(tr("kami_claims.jobs.field.days"), 36, Align.RIGHT, compareBy { it.period }) { _, c, j -> Draw.textRight(g, Format.number(j.period), c.right, c.y + 3, Palette.textMuted) },
            Column<JobLine>(tr("kami_claims.common.workers"), 52, Align.RIGHT, compareBy { j -> info.members.count { j.name in it.jobs } }) { _, c, j ->
                val n = info.members.count { j.name in it.jobs }
                Draw.textRight(g, if (n == 0) "-" else Format.number(n), c.right, c.y + 3, if (n == 0) Palette.textMuted else Palette.textSecondary)
            }
        ).let { if (app.compact) it.filter { c -> c.title != tr("kami_claims.jobs.field.days") } else it }, info.jobList, table, { it.name }, key = "jobs-table")
        jobEditor(ui, editor, selected, lockReason)
    }

    private fun jobEditor(ui: Ui, r: Rect, j: JobLine, lockReason: String?) {
        val state = edits.getOrPut(j.name) { Triple(NumberState(j.pay.toLong()), NumberState(j.quota.toLong()), NumberState(j.period.toLong())) }
        val dirty = state.first.value != j.pay.toLong() || state.second.value != j.quota.toLong() || state.third.value != j.period.toLong()
        if (!dirty) { state.first.sync(j.pay.toLong()); state.second.sync(j.quota.toLong()); state.third.sync(j.period.toLong()) }
        val head = r.top(14)
        Draw.text(ui.g, Draw.fit(Vocabulary.job(j.name), head.w / 2), head.x, head.y + 2, TextStyle.LABEL)
        if (dirty) {
            val actions = Row(Rect(head.x, head.y - 2, head.w, 16))
            val saved = ui.edgeButton(actions, tr("kami_claims.common.save"), Icons.SAVE, ButtonStyle.PRIMARY, pending = pending("job_set:${j.name}"), key = "save:${j.name}")
            if (ui.edgeButton(actions, tr("kami_claims.common.undo"), key = "undo:${j.name}")) edits.remove(j.name)
            if (saved) act("job_edit", j.name, state.first.value.toString(), state.second.value.toString(), state.third.value.toString(), key = "job_set:${j.name}")
        }
        val cols = r.dropTop(16).columns(3, 6)
        val max = (limits?.maxJobPay ?: 100).toLong()
        ui.fieldLabel(cols[0].top(9), tr("kami_claims.jobs.field.pay"))
        ui.numberField(Rect(cols[0].x, cols[0].y + 10, cols[0].w, CONTROL_H), state.first, 0L, max, enabled = lockReason == null, key = "${j.name}:0")
        ui.fieldLabel(cols[1].top(9), tr("kami_claims.jobs.field.quota"))
        ui.numberField(Rect(cols[1].x, cols[1].y + 10, cols[1].w, CONTROL_H), state.second, 0L, 100_000L, enabled = lockReason == null, key = "${j.name}:1")
        ui.fieldLabel(cols[2].top(9), tr("kami_claims.jobs.field.days"))
        ui.numberField(Rect(cols[2].x, cols[2].y + 10, cols[2].w, CONTROL_H), state.third, 1L, 30L, enabled = lockReason == null, key = "${j.name}:2")
    }
}

class RanksPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.ranks")
    override val help get() = listOf(Callout("ranks:matrix", tr("kami_claims.ranks.help.matrix"), tr("kami_claims.ranks.help.matrix.desc")))

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        ui.anchor("ranks:matrix", r)
        val ranks = Vocabulary.rankOrder
        val labelW = (r.w * 0.45).toInt()
        val colW = (r.w - labelW) / ranks.size
        val head = r.top(34)
        ranks.forEachIndexed { i, rank ->
            val c = Rect(head.x + labelW + i * colW, head.y, colW, head.h)
            val look = Vocabulary.rank(rank)
            if (rank == info.rank) Draw.fill(ui.g, Rect(c.x, r.y, c.w, r.h - 12), Palette.alpha(Palette.brass, 0x18))
            Draw.icon(ui.g, look.icon, c.centerX - 8, c.y)
            Draw.text(ui.g, look.label, c.centerX - Draw.width(look.label) / 2, c.y + 17, look.color)
            val count = info.members.count { it.rank == rank }
            Draw.text(ui.g, Format.number(count), c.centerX - Draw.width(Format.number(count)) / 2, c.y + 26, Palette.textMuted)
            ui.tooltip("rank:$rank", c, look.description)
        }
        Vocabulary.caps.entries.forEachIndexed { row, (cap, look) ->
            val y = r.y + 38 + row * MATRIX_ROW_H
            val line = Rect(r.x, y, r.w, MATRIX_ROW_H)
            if (row % 2 == 0) Draw.fill(ui.g, line, Palette.alpha(0xFFFFFF, 0x06))
            val labelX = r.x + 2 + Draw.leadIcon(ui.g, look.icon, r.x + 2, line.centerY) + 2
            Draw.text(ui.g, Draw.fit(look.label, r.x + labelW - labelX - 4), labelX, y + 4, Palette.text)
            ui.tooltip("cap:$cap", line.left(labelW), Tip.text(look.description, look.label))
            val min = snap.caps[cap]?.let(::rankOf) ?: Rank.PRESIDENT
            ranks.forEachIndexed { i, rank ->
                val ok = (rankOf(rank) ?: Rank.BANISHED) >= min
                Draw.icon(ui.g, if (ok) Icons.CHECK else Icons.REMOVE, Rect(r.x + labelW + i * colW, y, colW, MATRIX_ROW_H))
            }
        }
        val footerX = r.x + 2 + Draw.leadIcon(ui.g, Icons.LOCK, r.x + 2, r.bottom - 5) + 2
        Draw.text(ui.g, tr("kami_claims.ranks.footer", Vocabulary.rank(info.rank).label), footerX, r.bottom - 9, Palette.textMuted)
    }
}

private const val MATRIX_ROW_H = 16
