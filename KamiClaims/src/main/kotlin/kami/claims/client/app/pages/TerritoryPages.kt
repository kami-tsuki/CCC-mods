package kami.claims.client.app.pages

import kami.claims.client.rankOf
import kami.libs.ui.text.trJson
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClientLocks
import kami.claims.research.Capacity
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Tenure
import kami.libs.ui.core.Memo
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.net.ClaimLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import kotlin.math.abs
import kotlin.math.max

private fun cellsArg(rows: Collection<ClaimLine>) = rows.joinToString(",") { "${it.x}:${it.z}" }

class ChunksPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.chunks")
    override val help get() = listOf(
        Callout("chunks:filters", tr("kami_claims.ledger.help.filter"), tr("kami_claims.chunks.help.filter.desc")),
        Callout("chunks:table", tr("kami_claims.chunks.help.table"), tr("kami_claims.table.hint")),
        Callout("chunks:bulk", tr("kami_claims.chunks.help.bulk"), tr("kami_claims.chunks.help.bulk.desc"))
    )
    private val search = TextState()
    private var type = "all"
    private var state = "all"
    private val table = TableState<ClaimLine>()
    private var bulkType = ""

    override fun opened(route: Route) {
        if (route.focus == "debt") state = "debt"
    }

    private fun status(c: ClaimLine) = when {
        c.debt > 0 -> tr("kami_claims.chunks.status.debt", c.debt, limits?.maxDebt ?: 3)
        c.free -> tr("kami_claims.chunks.status.free")
        c.locked.isNotEmpty() -> tr("kami_claims.chunks.status.new")
        else -> tr("kami_libs.common.paid")
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        var rows: List<ClaimLine> = emptyList()
        val afterBar = ui.filterBar(r, "chunks:filters") { barRect ->
            val bar = Row(barRect, 6)
            ui.searchField(bar.take(150), search, tr("kami_claims.chunks.search"), key = "chunk-search")
            ui.select(bar.take(130), listOf(Option("all", tr("kami_claims.chunks.filter.all_types"), Icons.LAYERS)) + snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color) }, type, key = "chunk-type")?.let { type = it }
            ui.segmented(bar.take(200), listOf(Option("all", tr("kami_libs.common.all")), Option("debt", tr("kami_claims.stats.in_debt"), Icons.DEBT), Option("plots", tr("kami_claims.nav.plots"), Icons.HOUSE), Option("free", tr("kami_claims.chunks.status.free"))), state, key = "chunk-state")?.let { state = it }
            rows = ui.filtered("chunks-filtered", listOf(type, state, search.text, info.claimList)) {
                info.claimList.filter { c ->
                    (type == "all" || c.type == type) && when (state) {
                        "debt" -> c.debt > 0
                        "plots" -> c.owner.isNotEmpty()
                        "free" -> c.free
                        else -> true
                    } && (search.text.isBlank() || "${c.x}, ${c.z} ${c.x},${c.z} ${c.owner} ${c.type}".contains(search.text.trim(), true))
                }
            }
            val summary = tr("kami_claims.chunks.summary", Format.number(rows.size), Format.number(info.chunks), Format.number(info.free), Format.number(info.claimList.count { it.debt > 0 }), Format.perDay(Format.money(info.upkeep)))
            Draw.textRight(ui.g, Draw.fit(summary, bar.rest.w), r.right, r.y + 5, Palette.textMuted)
        }
        val selected = rows.filter { "${it.x}:${it.z}" in table.selected }
        val tableArea = if (selected.isNotEmpty()) afterBar.dropBottom(BULK_H, 4) else afterBar
        ui.anchor("chunks:table", tableArea)
        if (info.claimList.isEmpty()) {
            if (ui.emptyState(tableArea, tr("kami_claims.chunks.empty.title"), tr("kami_claims.chunks.empty.desc"), Illustrations.FOUND, tr("kami_claims.alert.open.map"), Icons.MAP)) app.navigate(Route("map"))
            return
        }
        val events = ui.table(tableArea, listOf(
            Column<ClaimLine>(tr("kami_claims.wizard.chunk"), 80, sort = compareBy({ it.x }, { it.z })) { _, c, row ->
                Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text)
                if (row.capital) Draw.leadIcon(g, Icons.CROWN, c.right - Draw.ICON, c.centerY)
            },
            Column<ClaimLine>(tr("kami_claims.chunks.col.type"), 100, sort = compareBy { it.type }) { _, c, row ->
                val look = Vocabulary.type(row.type)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                Draw.text(g, Draw.fit(look.label, c.right - x), x, c.y + 3, look.color)
            },
            Column<ClaimLine>(tr("kami_claims.ledger.upkeep"), 56, Align.RIGHT, compareBy { price(it) }) { _, c, row -> Draw.textRight(g, if (row.free) tr("kami_claims.chunks.status.free") else tr("kami_libs.unit.money", Format.decimal(price(row))), c.right, c.y + 3, Palette.textSecondary) },
            Column<ClaimLine>(tr("kami_libs.common.state"), 76, sort = compareBy { -it.debt }) { _, c, row ->
                val sev = when { row.debt > 0 -> Severity.DANGER; row.free -> Severity.SUCCESS; row.locked.isNotEmpty() -> Severity.INFO; else -> Severity.NEUTRAL }
                statusPill(c.x, c.y, status(row), sev, row.locked.ifEmpty { null }?.let(::trJson), key = "st:${row.x}:${row.z}")
            },
            Column.text<ClaimLine>(tr("kami_claims.role.owner"), -1, color = { if (it.rentDebt > 0) Palette.danger else Palette.textSecondary }) { row -> row.owner + if (row.rentDebt > 0) "  (${tr("kami_claims.home.debt", Format.money(row.rentDebt))})" else "" },
            Column<ClaimLine>(tr("kami_claims.chunks.col.claimed"), 64, Align.RIGHT, compareBy { it.at }) { _, c, row -> Draw.textRight(g, if (row.at > 0) Format.ago(row.at) else "", c.right, c.y + 3, Palette.textMuted) }
        ), rows, table, { "${it.x}:${it.z}" }, multi = can("claim"), severity = { if (it.debt > 0) Severity.DANGER else null }, emptyText = tr("kami_claims.chunks.empty.filtered"))
        events.opened?.let { app.openMapAt(it.x, it.z) }
        if (selected.isNotEmpty()) bulk(ui, r.bottom(BULK_H), selected)
    }

    private fun price(c: ClaimLine): Double {
        val t = snap.types.firstOrNull { it.name == c.type } ?: return 0.0
        return Vocabulary.perDay(t.price.toDouble(), t.period)
    }

    private fun bulk(ui: Ui, r: Rect, selected: List<ClaimLine>) {
        ui.anchor("chunks:bulk", r)
        Draw.fill(ui.g, r, Palette.selected)
        Draw.fill(ui.g, r.left(2), Palette.brass)
        val count = tr("kami_claims.chunks.selected", trn("kami_claims.unit.chunk", selected.size))
        Draw.text(ui.g, count, r.x + 8, r.y + (r.h - 8) / 2, TextStyle.HEADING)
        val row = Row(r.inset(4, 2), 4)
        row.take(Draw.width(count, TextStyle.HEADING) + 10)
        val release = tr("kami_claims.chunks.release")
        if (ui.edgeButton(row, release, Icons.REMOVE, ButtonStyle.DANGER, can("claim"), lock("claim"), key = "bulk-release")) {
            val saved = selected.filter { !it.free }.sumOf { price(it) }
            val blocked = selected.filter { it.capital || it.locked.isNotEmpty() }
            Dialogs.confirm(app, tr("kami_claims.chunks.release.confirm.title", trn("kami_claims.unit.chunk", selected.size)), tr("kami_claims.chunks.release.confirm.subtitle"), Icons.REMOVE, listOfNotNull(
                Consequence(tr("kami_claims.chunks.release.open"), Severity.DANGER),
                if (blocked.isNotEmpty()) Consequence(tr("kami_claims.chunks.release.blocked", trn("kami_claims.unit.chunk", blocked.size)), Severity.WARNING) else null,
                Consequence(tr("kami_claims.chunks.release.split")),
                Consequence(tr("kami_claims.chunks.release.saved", Format.perDay(tr("kami_libs.unit.money", Format.decimal(saved)))), Severity.SUCCESS)
            ), tr("kami_claims.chunks.release.action"), "unclaimcells", arrayOf("cells", cellsArg(selected)), danger = true, hold = true) { table.clear() }
        }
        if (bulkType.isEmpty()) bulkType = snap.types.firstOrNull()?.name ?: ""
        val retype = tr("kami_claims.chunks.retype")
        val retyped = ui.edgeButton(row, retype, enabled = can("claim"), disabledReason = lock("claim"), key = "bulk-type-go")
        ui.select(row.takeFromRight(120), snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color, lock = ClientLocks.claimType(it.name)) }, bulkType, key = "bulk-type")?.let { bulkType = it }
        if (retyped) {
            val t = snap.types.firstOrNull { it.name == bulkType }
            val delta = selected.filter { !it.free }.sumOf { (t?.let { it.price.toDouble() / max(1, it.period) } ?: 0.0) - price(it) }
            val losingPlots = selected.count { it.owner.isNotEmpty() && bulkType != "residential" }
            val sign = if (delta > 0) "+" else ""
            Dialogs.confirm(app, tr("kami_claims.chunks.retype.confirm.title", trn("kami_claims.unit.chunk", selected.size), Vocabulary.type(bulkType).label), null, Icons.EDIT, listOfNotNull(
                if (losingPlots > 0) Consequence(tr("kami_claims.chunks.retype.plots", Format.number(losingPlots)), Severity.DANGER) else null,
                Consequence(tr("kami_claims.chunks.retype.delta", Format.perDay(tr("kami_libs.unit.money", sign + Format.decimal(delta)))), if (delta > 0) Severity.WARNING else Severity.SUCCESS),
                Consequence(tr("kami_claims.chunks.retype.rules", Vocabulary.type(bulkType).label))
            ), tr("kami_claims.chunks.retype.action"), "typecells", arrayOf(bulkType, "cells", cellsArg(selected))) { table.clear() }
        }
        val showOnMap = tr("kami_claims.toast.show_on_map")
        if (ui.edgeButton(row, showOnMap, Icons.MAP, key = "bulk-map")) selected.firstOrNull()?.let { app.openMapAt(it.x, it.z) }
    }
}

class PlotsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.plots")
    override val help get() = listOf(
        Callout("plots:tabs", tr("kami_claims.nav.plots"), tr("kami_claims.plots.help.tabs.desc")),
        Callout("plots:list", tr("kami_claims.plots.help.list"), tr("kami_claims.plots.help.list.desc"))
    )
    private var tab = 0
    private val tableFree = TableState<ClaimLine>()
    private val tableAll = TableState<ClaimLine>()
    private val tableMoving = TableState<ClaimLine>()
    private var focusPlot: Pair<Int, Int>? = null
    private val trustName = TextState()
    private var trustRole = "household"
    private val lists = Memo()
    private val cardLines = Memo()

    private class Lists(val mine: List<ClaimLine>, val free: List<ClaimLine>, val taken: List<ClaimLine>, val moving: List<ClaimLine>, val debt: Int)

    override fun opened(route: Route) {
        val f = route.focus ?: return
        if (f == "moving") tab = 3
        f.split(':').takeIf { it.size == 2 }?.let { (x, z) -> focusPlot = (x.toIntOrNull() ?: return@let) to (z.toIntOrNull() ?: return@let); tab = 0 }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val l = lists.of(info.claimList, snap.me) {
            val taken = info.claimList.filter { it.owner.isNotEmpty() }
            Lists(info.claimList.filter { it.ownerId == snap.me }, info.claimList.filter { it.type == "residential" && it.owner.isEmpty() }, taken, taken.filter { it.state == Tenure.MOVING }, taken.count { it.rentDebt > 0 })
        }
        val tabsRect = r.top(CONTROL_H)
        ui.anchor("plots:tabs", tabsRect)
        ui.subTabs(tabsRect, listOf(
            TabItem(tr("kami_claims.plots.tab.mine"), null, l.mine.size, Severity.INFO),
            TabItem(tr("kami_claims.plots.tab.free"), null, l.free.size, Severity.SUCCESS),
            TabItem(tr("kami_claims.plots.tab.all"), null, l.debt, Severity.WARNING, lock("details")),
            TabItem(tr("kami_claims.plots.tab.moving"), null, l.moving.size, Severity.DANGER, lock("details"))
        ), tab, "plot-tabs")?.let { tab = it }
        val me = info.members.firstOrNull { it.id == snap.me }
        val held = me?.plotsHeld ?: l.mine.size
        val limit = me?.plotLimit ?: snap.maxPlots
        val plotRaise = ClientLocks.raise(Capacity.PLOTS)?.takeIf { held >= limit }
        val plotChip = plotRaise?.let { lockChipWidth(it) + 4 } ?: 0
        Draw.textRight(ui.g, tr("kami_claims.plots.rented", Format.number(held), Format.number(limit)), r.right - plotChip, r.y + 5, if (held >= limit) Palette.warning else Palette.textMuted)
        plotRaise?.let { ui.lockChip(r.right - plotChip + 4, r.y + 2, it, "plots-raise") }
        val body = r.dropTop(CONTROL_H + 6)
        ui.anchor("plots:list", body)
        when (tab) {
            0 -> mine(ui, body, l.mine)
            1 -> free(ui, body, l.free, held >= limit, limit)
            2 -> list(ui, body, l.taken, tableAll, true)
            else -> list(ui, body, l.moving, tableMoving, false)
        }
    }

    private fun mine(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        if (plots.isEmpty()) {
            if (ui.emptyState(r, tr("kami_claims.plots.empty.mine.title"), tr("kami_claims.plots.empty.mine.desc"), Illustrations.CITIZENS, tr("kami_claims.plots.empty.mine.action"), Icons.HOUSE)) tab = 1
            return
        }
        val focus = focusPlot?.takeIf { f -> plots.any { it.x == f.first && it.z == f.second } } ?: (plots.first().x to plots.first().z).also { focusPlot = it }
        val (list, detail) = r.columns(listOf(1f, 1.3f), 8)
        val lines = cardLines.of(plots, Format.locale, System.currentTimeMillis() / 60_000) {
            plots.map { PlotCard(it, tr("kami_claims.plot.at", it.x, it.z), Format.perDay(Format.money(it.rent.toLong())), Tenure.label(it.state), Tenure.severity(it.state, it.rentDebt), Tenure.note(it, it.state, it.until, it.rentDebt), Tenure.color(it.state, it.rentDebt), "plot-state:${it.x}:${it.z}") }
        }
        ui.scroll("my-plots", list, plots.size * (PLOT_CARD_H + 4)) { area ->
            lines.forEachIndexed { i, l ->
                val c = l.claim
                val card = Rect(area.x, area.y + i * (PLOT_CARD_H + 4), area.w, PLOT_CARD_H)
                val chosen = focus == c.x to c.z
                Draw.sprite(ui.g, if (chosen) Sprites.CARD_HOVER else Sprites.CARD, card)
                if (chosen) Draw.fill(ui.g, card.left(2), Palette.brass)
                ui.attention(card, app.isFocus("${c.x}:${c.z}"))
                Draw.text(ui.g, l.title, card.x + 6, card.y + 5, TextStyle.HEADING)
                Draw.textRight(ui.g, l.rent, card.right - 6, card.y + 5, Palette.money)
                val pill = ui.statusPill(card.x + 6, card.y + 19, l.state, l.severity, key = l.key)
                Draw.text(ui.g, Draw.fit(l.note, card.w - pill - 20), card.x + 12 + pill, card.y + 22, l.color)
                if (ui.pressed(card) != null) { focusPlot = c.x to c.z; ClaimsStore.quiet("chunk", c.x.toString(), c.z.toString()) }
            }
        }
        val c = plots.first { it.x == focus.first && it.z == focus.second }
        val moving = c.state == Tenure.MOVING
        val body = ui.card(detail, tr("kami_claims.plot.at", c.x, c.z), Icons.HOUSE, if (moving) Severity.DANGER else if (c.rentDebt > 0) Severity.WARNING else null)
        val f = Flow(body, 4)
        val hint = Tenure.hint(c.state)
        if (hint != null) {
            if (moving && c.until > 0) Draw.text(ui.g, Tenure.countdown(c.until), f.rest.x, f.rest.y, TextStyle.HEADING, Palette.danger).also { f.skip(14) }
            f.take(Draw.paragraph(ui.g, hint, f.rest.x, f.rest.y, f.rest.w, if (moving) Palette.danger else Palette.warning))
        }
        val top = f.take(66)
        MiniMap.draw(ui, top.left(66), c.x, c.z, 2, "plot", listOf(c.x to c.z))
        val props = Flow(top.dropLeft(74), 3)
        ui.property(props.take(11), tr("kami_claims.common.rent"), Format.perDay(Format.money(c.rent.toLong())))
        ui.property(props.take(11), tr("kami_libs.common.state"), Tenure.label(c.state), Tenure.severity(c.state, c.rentDebt).color)
        if (c.rentDebt > 0) ui.property(props.take(11), tr("kami_claims.common.rent_debt"), "${Format.money(c.rentDebt)} / ${Format.money(info?.rentDebtLimit ?: 0)}", Palette.danger)
        if (ui.link(props.rest.x, props.rest.y + 4, tr("kami_claims.toast.show_on_map"), key = "plot-map")) app.openMapAt(c.x, c.z)
        val d = snap.detail?.takeIf { it.x == c.x && it.z == c.z }
        ui.section(f.take(14), tr("kami_claims.plots.access"))
        if (d == null) ClaimsStore.quiet("chunk", c.x.toString(), c.z.toString())
        val roles = d?.roles ?: emptyList()
        if (roles.isEmpty()) f.take(12).let { Draw.text(ui.g, tr("kami_claims.plots.access.empty"), it.x, it.y, Palette.textMuted) }
        roles.forEach { line ->
            val row = f.take(14)
            val name = line.substringBefore(":")
            val role = line.substringAfter(": ")
            Draw.text(ui.g, name, row.x, row.y + 3, Palette.text)
            ui.chip(row.x + 100, row.y, tr("kami_claims.role.$role"), Vocabulary.rank(if (role == "household") "citizen" else role).color)
            if (ui.iconButton(Rect(row.right - 16, row.y - 1, 16, 16), Icons.REMOVE, tr("kami_libs.common.remove_x", name), key = "untrust:$name")) act("plot_untrust", name, c.x.toString(), c.z.toString(), key = "plot_untrust")
        }
        f.skip(4)
        ui.section(f.take(14), tr("kami_claims.plots.access.add"))
        val roles2 = listOf(
            Option("household", tr("kami_claims.role.household"), Icons.HOUSE, tr("kami_claims.role.household.desc")),
            Option("allied", tr("kami_claims.role.allied"), Icons.HANDSHAKE, tr("kami_claims.role.allied.desc")),
            Option("banished", tr("kami_claims.role.banished"), Icons.BAN, tr("kami_claims.role.banished.desc"))
        )
        val entry = f.take(CONTROL_H)
        ui.textField(entry.left(entry.w - 90), trustName, tr("kami_claims.field.player"), Icons.PERSON, maxLength = 16, key = "trust-name")
        if (ui.button(entry.right(86), tr("kami_libs.common.add"), Icons.ADD, ButtonStyle.PRIMARY, trustName.text.length >= 3, tr("kami_claims.field.player.disabled"), pending = pending("plot_trust"), key = "trust-go")) {
            act("plot_trust", trustName.text, trustRole, c.x.toString(), c.z.toString(), key = "plot_trust")
            trustName.set("")
        }
        ui.radioGroup(f.take(radioGroupHeight(roles2, body.w)), roles2, trustRole, key = "trust-role")?.let { trustRole = it }
        val bottom = f.rest
        if (ui.button(Rect(bottom.x, bottom.bottom - CONTROL_H, bottom.w, CONTROL_H), tr("kami_claims.plots.release"), Icons.REMOVE, ButtonStyle.SECONDARY, key = "plot-release")) {
            Dialogs.release(app, c.x, c.z, null, moving)
        }
    }

    private fun free(ui: Ui, r: Rect, plots: List<ClaimLine>, full: Boolean, limit: Int) {
        if (plots.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.plots.empty.free.title"), tr("kami_claims.plots.empty.free.desc"), Illustrations.CITIZENS)
            return
        }
        val events = ui.table(r.dropBottom(26, 4), listOf(
            Column<ClaimLine>(tr("kami_claims.help.term.plot"), -1, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text) },
            Column.number<ClaimLine>(tr("kami_claims.plots.col.rent"), 70, format = { Format.perDay(Format.money(it)) }) { it.rent.toLong() },
            Column.number<ClaimLine>(tr("kami_claims.plots.col.distance"), 70, format = { trn("kami_claims.unit.chunk", it) }) { (abs(it.x - snap.px) + abs(it.z - snap.pz)).toLong() },
        ), plots, tableFree, { "${it.x}:${it.z}" })
        events.opened?.let { app.openMapAt(it.x, it.z) }
        val chosen = plots.firstOrNull { "${it.x}:${it.z}" in tableFree.selected }
        val bar = r.bottom(22)
        val lockReason = lock("plot") ?: if (full) tr("kami_claims.plots.limit", Format.number(limit)) else null
        val row = Row(Rect(bar.x, bar.y, bar.w, CONTROL_H))
        if (ui.edgeButton(row, tr("kami_claims.plots.rent"), Icons.HOUSE, ButtonStyle.PRIMARY, chosen != null && lockReason == null, lockReason ?: tr("kami_claims.plots.select_first"), key = "rent")) chosen?.let { c ->
            Dialogs.rent(app, c.x, c.z, c.rent, tr("kami_claims.chunk_type.residential"))
        }
        if (ui.edgeButton(row, tr("kami_claims.toast.show_on_map"), Icons.MAP, enabled = chosen != null, disabledReason = tr("kami_claims.plots.select_first"), key = "free-map")) chosen?.let { app.openMapAt(it.x, it.z) }
    }

    private fun outranks(id: String): Boolean {
        val m = info?.members?.firstOrNull { it.id == id } ?: return true
        val rank = rankOf(m.rank) ?: return true
        return ClaimsStore.rank > rank
    }

    private fun list(ui: Ui, r: Rect, plots: List<ClaimLine>, state: TableState<ClaimLine>, removable: Boolean) {
        val wide = !app.compact
        val removeLock = lock("housing")
        val removeTip = tr("kami_claims.plots.remove")
        val mapTip = tr("kami_claims.nav.map")
        val columns = listOfNotNull(
            Column<ClaimLine>(tr("kami_claims.help.term.plot"), 64, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text) },
            Column.text<ClaimLine>(tr("kami_claims.plots.col.owner"), -1) { it.owner },
            Column<ClaimLine>(tr("kami_libs.common.state"), 84, sort = compareBy { it.state }) { _, c, row ->
                statusPill(c.x, c.y, Tenure.label(row.state), Tenure.severity(row.state, row.rentDebt), key = "p:${row.x}:${row.z}")
            },
            Column<ClaimLine>(tr("kami_claims.plots.col.rent_debt"), 64, Align.RIGHT, compareBy { it.rentDebt * 1000 + it.rent }) { _, c, row ->
                val debt = row.rentDebt > 0
                val s = Draw.fit(if (debt) tr("kami_claims.home.debt", Format.money(row.rentDebt)) else Format.perDay(Format.money(row.rent.toLong())), c.w)
                Draw.textRight(g, s, c.right, c.y + 3, if (debt) Palette.warning else Palette.textSecondary)
            },
            if (wide) Column.text<ClaimLine>(tr("kami_claims.plots.col.category"), 72) { Tenure.category(it.category) } else null,
            if (wide) Column<ClaimLine>(tr("kami_claims.plots.col.left"), 60, Align.RIGHT, compareBy { it.until }) { _, c, row ->
                if (row.state == Tenure.MOVING && row.until > 0) Draw.textRight(g, Draw.fit(Tenure.note(row, row.state, row.until, 0), c.w), c.right, c.y + 3, Palette.danger)
            } else null,
            Column<ClaimLine>("", 34) { _, c, row ->
                val canRemove = removable && removeLock == null && row.state != Tenure.MOVING && outranks(row.ownerId)
                if (canRemove && iconButton(Rect(c.x, c.y + 1, 12, 12), Icons.BAN, removeTip, key = (row.x * 31 + row.z) * 2)) Dialogs.removeTenant(app, row.x, row.z, row.owner)
                if (iconButton(Rect(c.x + 16, c.y + 1, 12, 12), Icons.MAP, mapTip, key = (row.x * 31 + row.z) * 2 + 1)) app.openMapAt(row.x, row.z)
            }
        )
        val events = ui.table(r, columns, plots, state, { "${it.x}:${it.z}" }, severity = { if (it.state == Tenure.MOVING) Severity.DANGER else if (it.rentDebt > 0) Severity.WARNING else null }, emptyText = tr("kami_claims.plots.empty.all"), key = "plots:table")
        events.opened?.let { app.openMapAt(it.x, it.z) }
    }
}

private class PlotCard(val claim: ClaimLine, val title: String, val rent: String, val state: String, val severity: Severity, val note: String, val color: Int, val key: String)

private const val BULK_H = 22
private const val PLOT_CARD_H = 38
