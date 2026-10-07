package kami.essentials.client.admin

import kami.libs.ui.app.Paged
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminView
import kami.libs.ui.app.adminConfirmAct
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.pagedTable
import kami.essentials.admin.PlayerDetail
import kami.essentials.admin.PlayerRow
import kami.essentials.admin.WarnRow
import kami.libs.admin.Audit
import kami.libs.economy.Journal
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.Route
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.*

private val FILTERS = listOf("all", "online", "banned", "muted")
private val SORTS = listOf("name", "seen", "balance", "playtime")
private const val HEADER_H = 28
private const val PROP_H = 11
private const val INFO_H = 96
private const val WARNS_H = 100
private const val PREFETCH_MS = 250L

private fun rowSeverity(p: PlayerRow) = if (p.banned) Severity.DANGER else if (p.muted) Severity.WARNING else null

private fun state(p: PlayerRow) = when {
    p.banned -> tr("kami_essentials.admin.state.banned")
    p.muted -> tr("kami_essentials.admin.state.muted")
    p.online -> tr("kami_libs.common.online")
    else -> ""
}

private fun playerColumns() = listOf(
    playerColumn<PlayerRow>(tr("kami_libs.common.name"), { it.uuid }, { it.name }, { it.online }),
    Column.text<PlayerRow>(tr("kami_libs.common.country"), 80) { it.country.orEmpty() },
    Column.text<PlayerRow>(tr("kami_libs.common.rank"), 60) { it.rank.orEmpty() },
    Column.number<PlayerRow>(tr("kami_essentials.admin.balance"), 64, color = { Palette.money }, format = { Format.money(it, compact = true) }) { it.balance },
    Column.number<PlayerRow>(tr("kami_essentials.admin.col.seen"), 60, format = { Format.ago(it) }) { it.lastSeen },
    Column.number<PlayerRow>(tr("kami_essentials.admin.col.playtime"), 56, format = { Format.duration(it) }) { it.playtimeMs },
    Column.number<PlayerRow>(tr("kami_essentials.admin.col.warns"), 40, color = { if (it.warns > 0) Palette.warning else Palette.textMuted }) { it.warns.toLong() },
    Column.text<PlayerRow>(tr("kami_libs.common.state"), 50, color = { rowSeverity(it)?.color ?: Palette.success }) { state(it) }
)

private fun <T> Column<T>.onRowHover(span: Rect, hit: (T) -> Unit) = Column(title, width, align, sort, tip) { g, r, row ->
    this@onRowHover.cell(this, g, r, row)
    if (hovering(Rect(span.x, r.y, span.w, r.h))) hit(row)
}

private fun until(at: Long?) = at?.let { Format.until(it) } ?: tr("kami_essentials.admin.permanent")

internal class DirectoryPage(private val app: AdminApp) : Page() {
    override val title get() = tr("kami_essentials.admin.nav.directory")
    private val search = TextState()
    private var filter = FILTERS[0]
    private var sort = SORTS[0]
    private val paged = Paged("players.list", PlayerRow.serializer()) { listOf(search.text.trim(), filter, sort) }
    private val table = TableState<PlayerRow>()
    private var hovered: String? = null
    private var hoverSince = 0L

    override fun draw(ui: Ui, r: Rect) {
        val was = hovered
        hovered = null
        val body = ui.filterBar(r, "players:filters") { bar ->
            val row = Row(bar, 6)
            ui.select(row.takeFromRight(110), SORTS.map { Option(it, tr("kami_essentials.admin.sort.$it")) }, sort, key = "players:sort")?.let { sort = it }
            val chips = row.takeFromRight(190)
            ui.chipFlow(Stack(chips.x, chips.y + 2, chips.w), FILTERS.map { ChipSpec(tr("kami_essentials.admin.filter.$it"), selected = it == filter) }, "players:filter")
                ?.let { filter = FILTERS[it] }
            ui.searchField(row.remaining(), search, key = "players:search")
        }
        paged.rows.forEach { AdminData.remember(it.uuid, it.name) }
        val columns = playerColumns().let { cols ->
            listOf(cols.first().onRowHover(body) { p ->
                hovered = p.uuid
                if (p.uuid != was) hoverSince = ui.wallMillis
                else if (ui.wallMillis - hoverSince >= PREFETCH_MS) app.prefetchPlayer(p.uuid)
            }) + cols.drop(1)
        }
        val events = ui.pagedTable(body, paged, columns, table, { it.uuid }, tr("kami_essentials.admin.players.empty"), "players", ::rowSeverity)
        if (events.selectionChanged) table.selected.forEach { app.prefetchPlayer(it.toString()) }
        events.opened?.let { app.openPlayer(it.uuid) }
    }
}

internal class PlayerPage(private val app: AdminApp) : Page() {
    private class Tab(val id: String, val item: TabItem, val draw: (Ui, Rect, PlayerDetail) -> Unit)

    private var uuid: String? = null
    private var tab = "overview"
    private val detail get() = app.playerDetail
    private var money = JournalFilter("player-money", false)
    private var stats: PlayerStats? = null
    private var discord: PlayerDiscord? = null
    private val journal = Paged("journal.query", Journal.Entry.serializer()) { listOf("p:$uuid") + money.args() }
    private val history = Paged("players.history", Audit.Entry.serializer()) { listOfNotNull(uuid) }
    private val journalTable = TableState<Journal.Entry>()
    private val historyTable = TableState<Audit.Entry>()
    private val altsTable = TableState<PlayerRow>()
    private val warnsTable = TableState<WarnRow>()
    private val amount = TextState()

    override val title get() = current()?.row?.name ?: uuid?.let(AdminData::known) ?: tr("kami_essentials.admin.nav.player")

    override fun opened(route: Route) {
        val next = route.param("uuid") ?: return
        if (next != uuid) {
            uuid = next
            tab = "overview"
            amount.set("")
            money = JournalFilter("player-money", false)
            stats = null
            discord = null
        }
    }

    private fun statsTab(ui: Ui, r: Rect, d: PlayerDetail) = (stats ?: PlayerStats(d.row.uuid).also { stats = it }).draw(ui, r)

    private fun discordTab(ui: Ui, r: Rect, d: PlayerDetail) = (discord ?: PlayerDiscord(app, d.row.uuid).also { discord = it }).draw(ui, r, d, lock(d))

    private fun current() = detail.data?.takeIf { it.row.uuid == uuid }

    private fun lock(d: PlayerDetail) = lockUnless(adminManager) ?: if (d.canManage) null else tr("kami_essentials.admin.outranked")

    private fun tabs(d: PlayerDetail) = listOfNotNull(
        Tab("overview", TabItem(tr("kami_essentials.admin.tab.overview"), Icons.PERSON), ::overview),
        Tab("profile", TabItem(tr("kami_essentials.admin.tab.profile"), Icons.EYE), ::profile),
        Tab("money", TabItem(tr("kami_essentials.admin.tab.money"), Icons.COIN), ::moneyTab),
        Tab("moderation", TabItem(tr("kami_essentials.admin.tab.moderation"), Icons.BAN), ::moderation),
        Tab("stats", TabItem(tr("kami_essentials.admin.tab.stats"), Icons.STATS), ::statsTab),
        Tab("discord", TabItem(tr("kami_essentials.admin.tab.discord"), Icons.CHAIN), ::discordTab),
        Tab("position", TabItem(tr("kami_essentials.admin.tab.position"), Icons.LOCATE), ::position),
        Tab("inventory", TabItem(tr("kami_essentials.admin.tab.inventory"), Icons.CHEST), ::inventory),
        if (d.lastIp != null || d.knownIps != null || d.alts != null) Tab("network", TabItem(tr("kami_essentials.admin.tab.network"), Icons.GLOBE), ::network) else null
    )

    override fun draw(ui: Ui, r: Rect) {
        val id = uuid ?: run {
            if (ui.emptyState(r, tr("kami_essentials.admin.player.none"), tr("kami_essentials.admin.player.none.body"), action = tr("kami_essentials.admin.nav.directory"), actionIcon = Icons.PEOPLE))
                app.navigate(Route("players"))
            return
        }
        app.playerUuid = id
        detail.poll()
        val d = current() ?: run {
            if (detail.error != null && !detail.loading) ui.remoteFailed(r, detail, "player") else placeholder(ui, r)
            return
        }
        AdminData.remember(id, d.row.name)
        header(ui, r.top(HEADER_H), d)
        val tabs = tabs(d)
        val i = tabs.indexOfFirst { it.id == tab }.coerceAtLeast(0)
        ui.subTabs(r.dropTop(HEADER_H + 4).top(CONTROL_H), tabs.map { it.item }, i, key = "player-tabs")?.let { tab = tabs[it].id }
        tabs[i].draw(ui, r.dropTop(HEADER_H + CONTROL_H + 10), d)
    }

    private fun placeholder(ui: Ui, r: Rect) {
        val head = r.top(HEADER_H)
        ui.skeleton(Rect(head.x, head.y + 2, 24, 24))
        ui.skeleton(Rect(head.x + 30, head.y + 3, 90, 8))
        ui.skeleton(Rect(head.x + 30, head.y + 16, 50, 8))
        ui.skeleton(Rect(head.right - 60, head.y + 3, 60, 8))
        ui.skeleton(r.dropTop(HEADER_H + 4).top(CONTROL_H).withWidth(minOf(r.w, 360)))
        val (left, right) = r.dropTop(HEADER_H + CONTROL_H + 10).top(INFO_H).columns(2, 8)
        ui.skeletonLines(ui.card(left, tr("kami_libs.common.details"), Icons.PERSON), 6)
        ui.skeletonLines(ui.card(right, tr("kami_essentials.admin.activity"), Icons.CLOCK), 4)
    }

    private fun header(ui: Ui, r: Rect, d: PlayerDetail) {
        ui.avatar(d.row.uuid, r.x, r.y + 2, 24, d.row.online)
        val balance = Format.money(d.row.balance)
        val balanceW = Draw.width(balance, TextStyle.HEADING)
        Draw.text(ui.g, Draw.fit(d.row.name, r.w - 38 - balanceW, TextStyle.HEADING), r.x + 30, r.y + 3, TextStyle.HEADING)
        var x = r.x + 30
        val y = r.y + 15
        x += ui.statusPill(x, y, if (d.row.online) tr("kami_libs.common.online") else tr("kami_essentials.admin.state.offline"), if (d.row.online) Severity.SUCCESS else Severity.NEUTRAL) + 4
        if (d.ban != null) x += ui.statusPill(x, y, tr("kami_essentials.admin.state.banned"), Severity.DANGER) + 4
        if (d.mute != null) x += ui.statusPill(x, y, tr("kami_essentials.admin.state.muted"), Severity.WARNING) + 4
        listOfNotNull(d.row.country, d.row.rank).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Draw.text(ui.g, Draw.fit(it, r.right - x - 2), x + 2, y + 3, Palette.textMuted) }
        Draw.textRight(ui.g, balance, r.right, r.y + 3, Palette.money, TextStyle.HEADING)
    }

    private fun Ui.prop(f: Flow, label: String, value: String, color: Int = Palette.text, copy: Boolean = false) =
        property(f.take(PROP_H), label, value, color, copy, key = "player-prop:$label:$value")

    private fun overview(ui: Ui, r: Rect, d: PlayerDetail) {
        val (left, right) = r.columns(2, 8)
        val a = Flow(ui.card(left.top(INFO_H), tr("kami_libs.common.details"), Icons.PERSON), 2)
        ui.secretProperty(a.take(PROP_H), tr("kami_essentials.admin.uuid"), d.row.uuid)
        ui.prop(a, tr("kami_libs.common.name"), d.row.name, copy = true)
        ui.prop(a, tr("kami_libs.common.country"), d.row.country ?: tr("kami_libs.common.none"))
        ui.prop(a, tr("kami_libs.common.rank"), d.row.rank ?: tr("kami_libs.common.none"))
        ui.prop(a, tr("kami_essentials.admin.balance"), Format.money(d.row.balance), Palette.money)
        ui.prop(a, tr("kami_essentials.admin.col.playtime"), Format.duration(d.row.playtimeMs))
        val b = Flow(ui.card(right.top(INFO_H), tr("kami_essentials.admin.activity"), Icons.CLOCK), 2)
        ui.prop(b, tr("kami_essentials.admin.first_join"), Format.exact(d.firstJoin))
        ui.prop(b, tr("kami_essentials.admin.col.seen"), if (d.row.online) tr("kami_libs.common.online") else "${Format.exact(d.lastSeen)} (${Format.ago(d.lastSeen)})")
        ui.prop(b, tr("kami_essentials.admin.state.banned"), d.ban?.let { until(it.until) } ?: tr("kami_libs.common.none"), if (d.ban != null) Palette.danger else Palette.text)
        ui.prop(b, tr("kami_essentials.admin.state.muted"), d.mute?.let { until(it.until) } ?: tr("kami_libs.common.none"), if (d.mute != null) Palette.warning else Palette.text)
    }

    private fun moneyTab(ui: Ui, r: Rect, d: PlayerDetail) {
        val row = Row(r.top(CONTROL_H), 6)
        ui.textField(row.take(100), amount, tr("kami_libs.common.amount"), Icons.COIN, allow = { it.isDigit() || it in "kmbKMB.," }, key = "player-amount")
        val value = Format.parseAmount(amount.text)?.takeIf { it > 0 }
        val reason = lockUnless(adminManager) ?: tr("kami_essentials.admin.money.invalid").takeIf { value == null }
        fun moneyButton(kind: String, icon: Icon, danger: Boolean) {
            val args = listOf(d.row.uuid, kind, value.toString())
            if (ui.edgeButton(row, tr("kami_essentials.admin.money.$kind"), icon, if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY, reason == null, reason,
                    pending = value != null && adminPending("players.money", args), left = true, key = "money-$kind") && value != null)
                reasonDialog({ app.open(it) }, tr("kami_essentials.admin.money.$kind.title", Format.money(value), d.row.name), danger) { why, _, _ ->
                    app.adminAct("players.money", args, why) { amount.set("") }
                }
        }
        moneyButton("give", Icons.DEPOSIT, false)
        moneyButton("take", Icons.WITHDRAW, true)
        val body = money.draw(ui, r.dropTop(CONTROL_H + 6))
        ui.pagedTable(body, journal, journalColumns(false), journalTable, { it.id }, tr("kami_essentials.admin.journal.empty"), "player-journal")
    }

    private fun moderation(ui: Ui, r: Rect, d: PlayerDetail) {
        val id = d.row.uuid
        val name = d.row.name
        val row = Row(r.top(CONTROL_H), 6)
        val outrank = lock(d)
        val manage = lockUnless(adminManager)
        fun action(label: String, icon: Icon, lock: String?, danger: Boolean = false, act: String? = null, run: () -> Unit) {
            val pending = act != null && adminPending(act, listOf(id))
            if (ui.edgeButton(row, label, icon, if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY, lock == null, lock, pending = pending, left = true, key = "mod:$label")) run()
        }
        if (d.ban == null) action(tr("kami_essentials.admin.ban"), Icons.BAN, outrank, true) {
            reasonDialog({ app.open(it) }, tr("kami_essentials.admin.ban.title", name), danger = true, withDuration = true, withIp = true) { why, duration, ip ->
                app.adminAct("players.ban", listOf(id, duration ?: "perm", ip.toString()), why)
            }
        } else action(tr("kami_essentials.admin.unban"), Icons.CHECK, manage, act = "players.unban") { app.adminConfirmAct(tr("kami_essentials.admin.unban.title", name), "players.unban", listOf(id)) }
        if (d.mute == null) action(tr("kami_essentials.admin.mute"), Icons.LOCK, outrank, true) {
            reasonDialog({ app.open(it) }, tr("kami_essentials.admin.mute.title", name), danger = true, withDuration = true) { why, duration, _ ->
                app.adminAct("players.mute", listOf(id, duration ?: "perm"), why)
            }
        } else action(tr("kami_essentials.admin.unmute"), Icons.CHECK, manage, act = "players.unmute") { app.adminConfirmAct(tr("kami_essentials.admin.unmute.title", name), "players.unmute", listOf(id)) }
        action(tr("kami_essentials.admin.warn"), Icons.WARNING, outrank, act = "players.warn") {
            reasonDialog({ app.open(it) }, tr("kami_essentials.admin.warn.title", name)) { why, _, _ -> app.adminAct("players.warn", listOf(id), why) }
        }
        action(tr("kami_essentials.admin.kick"), Icons.CLOSE, outrank ?: tr("kami_essentials.admin.kick.offline").takeUnless { d.row.online }, true, "players.kick") {
            app.adminConfirmAct(tr("kami_essentials.admin.kick.title", name), "players.kick", listOf(id), danger = true)
        }
        var rest = r.dropTop(CONTROL_H + 6)
        d.ban?.let {
            ui.banner(rest.top(26), Severity.DANGER, tr("kami_essentials.admin.ban.active", it.by, until(it.until)), it.reason, key = "ban-banner")
            rest = rest.dropTop(30)
        }
        d.mute?.let {
            ui.banner(rest.top(26), Severity.WARNING, tr("kami_essentials.admin.mute.active", it.by, until(it.until)), it.reason, key = "mute-banner")
            rest = rest.dropTop(30)
        }
        if (d.warns.isNotEmpty()) {
            val h = minOf(TABLE_HEADER_H + d.warns.size * TABLE_ROW_H + 2, WARNS_H)
            ui.table(rest.top(h), warnColumns(app, outrank, false), d.warns, warnsTable, { it.id }, key = "player-warns")
            rest = rest.dropTop(h + 6)
        }
        ui.pagedTable(rest, history, auditColumns(false), historyTable, { it.id }, tr("kami_essentials.admin.audit.empty"), "player-history", ::auditSeverity)
            .opened?.let { app.auditDialog(it) }
    }

    private fun position(ui: Ui, r: Rect, d: PlayerDetail) {
        val p = d.position ?: run {
            ui.emptyState(r, tr("kami_essentials.admin.position.none"), tr("kami_essentials.admin.position.none.body"))
            return
        }
        val box = r.top(INFO_H).withWidth(minOf(r.w, 260))
        val f = Flow(ui.card(box, tr(if (p.live) "kami_essentials.admin.position.live" else "kami_essentials.admin.position.logout"), Icons.LOCATE), 2)
        ui.prop(f, tr("kami_essentials.admin.position.dim"), trOr("kami_essentials.admin.dim.${p.dim.replace(':', '.')}", p.dim))
        ui.prop(f, "X", Format.decimal(p.x))
        ui.prop(f, "Y", Format.decimal(p.y))
        ui.prop(f, "Z", Format.decimal(p.z))
        ui.prop(f, "", "${p.x.toInt()} ${p.y.toInt()} ${p.z.toInt()}", Palette.textMuted, copy = true)
        val lock = lockUnless(adminManager)
        if (ui.button(f.take(CONTROL_H), tr("kami_essentials.admin.teleport"), Icons.LOCATE, ButtonStyle.PRIMARY, lock == null, lock, pending = adminPending("players.teleport", listOf(d.row.uuid)), key = "teleport"))
            app.adminConfirmAct(tr("kami_essentials.admin.teleport.title", d.row.name), "players.teleport", listOf(d.row.uuid))
    }

    private fun profile(ui: Ui, r: Rect, d: PlayerDetail) {
        val f = Flow(ui.card(r.top(64).withWidth(minOf(r.w, 260)), tr("kami_essentials.admin.tab.profile"), Icons.EYE), 4)
        val line = f.take(PROP_H)
        Draw.text(ui.g, Draw.fit(tr("kami_essentials.admin.profile.body"), line.w), line.x, line.y, Palette.textMuted)
        if (ui.button(f.take(CONTROL_H), tr("kami_essentials.admin.profile.open"), Icons.EYE, ButtonStyle.PRIMARY, Personal.available, key = "profile-open"))
            Personal.view(Personal.Subject(d.row.uuid, d.row.name))
    }

    private fun inventory(ui: Ui, r: Rect, d: PlayerDetail) {
        val lock = lock(d)
        r.top(64).columns(2, 8).forEachIndexed { i, cell ->
            val ender = i == 1
            val label = tr(if (ender) "kami_essentials.admin.inventory.ender" else "kami_essentials.admin.inventory.main")
            val (viewBox, editBox) = ui.card(cell, label, if (ender) Icons.CHEST else Icons.PERSON).top(CONTROL_H).columns(2, 6)
            if (ui.button(viewBox, tr("kami_essentials.admin.inventory.view"), Icons.EYE, key = "inv-view-$i")) app.adminView("players.inventory", listOf(d.row.uuid, ender.toString()))
            if (ui.button(editBox, tr("kami_essentials.admin.inventory.edit"), Icons.EDIT, enabled = lock == null, disabledReason = lock,
                    pending = adminPending("players.inventory_edit", listOf(d.row.uuid, ender.toString())), key = "inv-edit-$i"))
                app.adminConfirmAct(tr("kami_essentials.admin.inventory.edit.title", label, d.row.name), "players.inventory_edit", listOf(d.row.uuid, ender.toString()))
        }
    }

    private fun network(ui: Ui, r: Rect, d: PlayerDetail) {
        val (left, right) = r.columns(listOf(1f, 1.4f), 8)
        val f = Flow(ui.card(left, tr("kami_essentials.admin.tab.network"), Icons.GLOBE), 2)
        d.lastIp?.let { ui.secretProperty(f.take(PROP_H), tr("kami_essentials.admin.last_ip"), it) }
        d.knownIps?.forEachIndexed { i, ip -> ui.secretProperty(f.take(PROP_H), if (i == 0) tr("kami_essentials.admin.known_ips") else "", ip, Palette.textSecondary) }
        val alts = d.alts ?: return
        alts.forEach { AdminData.remember(it.uuid, it.name) }
        val box = ui.card(right, tr("kami_essentials.admin.alts"), Icons.PEOPLE)
        ui.table(box, playerColumns().let { listOf(it.first(), it.last()) }, alts, altsTable, { it.uuid }, severity = ::rowSeverity, emptyText = tr("kami_essentials.admin.alts.empty"), key = "player-alts")
            .opened?.let { app.openPlayer(it.uuid) }
    }
}
