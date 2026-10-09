package kami.claims.client.app.pages

import kami.claims.client.store.ClientResearch
import kami.claims.client.app.rankOf
import kami.libs.text.SearchQuery
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
import kami.claims.client.store.ClaimsStore
import kami.claims.net.ClaimLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import kotlin.math.abs
import kotlin.math.max

private fun cellsArg(rows: Collection<ClaimLine>) = rows.joinToString(",") { "${it.x}:${it.z}" }

class ChunksPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.chunks")
    override val help get() = listOf(
        Callout("chunks:filters", tr("kami_libs.common.filter"), tr("kami_claims.chunks.help.filter.desc")),
        Callout("chunks:table", tr("kami_claims.chunks.help.table"), tr("kami_claims.table.hint")),
        Callout("chunks:bulk", tr("kami_claims.chunks.help.bulk"), tr("kami_claims.chunks.help.bulk.desc"))
    )
    private val search = TextState()
    private var type = "all"
    private var state = "all"
    private val table = TableState<ClaimLine>()
    private var bulkType = ""
    private val chunkColumns = Memo()
    private val chunkTypes = Memo()
    private val chunkStates = Memo()
    private val bulkTypes = Memo()

    override fun opened(route: Route) {
        if (route.focus == "debt") state = "debt"
    }

    private fun status(c: ClaimLine) = when {
        c.debt > 0 -> tr("kami_claims.chunks.status.debt", c.debt, limits?.maxDebt ?: 3)
        c.free -> tr("kami_libs.common.free")
        c.locked.isNotEmpty() -> tr("kami_claims.chunks.status.new")
        else -> tr("kami_libs.common.paid")
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        var rows: List<ClaimLine> = emptyList()
        val afterBar = ui.filterBar(r, "chunks:filters") { barRect ->
            val bar = Row(barRect, 6)
            val tight = app.compact || barRect.w < 520
            ui.searchField(bar.take(if (tight) 110 else 150), search, tr("kami_claims.chunks.search"), key = "chunk-search")
            val types = chunkTypes.of(snap.types, Format.locale) { listOf(Option("all", tr("kami_claims.chunks.filter.all_types"), Icons.LAYERS)) + snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color) } }
            ui.select(bar.take(if (tight) 100 else 130), types, type, key = "chunk-type")?.let { type = it }
            val states = chunkStates.of(Format.locale) { listOf(Option("all", tr("kami_libs.common.all")), Option("debt", tr("kami_claims.stats.in_debt"), Icons.DEBT), Option("plots", tr("kami_claims.nav.plots"), Icons.HOUSE), Option("free", tr("kami_libs.common.free"))) }
            (if (tight) ui.select(bar.take(110), states, state, key = "chunk-state") else ui.segmented(bar.take(200), states, state, key = "chunk-state"))?.let { state = it }
            rows = ui.filtered("chunks-filtered", listOf(type, state, search.text, info.claimList)) {
                val q = SearchQuery.parse(search.text)
                info.claimList.filter { c ->
                    (type == "all" || c.type == type) && when (state) {
                        "debt" -> c.debt > 0
                        "plots" -> c.owner.isNotEmpty()
                        "free" -> c.free
                        else -> true
                    } && q.matches("${c.x}, ${c.z}", "${c.x},${c.z}", c.owner, c.type)
                }
            }
            val summary = tr("kami_claims.chunks.summary", Format.number(rows.size), Format.number(info.chunks), Format.number(info.free), Format.number(info.claimList.count { it.debt > 0 }), Format.perDay(Format.money(info.upkeep)))
            if (bar.rest.w > 40) ui.fitText(bar.rest.x, r.y + 5, bar.rest.w, summary, Palette.textMuted, align = Align.RIGHT)
        }
        val selected = rows.filter { "${it.x}:${it.z}" in table.selected }
        val tableArea = if (selected.isNotEmpty()) afterBar.dropBottom(BULK_H, 4) else afterBar
        ui.anchor("chunks:table", tableArea)
        if (info.claimList.isEmpty()) {
            if (ui.emptyState(tableArea, tr("kami_claims.chunks.empty.title"), tr("kami_claims.chunks.empty.desc"), Illustrations.FOUND, tr("kami_claims.alert.open.map"), Icons.MAP)) app.navigate(Route("map"))
            return
        }
        val columns = chunkColumns.of(Format.locale) { listOf(
            Column<ClaimLine>(tr("kami_claims.wizard.chunk"), 80, sort = compareBy({ it.x }, { it.z })) { _, c, row ->
                Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text)
                if (row.capital) Draw.leadIcon(g, Icons.CROWN, c.right - Draw.ICON, c.centerY)
            },
            Column<ClaimLine>(tr("kami_libs.common.type"), 100, sort = compareBy { it.type }) { _, c, row ->
                val look = Vocabulary.type(row.type)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                fitText(x, c.y + 3, c.right - x, look.label, look.color, key = "ct:${row.x}:${row.z}")
            },
            Column<ClaimLine>(tr("kami_claims.ledger.upkeep"), 56, Align.RIGHT, compareBy { price(it) }) { _, c, row -> Draw.textRight(g, if (row.free) tr("kami_libs.common.free") else Format.rateMoney(price(row)), c.right, c.y + 3, Palette.textSecondary) },
            Column<ClaimLine>(tr("kami_libs.common.state"), 76, sort = compareBy { -it.debt }) { _, c, row ->
                val sev = when { row.debt > 0 -> Severity.DANGER; row.free -> Severity.SUCCESS; row.locked.isNotEmpty() -> Severity.INFO; else -> Severity.NEUTRAL }
                statusPill(c.x, c.y, status(row), sev, row.locked.ifEmpty { null }?.let(::trJson), key = "st:${row.x}:${row.z}")
            },
            Column.text<ClaimLine>(tr("kami_claims.role.owner"), -1, color = { if (it.rentDebt > 0) Palette.danger else Palette.textSecondary }) { row -> row.owner + if (row.rentDebt > 0) "  (${tr("kami_claims.home.debt", Format.money(row.rentDebt))})" else "" },
            Column<ClaimLine>(tr("kami_claims.chunks.col.claimed"), 64, Align.RIGHT, compareBy { it.at }) { _, c, row -> Draw.textRight(g, if (row.at > 0) Format.ago(row.at) else "", c.right, c.y + 3, Palette.textMuted) }
        ) }
        val events = ui.table(tableArea, columns, rows, table, { "${it.x}:${it.z}" }, multi = can("claim"), severity = { if (it.debt > 0) Severity.DANGER else null }, emptyText = tr("kami_claims.chunks.empty.filtered"))
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
        val row = Row(r.inset(4, 2), 4)
        if (r.w >= 360) {
            Draw.text(ui.g, count, r.x + 8, r.y + (r.h - 8) / 2, TextStyle.HEADING)
            row.take(Draw.width(count, TextStyle.HEADING) + 10)
        }
        val release = tr("kami_claims.chunks.release")
        if (ui.edgeButton(row, release, Icons.REMOVE, ButtonStyle.DANGER, can("claim"), lock("claim"), key = "bulk-release")) {
            val saved = selected.filter { !it.free }.sumOf { price(it) }
            val blocked = selected.filter { it.capital || it.locked.isNotEmpty() }
            Dialogs.confirm(app, tr("kami_claims.chunks.release.confirm.title", trn("kami_claims.unit.chunk", selected.size)), tr("kami_claims.chunks.release.confirm.subtitle"), Icons.REMOVE, listOfNotNull(
                Consequence(tr("kami_claims.chunks.release.open"), Severity.DANGER),
                if (blocked.isNotEmpty()) Consequence(tr("kami_claims.chunks.release.blocked", trn("kami_claims.unit.chunk", blocked.size)), Severity.WARNING) else null,
                Consequence(tr("kami_claims.chunks.release.split")),
                Consequence(tr("kami_claims.chunks.release.saved", Format.perDay(Format.rateMoney(saved))), Severity.SUCCESS)
            ), tr("kami_claims.chunks.release.action"), "unclaimcells", arrayOf("cells", cellsArg(selected)), danger = true, hold = true) { table.clear() }
        }
        if (bulkType.isEmpty()) bulkType = snap.types.firstOrNull()?.name ?: ""
        val retype = tr("kami_claims.chunks.retype")
        val retyped = ui.edgeButton(row, retype, enabled = can("claim"), disabledReason = lock("claim"), key = "bulk-type-go")
        val typeOptions = bulkTypes.of(snap.types, Format.locale, ClientResearch.state, ClientResearch.defs) { snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color, lock = ClientLocks.claimType(it.name)) } }
        ui.select(row.takeFromRight(if (r.w < 360) 90 else 120), typeOptions, bulkType, key = "bulk-type")?.let { bulkType = it }
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
    private val lists = Memo()
    private val plotColumns = Memo()
    private val search = TextState()
    private var category = ""

    private class Lists(val free: List<ClaimLine>, val taken: List<ClaimLine>, val moving: List<ClaimLine>, val debt: Int)

    override fun opened(route: Route) {
        when (route.focus) {
            "free" -> tab = 0
            "moving" -> tab = 2
        }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val l = lists.of(info.claimList, snap.me) {
            val taken = info.claimList.filter { it.owner.isNotEmpty() }
            Lists(info.claimList.filter { it.type == "residential" && it.owner.isEmpty() }, taken, taken.filter { it.state == Tenure.MOVING }, taken.count { it.rentDebt > 0 })
        }
        val tabsRect = r.top(CONTROL_H)
        ui.anchor("plots:tabs", tabsRect)
        ui.subTabs(tabsRect, listOf(
            TabItem(tr("kami_claims.plots.tab.free"), null, l.free.size, Severity.SUCCESS),
            TabItem(tr("kami_claims.plots.tab.all"), null, l.debt, Severity.WARNING, lock("details")),
            TabItem(tr("kami_claims.plots.tab.moving"), null, l.moving.size, Severity.DANGER, lock("details"))
        ), tab, "plot-tabs")?.let { tab = it }
        val me = info.members.firstOrNull { it.id == snap.me }
        val held = me?.plotsHeld ?: 0
        val limit = me?.plotLimit ?: snap.maxPlots
        val plotRaise = ClientLocks.raise(Capacity.PLOTS)?.takeIf { held >= limit }
        val plotChip = plotRaise?.let { lockChipWidth(it) + 4 } ?: 0
        if (r.w >= 420) {
            Draw.textRight(ui.g, tr("kami_claims.plots.rented", Format.number(held), Format.number(limit)), r.right - plotChip, r.y + 5, if (held >= limit) Palette.warning else Palette.textMuted)
            plotRaise?.let { ui.lockChip(r.right - plotChip + 4, r.y + 2, it, "plots-raise") }
        }
        val body = r.dropTop(CONTROL_H + 6)
        ui.anchor("plots:list", body)
        val source = when (tab) { 0 -> l.free; 1 -> l.taken; else -> l.moving }
        if (tab == 0 && source.isEmpty()) return free(ui, body, source, held >= limit, limit)
        val cats = source.map { it.category }.filter { it.isNotEmpty() }.distinct()
        val area = ui.filterBar(body, "plots:filters") { bar ->
            val row = Row(bar, 6)
            ui.select(row.takeFromRight(130), listOf(Option("", tr("kami_libs.common.all"))) + cats.map { Option(it, Tenure.category(it)) }, category, key = "plot-category")?.let { category = it }
            ui.searchField(row.rest, search, tr("kami_claims.chunks.search"), key = "plot-search")
        }
        val shown = ui.filtered("plots-filtered:$tab", listOf(search.text, category, source)) {
            val q = SearchQuery.parse(search.text)
            source.filter { (category.isEmpty() || it.category == category) && q.matches("${it.x}, ${it.z}", "${it.x},${it.z}", it.owner) }
        }
        when (tab) {
            0 -> free(ui, area, shown, held >= limit, limit)
            1 -> list(ui, area, shown, tableAll, true)
            else -> list(ui, area, shown, tableMoving, false)
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
        val columns = plotColumns.of(Format.locale, wide, removable, removeLock) { val removeTip = tr("kami_claims.plots.remove")
        val mapTip = tr("kami_claims.nav.map")
        listOfNotNull(
            Column<ClaimLine>(tr("kami_claims.help.term.plot"), 64, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text) },
            Column.text<ClaimLine>(tr("kami_libs.common.owner"), -1) { it.owner },
            Column<ClaimLine>(tr("kami_libs.common.state"), 84, sort = compareBy { it.state }) { _, c, row ->
                statusPill(c.x, c.y, Tenure.label(row.state), Tenure.severity(row.state, row.rentDebt), key = "p:${row.x}:${row.z}")
            },
            Column<ClaimLine>(tr("kami_claims.plots.col.rent_debt"), 64, Align.RIGHT, compareBy { it.rentDebt * 1000 + it.rent }) { _, c, row ->
                val debt = row.rentDebt > 0
                fitText(c.x, c.y + 3, c.w, if (debt) tr("kami_claims.home.debt", Format.money(row.rentDebt)) else Format.perDay(Format.money(row.rent.toLong())), if (debt) Palette.warning else Palette.textSecondary, align = Align.RIGHT, key = "rd:${row.x}:${row.z}")
            },
            if (wide) Column.text<ClaimLine>(tr("kami_claims.plots.col.category"), 72) { Tenure.category(it.category) } else null,
            if (wide) Column<ClaimLine>(tr("kami_libs.common.time_left"), 60, Align.RIGHT, compareBy { it.until }) { _, c, row ->
                if (row.state == Tenure.MOVING && row.until > 0) fitText(c.x, c.y + 3, c.w, Tenure.note(row, row.state, row.until, 0), Palette.danger, align = Align.RIGHT, key = "tl:${row.x}:${row.z}")
            } else null,
            Column<ClaimLine>("", 34) { _, c, row ->
                val canRemove = removable && removeLock == null && row.state != Tenure.MOVING && outranks(row.ownerId)
                if (canRemove && iconButton(Rect(c.x, c.y + 1, 12, 12), Icons.BAN, removeTip, key = (row.x * 31 + row.z) * 2)) Dialogs.removeTenant(app, row.x, row.z, row.owner)
                if (iconButton(Rect(c.x + 16, c.y + 1, 12, 12), Icons.MAP, mapTip, key = (row.x * 31 + row.z) * 2 + 1)) app.openMapAt(row.x, row.z)
            }
        ) }
        val events = ui.table(r, columns, plots, state, { "${it.x}:${it.z}" }, severity = { if (it.state == Tenure.MOVING) Severity.DANGER else if (it.rentDebt > 0) Severity.WARNING else null }, emptyText = tr("kami_claims.plots.empty.all"), key = "plots:table")
        events.opened?.let { app.openMapAt(it.x, it.z) }
    }
}

private const val BULK_H = 22
