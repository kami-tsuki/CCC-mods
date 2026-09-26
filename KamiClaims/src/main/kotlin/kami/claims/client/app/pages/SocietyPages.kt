package kami.claims.client.app.pages

import kami.claims.Rank
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.net.JobLine
import kami.claims.net.Mem
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
    override val title = "Citizens"
    override val help = listOf(
        Callout("citizens:tabs", "Lists", "Members, people asking to join, and invitations you sent."),
        Callout("citizens:table", "Members", "Click someone to see their profile and manage their rank or job."),
        Callout("citizens:profile", "Profile", "Rank changes show what the person gains or loses before you confirm.")
    )
    private var tab = 0
    private val table = TableState<Mem>()
    private val search = TextState()
    private val invite = TextState()

    override fun opened(route: Route) { if (route.focus == "requests") tab = 1 }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val tabs = r.top(20)
        ui.anchor("citizens:tabs", tabs)
        val staff = lock("invite")
        ui.subTabs(tabs, listOf(
            TabItem("Members", Icons.PEOPLE, info.members.size, Severity.NEUTRAL),
            TabItem("Join requests", Icons.INVITE, info.requests.size, Severity.INFO, staff),
            TabItem("Invitations sent", Icons.SCROLL, info.invitesSent.size, Severity.NEUTRAL, staff)
        ), tab, "cit-tabs")?.let { tab = it }
        val body = r.dropTop(26)
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
        val bar = Row(left.top(20), 6)
        ui.searchField(bar.take(140), search, "Search member", key = "mem-search")
        val iw = buttonWidth("Invite", Icons.INVITE)
        val inviteBtn = bar.takeFromRight(iw)
        ui.textField(bar.takeFromRight(120), invite, "Player name", Icons.PERSON, maxLength = 16, key = "invite-name")
        if (ui.button(inviteBtn, "Invite", Icons.INVITE, ButtonStyle.PRIMARY, can("invite") && invite.text.length >= 3, lock("invite") ?: "Enter a player name", pending = pending("invite"), key = "invite-go")) {
            act("invite", invite.text, key = "invite")
            invite.set("")
        }
        val rows = info.members.filter { search.text.isBlank() || it.name.contains(search.text, true) }
        val tr = left.dropTop(24)
        ui.anchor("citizens:table", tr)
        ui.table(tr, listOf(
            Column<Mem>("Name", -1, sort = compareBy { it.name.lowercase() }) { _, c, m ->
                avatar(m.id, c.x, c.y + 2, 12, m.online)
                Draw.text(g, Draw.fit(m.name, c.w - 16), c.x + 16, c.y + 4, Palette.text)
            },
            Column<Mem>("Rank", 84, sort = compareBy { Vocabulary.rankOrder.indexOf(it.rank) }) { _, c, m ->
                val look = Vocabulary.rank(m.rank)
                Draw.icon(g, look.icon, c.x - 2, c.y + 2, 12)
                Draw.text(g, look.label, c.x + 12, c.y + 4, look.color)
            },
            Column<Mem>("Job", 90, sort = compareBy { it.job }) { _, c, m ->
                if (m.job.isEmpty()) Draw.text(g, "—", c.x, c.y + 4, Palette.textMuted)
                else {
                    val quota = info.jobList.firstOrNull { it.name == m.job }?.quota ?: 1
                    Draw.text(g, Draw.fit(m.job, c.w - 34), c.x, c.y + 4, Palette.textSecondary)
                    progress(Rect(c.right - 30, c.y + 6, 30, 4), m.progress.toDouble() / max(1, quota), if (m.progress >= quota) Palette.success else Palette.brass)
                }
            },
            Column<Mem>("Seen", 60, Align.RIGHT, compareBy { -it.seen }) { _, c, m -> Draw.textRight(g, if (m.online) "online" else Format.ago(m.seen), c.right, c.y + 4, if (m.online) Palette.success else Palette.textMuted) }
        ), rows, table, { it.id })
        if (profileW > 0) profile(ui, r.right(profileW), rows.firstOrNull { it.id in table.selected } ?: info.members.firstOrNull { it.id == snap.me })
    }

    private fun profile(ui: Ui, r: Rect, m: Mem?) {
        ui.anchor("citizens:profile", r)
        if (m == null) return
        val info = info ?: return
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val f = Flow(r.inset(8), 4)
        val head = f.take(32)
        header(ui, m, head)
        val rank = Rank.entries.firstOrNull { it.name.equals(m.rank, true) } ?: Rank.CITIZEN
        ui.property(f.take(11), "Since", if (m.since > 0) Format.ago(m.since) else "—")
        ui.property(f.take(11), "Plots", "${m.plots}")
        val job = info.jobList.firstOrNull { it.name == m.job }
        ui.property(f.take(11), "Job", job?.let { "${it.name} · ${m.progress}/${it.quota}" } ?: "none")
        val me = m.id == snap.me
        val myRank = ClaimsStore.rank
        f.skip(4)
        ui.section(f.take(14), "Rank")
        val targets = listOf(Rank.CITIZEN, Rank.OFFICER, Rank.CHANCELLOR)
        val rankLock = lock("rank") ?: when {
            me -> "You can't change your own rank"
            rank >= myRank -> "You can only manage ranks below your own"
            else -> null
        }
        ui.segmented(f.take(CONTROL_H), targets.map { t ->
            Option(t, Vocabulary.rank(t.name).label, Vocabulary.rank(t.name).icon, disabledReason = rankLock ?: if (t >= myRank) "Only ranks below yours" else if (t == Rank.CHANCELLOR && info.members.any { it.rank == "chancellor" && it.id != m.id }) "There is already a chancellor" else null)
        }, rank, key = "rank:${m.id}")?.let { next -> rankDialog(m, rank, next) }
        f.skip(4)
        ui.section(f.take(14), "Job")
        val jobLock = lock("jobs")
        ui.select(f.take(CONTROL_H), listOf(Option("", "No job", Icons.CROSS)) + info.jobList.map { j ->
            Option(j.name, j.name.replaceFirstChar { it.uppercase() }, Vocabulary.type(j.type).icon, "Works in ${Vocabulary.type(j.type).label} land · ${j.pay} ◎ for ${j.quota} actions")
        }, m.job, enabled = jobLock == null, disabledReason = jobLock, key = "job:${m.id}")?.let { next ->
            if (next.isEmpty()) act("job_unassign", m.id, key = "job") else act("job_assign", m.id, next, key = "job")
        }
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        val memberLock = lock("members") ?: if (me) "Use Leave for yourself" else if (rank >= myRank) "Only members below your rank" else null
        if (me && !info.delegated) {
            val presidentAlone = rank == Rank.PRESIDENT && info.members.size > 1
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), "Leave ${info.name}", Icons.BACK, ButtonStyle.DANGER, !presidentAlone, "Transfer presidency first", key = "leave")) {
                Dialogs.confirm(app, "Leave ${info.name}", null, Icons.BACK, listOfNotNull(
                    Consequence("You lose rank, job, and plots in ${info.name}.", Severity.DANGER),
                    if (info.members.size == 1) Consequence("Last member leaving disbands the country.", Severity.DANGER) else null,
                    Consequence("You can join or found another country after leaving.")
                ), "Leave", "leave", emptyArray(), danger = true, hold = true, typed = if (info.members.size == 1) info.name else null)
            }
            y -= CONTROL_H + 3
            if (rank == Rank.PRESIDENT) {
                if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), "Disband country", Icons.DANGER, ButtonStyle.DANGER, key = "disband")) {
                    Dialogs.confirm(app, "Disband ${info.name}", "This cannot be undone", Icons.DANGER, listOf(
                        Consequence("All ${info.chunks} chunks become nomansland.", Severity.DANGER),
                        Consequence("${info.members.size} members lose country, jobs, and plots.", Severity.DANGER),
                        Consequence("Treasury ${Format.money(info.treasury)} is lost.", Severity.DANGER),
                        Consequence("All provinces become independent.")
                    ), "Disband", "disband", arrayOf("confirm"), danger = true, hold = true, typed = info.name)
                }
            }
            return
        }
        val half = (rest.w - 4) / 2
        if (ui.button(Rect(rest.x, y, half, CONTROL_H), "Kick", Icons.BACK, ButtonStyle.DANGER, memberLock == null, memberLock, key = "kick:${m.id}")) {
            Dialogs.confirm(app, "Kick ${m.name}", "from ${info.name}", Icons.BACK, listOf(
                Consequence("${m.name} loses rank, job, and plots here.", Severity.DANGER),
                Consequence("They can ask to join again later.")
            ), "Kick", "kick", arrayOf(m.id), danger = true, hold = true)
        }
        if (ui.button(Rect(rest.x + half + 4, y, half, CONTROL_H), "Banish", Icons.BAN, ButtonStyle.DANGER, memberLock == null, memberLock, key = "ban:${m.id}")) {
            Dialogs.confirm(app, "Banish ${m.name}", "from ${info.name}", Icons.BAN, listOf(
                Consequence("${m.name} is removed and locked out of all your land.", Severity.DANGER),
                Consequence("They cannot reapply until ban is lifted in Relations.", Severity.WARNING)
            ), "Banish", "banish", arrayOf(m.id), danger = true, hold = true)
        }
        y -= CONTROL_H + 3
        if (myRank == Rank.PRESIDENT && !info.delegated) {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), "Make president…", Icons.CROWN, key = "pres:${m.id}")) {
                Dialogs.confirm(app, "Make ${m.name} president", null, Icons.CROWN, listOf(
                    Consequence("${m.name} gets every right in ${info.name}, including disbanding it.", Severity.DANGER),
                    Consequence("You become ${if (rank >= Rank.OFFICER) Vocabulary.rank(rank.name).label else "Officer"}.", Severity.WARNING),
                    Consequence("You cannot reclaim presidency yourself.", Severity.DANGER)
                ), "Transfer presidency", "president", arrayOf(m.id, "confirm"), danger = true, hold = true, typed = m.name)
            }
        }
    }

    private fun header(ui: Ui, m: Mem, r: Rect) {
        ui.avatar(m.id, r.x, r.y, 24, m.online)
        Draw.text(ui.g, m.name, r.x + 30, r.y + 3, TextStyle.HEADING)
        val look = Vocabulary.rank(m.rank)
        Draw.icon(ui.g, look.icon, r.x + 29, r.y + 12, 12)
        Draw.text(ui.g, look.label + if (m.online) " · online" else " · ${Format.ago(m.seen)}", r.x + 42, r.y + 15, look.color)
    }

    private fun rankDialog(m: Mem, from: Rank, to: Rank) {
        if (from == to) return
        val caps = snap.caps
        fun rights(r: Rank) = caps.filter { (_, min) -> runCatching { Rank.valueOf(min.uppercase()) }.getOrNull()?.let { r >= it } == true }.keys
        val gained = rights(to) - rights(from)
        val lost = rights(from) - rights(to)
        val up = to > from
        Dialogs.confirm(app, "${if (up) "Promote" else "Demote"} ${m.name} to ${Vocabulary.rank(to.name).label}", null, Vocabulary.rank(to.name).icon,
            gained.map { Consequence("Can now: ${Vocabulary.caps[it]?.description ?: it}", Severity.WARNING) } +
                lost.map { Consequence("Can no longer: ${Vocabulary.caps[it]?.description ?: it}") } +
                listOf(Consequence("${m.name} gets a message about the change.")),
            if (up) "Promote" else "Demote", "rank", arrayOf(m.id, to.name.lowercase()))
    }

    private fun requests(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.requests.isEmpty()) {
            ui.emptyState(r, "No join requests", "Players who want to join show up here. They find you on the World page.", Illustrations.CITIZENS)
            return
        }
        ui.scroll("requests", r, info.requests.size * 30) { area ->
            info.requests.forEachIndexed { i, m ->
                val row = Rect(area.x, area.y + i * 30, area.w, 26)
                Draw.sprite(ui.g, Sprites.CARD, row)
                ui.attention(row, app.isFocus("requests") && i == 0)
                ui.avatar(m.id, row.x + 5, row.y + 5, 16)
                Draw.text(ui.g, m.name, row.x + 26, row.y + 5, TextStyle.HEADING)
                val citizenships = snap.players.firstOrNull { it.id == m.id }?.citizenships?.firstOrNull { it.via.isEmpty() }
                Draw.text(ui.g, citizenships?.let { "currently in ${it.country}" } ?: "no country", row.x + 26, row.y + 15, Palette.textMuted)
                val staff = lock("invite")
                if (ui.button(Rect(row.right - 150, row.y + 4, 80, 18), "Approve", Icons.CHECK, ButtonStyle.PRIMARY, staff == null, staff, pending = pending("approve:${m.id}"), key = "approve:${m.id}")) act("approve", m.id, key = "approve:${m.id}")
                if (ui.button(Rect(row.right - 66, row.y + 4, 60, 18), "Deny", enabled = staff == null, disabledReason = staff, key = "deny:${m.id}")) act("deny", m.id, key = "deny:${m.id}")
            }
        }
    }

    private fun sent(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.invitesSent.isEmpty()) {
            ui.emptyState(r, "No open invitations", "Invite players from the Members tab. They get a chat message with an Accept button.", Illustrations.CITIZENS)
            return
        }
        info.invitesSent.forEachIndexed { i, m ->
            val row = Rect(r.x, r.y + i * 20, r.w, 18)
            ui.avatar(m.id, row.x, row.y + 3, 12)
            Draw.text(ui.g, m.name, row.x + 16, row.y + 5, Palette.text)
            Draw.textRight(ui.g, "waiting for an answer", row.right, row.y + 5, Palette.textMuted)
        }
    }
}

class JobsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Jobs"
    override val help = listOf(
        Callout("jobs:cards", "Jobs", "Each job pays members for work in one type of land. Progress is counted automatically."),
        Callout("jobs:payroll", "Wage budget", "Wages come from the treasury and are capped at a share of the daily income.")
    )
    private val edits = HashMap<String, Triple<NumberState, NumberState, NumberState>>()

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val payroll = r.bottom(40)
        ui.anchor("jobs:payroll", payroll)
        val cap = info.income * snap.jobShare / 100
        val pb = ui.card(payroll, "Wage budget", Icons.SCALES, if (info.jobs > cap) Severity.WARNING else null, trailing = "${info.jobs} of $cap ◎ per day")
        ui.progress(Rect(pb.x, pb.y + 4, pb.w, 6), info.jobs.toDouble() / max(1, cap), if (info.jobs > cap) Palette.danger else Palette.brass)
        val cards = r.dropBottom(40, 8)
        ui.anchor("jobs:cards", cards)
        if (info.jobList.isEmpty()) {
            ui.emptyState(cards, "No jobs", "The server has no jobs configured.", Illustrations.JOBS)
            return
        }
        val lockReason = lock("jobs")
        val columns = if (cards.w > 520) 2 else 1
        val h = 108
        ui.scroll("jobs", cards, ((info.jobList.size + columns - 1) / columns) * (h + 6)) { area ->
            area.grid(columns, h, info.jobList.size, 6).forEachIndexed { i, cell -> jobCard(ui, cell, info.jobList[i], lockReason) }
        }
    }

    private fun jobCard(ui: Ui, r: Rect, j: JobLine, lockReason: String?) {
        val info = info ?: return
        val look = Vocabulary.type(j.type)
        val body = ui.card(r, j.name.replaceFirstChar { it.uppercase() }, look.icon, trailing = "${look.label} land", key = "job:${j.name}")
        val state = edits.getOrPut(j.name) { Triple(NumberState(j.pay.toLong()), NumberState(j.quota.toLong()), NumberState(j.period.toLong())) }
        val dirty = state.first.value != j.pay.toLong() || state.second.value != j.quota.toLong() || state.third.value != j.period.toLong()
        if (!dirty) { state.first.sync(j.pay.toLong()); state.second.sync(j.quota.toLong()); state.third.sync(j.period.toLong()) }
        val cols = body.top(30).columns(3, 6)
        listOf(Triple("Pay ◎", state.first, 0L to (limits?.maxJobPay ?: 100).toLong()), Triple("Quota", state.second, 0L to 100_000L), Triple("Days", state.third, 1L to 30L)).forEachIndexed { i, (label, st, range) ->
            ui.fieldLabel(cols[i].top(9), label)
            ui.numberField(Rect(cols[i].x, cols[i].y + 10, cols[i].w, 18), st, range.first, range.second, enabled = lockReason == null, key = "${j.name}:$i")
        }
        val workers = info.members.filter { it.job == j.name }
        val wy = body.y + 34
        Draw.text(ui.g, if (workers.isEmpty()) "No workers. Assign one in Citizens." else "${workers.size} ${if (workers.size == 1) "worker" else "workers"}", body.x, wy, if (workers.isEmpty()) Palette.textMuted else Palette.textSecondary)
        workers.take(3).forEachIndexed { i, m ->
            val y = wy + 11 + i * 10
            Draw.text(ui.g, Draw.fit(m.name, 70), body.x, y, Palette.text)
            ui.progress(Rect(body.x + 74, y + 2, body.w - 130, 4), m.progress.toDouble() / max(1, j.quota), if (m.progress >= j.quota) Palette.success else Palette.brass)
            Draw.textRight(ui.g, "${m.progress}/${j.quota}", body.right, y, Palette.textMuted)
        }
        ui.tooltip("job-help:${j.name}", body.top(30), Tip("How ${j.name}s are paid", listOf(
            "Counted: ${j.actions.joinToString(", ")} in ${look.label} land${if (j.blocks.isNotEmpty()) " (${j.blocks.take(3).joinToString(", ")})" else ""}." to Palette.textSecondary,
            "Paid ${j.pay} ◎ when ${j.quota} actions are done within ${j.period} ${if (j.period == 1) "day" else "days"}." to Palette.textSecondary
        )))
        if (dirty) {
            val save = Rect(body.right - 60, body.bottom - 16, 60, 16)
            if (ui.button(save, "Save", Icons.SAVE, ButtonStyle.PRIMARY, pending = pending("job_set:${j.name}"), key = "save:${j.name}")) {
                act("job_edit", j.name, state.first.value.toString(), state.second.value.toString(), state.third.value.toString(), key = "job_set:${j.name}")
            }
            if (ui.button(Rect(save.x - 58, save.y, 54, 16), "Undo", key = "undo:${j.name}")) edits.remove(j.name)
            Draw.fill(ui.g, Rect(r.right - 4, r.y + 2, 2, 2), Palette.brass)
        }
    }
}

class RanksPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Ranks"
    override val help = listOf(Callout("ranks:matrix", "Who may do what", "Each row is a right, each column a rank. Your rank is highlighted. The server config decides the minimum rank."))

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
            Draw.text(ui.g, "$count", c.centerX - Draw.width("$count") / 2, c.y + 26, Palette.textMuted)
            ui.tooltip("rank:$rank", c, look.description)
        }
        Vocabulary.caps.entries.forEachIndexed { row, (cap, look) ->
            val y = r.y + 38 + row * 16
            val line = Rect(r.x, y, r.w, 15)
            if (row % 2 == 0) Draw.fill(ui.g, line, Palette.alpha(0xFFFFFF, 0x06))
            Draw.icon(ui.g, look.icon, r.x, y - 1)
            Draw.text(ui.g, look.label, r.x + 20, y + 4, Palette.text)
            ui.tooltip("cap:$cap", line.left(labelW), Tip.text(look.description, look.label))
            val min = snap.caps[cap]?.let { runCatching { Rank.valueOf(it.uppercase()) }.getOrNull() } ?: Rank.PRESIDENT
            ranks.forEachIndexed { i, rank ->
                val ok = Rank.valueOf(rank.uppercase()) >= min
                val c = Rect(r.x + labelW + i * colW, y, colW, 15)
                Draw.icon(ui.g, if (ok) Icons.CHECK else Icons.REMOVE, c.centerX - 6, y + 1, 12)
            }
        }
        Draw.icon(ui.g, Icons.LOCK, r.x, r.bottom - 12, 12)
        Draw.text(ui.g, "Set by the server config. You are ${Vocabulary.rank(info.rank).label}.", r.x + 14, r.bottom - 9, Palette.textMuted)
    }
}
