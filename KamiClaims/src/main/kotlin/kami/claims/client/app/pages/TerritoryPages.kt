package kami.claims.client.app.pages

import kami.libs.ui.text.trJson
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClientLocks
import kami.claims.research.Capacity
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.libs.ui.app.consequences
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.net.ClaimLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.Route
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.numberDialogBody
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

private fun lapseState(lapse: Int, threshold: Int) = when {
    lapse >= threshold -> StepState.DANGER
    lapse > 0 -> StepState.CURRENT
    else -> StepState.PENDING
}

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
        else -> tr("kami_claims.chunks.status.paid")
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        var rows: List<ClaimLine> = emptyList()
        val afterBar = ui.filterBar(r, "chunks:filters") { barRect ->
            val bar = Row(barRect, 6)
            ui.searchField(bar.take(150), search, tr("kami_claims.chunks.search"), key = "chunk-search")
            ui.select(bar.take(130), listOf(Option("all", tr("kami_claims.chunks.filter.all_types"), Icons.LAYERS)) + snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color) }, type, key = "chunk-type")?.let { type = it }
            ui.segmented(bar.take(200), listOf(Option("all", tr("kami_claims.chunks.filter.all")), Option("debt", tr("kami_claims.stats.in_debt"), Icons.DEBT), Option("plots", tr("kami_claims.nav.plots"), Icons.HOUSE), Option("free", tr("kami_claims.chunks.status.free"))), state, key = "chunk-state")?.let { state = it }
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
                if (row.capital) Draw.leadIcon(g, Icons.CROWN, c.right - Draw.ICON_SLOT, c.centerY)
            },
            Column<ClaimLine>(tr("kami_claims.chunks.col.type"), 100, sort = compareBy { it.type }) { _, c, row ->
                val look = Vocabulary.type(row.type)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                Draw.text(g, Draw.fit(look.label, c.right - x), x, c.y + 3, look.color)
            },
            Column<ClaimLine>(tr("kami_claims.ledger.upkeep"), 56, Align.RIGHT, compareBy { price(it) }) { _, c, row -> Draw.textRight(g, if (row.free) tr("kami_claims.chunks.status.free") else tr("kami_libs.unit.money", Format.decimal(price(row))), c.right, c.y + 3, Palette.textSecondary) },
            Column<ClaimLine>(tr("kami_claims.chunks.col.state"), 76, sort = compareBy { -it.debt }) { _, c, row ->
                val sev = when { row.debt > 0 -> Severity.DANGER; row.free -> Severity.SUCCESS; row.locked.isNotEmpty() -> Severity.INFO; else -> Severity.NEUTRAL }
                statusPill(c.x, c.y, status(row), sev, row.locked.ifEmpty { null }?.let(::trJson), key = "st:${row.x}:${row.z}")
            },
            Column.text<ClaimLine>(tr("kami_claims.chunks.col.tenant"), -1, color = { if (it.lapse > 0) Palette.danger else Palette.textSecondary }) { row -> row.owner + if (row.lapse > 0) "  (${tr("kami_claims.plot.unpaid", Format.days(row.lapse.toLong()))})" else "" },
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
    private var focusPlot: Pair<Int, Int>? = null
    private val trustName = TextState()
    private var trustRole = "household"

    override fun opened(route: Route) {
        val f = route.focus ?: return
        if (f == "lapse") tab = 2
        f.split(':').takeIf { it.size == 2 }?.let { (x, z) -> focusPlot = (x.toIntOrNull() ?: return@let) to (z.toIntOrNull() ?: return@let); tab = 0 }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val mine = info.claimList.filter { it.ownerId == snap.me }
        val free = info.claimList.filter { it.type == "residential" && it.owner.isEmpty() }
        val taken = info.claimList.filter { it.owner.isNotEmpty() }
        val tabsRect = r.top(CONTROL_H)
        ui.anchor("plots:tabs", tabsRect)
        ui.subTabs(tabsRect, listOf(
            TabItem(tr("kami_claims.plots.tab.mine"), Icons.HOUSE, mine.size, Severity.INFO),
            TabItem(tr("kami_claims.plots.tab.free"), Icons.ADD, free.size, Severity.SUCCESS),
            TabItem(tr("kami_claims.plots.tab.all"), Icons.LEDGER, taken.count { it.lapse > 0 }, Severity.WARNING, lock("details"))
        ), tab, "plot-tabs")?.let { tab = it }
        val plotRaise = ClientLocks.raise(Capacity.PLOTS)?.takeIf { mine.size >= snap.maxPlots }
        val plotChip = plotRaise?.let { lockChipWidth(it) + 4 } ?: 0
        Draw.textRight(ui.g, tr("kami_claims.plots.rented", Format.number(mine.size), Format.number(snap.maxPlots)), r.right - plotChip, r.y + 5, if (plotRaise == null) Palette.textMuted else Palette.warning)
        plotRaise?.let { ui.lockChip(r.right - plotChip + 4, r.y + 2, it, "plots-raise") }
        val body = r.dropTop(CONTROL_H + 6)
        ui.anchor("plots:list", body)
        when (tab) {
            0 -> mine(ui, body, mine)
            1 -> free(ui, body, free)
            else -> all(ui, body, taken)
        }
    }

    private fun lapseSteps(c: ClaimLine): List<Step> {
        val info = info!!
        val shut = info.shutdown
        val total = info.shutdown + info.release
        return listOf(
            Step(tr("kami_claims.chunks.status.paid"), tr(if (c.lapse == 0) "kami_claims.plots.step.today" else "kami_claims.plots.step.overdue"), if (c.lapse == 0) StepState.DONE else StepState.DANGER),
            Step(tr("kami_claims.plots.step.locked"), tr("kami_claims.plots.step.after", Format.days(shut.toLong())), lapseState(c.lapse, shut)),
            Step(tr("kami_claims.plots.step.lost"), tr("kami_claims.plots.step.after", Format.days(total.toLong())), lapseState(c.lapse, total))
        )
    }

    private fun mine(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        if (plots.isEmpty()) {
            if (ui.emptyState(r, tr("kami_claims.plots.empty.mine.title"), tr("kami_claims.plots.empty.mine.desc"), Illustrations.CITIZENS, tr("kami_claims.plots.empty.mine.action"), Icons.HOUSE)) tab = 1
            return
        }
        val focus = focusPlot?.takeIf { f -> plots.any { it.x == f.first && it.z == f.second } } ?: (plots.first().x to plots.first().z).also { focusPlot = it }
        val (list, detail) = r.columns(listOf(1f, 1.3f), 8)
        ui.scroll("my-plots", list, plots.size * (PLOT_CARD_H + 4)) { area ->
            plots.forEachIndexed { i, c ->
                val card = Rect(area.x, area.y + i * (PLOT_CARD_H + 4), area.w, PLOT_CARD_H)
                val chosen = focus == c.x to c.z
                Draw.sprite(ui.g, if (chosen) Sprites.CARD_HOVER else Sprites.CARD, card)
                if (chosen) Draw.fill(ui.g, card.left(2), Palette.brass)
                ui.attention(card, app.isFocus("${c.x}:${c.z}"))
                Draw.text(ui.g, tr("kami_claims.plot.at", c.x, c.z), card.x + 6, card.y + 5, TextStyle.HEADING)
                Draw.textRight(ui.g, Format.perDay(Format.money(c.tax.toLong())), card.right - 6, card.y + 5, Palette.money)
                ui.timeline(Rect(card.x + 8, card.y + 17, card.w - 16, 34), lapseSteps(c))
                if (ui.pressed(card) != null) { focusPlot = c.x to c.z; ClaimsStore.quiet("chunk", c.x.toString(), c.z.toString()) }
            }
        }
        val c = plots.first { it.x == focus.first && it.z == focus.second }
        val body = ui.card(detail, tr("kami_claims.plot.at", c.x, c.z), Icons.HOUSE, if (c.lapse > 0) Severity.WARNING else null)
        val f = Flow(body, 4)
        val top = f.take(80)
        MiniMap.draw(ui, top.left(80), c.x, c.z, 2, "plot", listOf(c.x to c.z))
        val props = Flow(top.dropLeft(88), 2)
        ui.property(props.take(11), tr("kami_claims.plots.tax"), Format.perDay(Format.money(c.tax.toLong())))
        ui.property(props.take(11), tr("kami_claims.plots.unpaid"), if (c.lapse == 0) tr("kami_claims.plots.unpaid.none") else trn("kami_claims.unit.day", c.lapse), if (c.lapse > 0) Palette.danger else Palette.success)
        ui.property(props.take(11), tr("kami_claims.plots.trusted"), Format.number(c.roles))
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
            if (ui.iconButton(Rect(row.right - 16, row.y - 1, 16, 16), Icons.REMOVE, tr("kami_claims.plots.access.remove.tooltip", name), key = "untrust:$name")) act("plot_untrust", name, c.x.toString(), c.z.toString(), key = "plot_untrust")
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
        if (ui.button(entry.right(86), tr("kami_claims.plots.access.add.action"), Icons.ADD, ButtonStyle.PRIMARY, trustName.text.length >= 3, tr("kami_claims.field.player.disabled"), pending = pending("plot_trust"), key = "trust-go")) {
            act("plot_trust", trustName.text, trustRole, c.x.toString(), c.z.toString(), key = "plot_trust")
            trustName.set("")
        }
        ui.radioGroup(f.take(radioGroupHeight(roles2, body.w)), roles2, trustRole, key = "trust-role")?.let { trustRole = it }
        val bottom = f.rest
        if (ui.button(Rect(bottom.x, bottom.bottom - CONTROL_H, bottom.w, CONTROL_H), tr("kami_claims.plots.release"), Icons.REMOVE, ButtonStyle.DANGER, key = "plot-release")) {
            Dialogs.confirm(app, tr("kami_claims.plots.release.confirm.title", c.x, c.z), null, Icons.HOUSE, listOf(
                Consequence(tr("kami_claims.plots.release.access"), Severity.WARNING),
                Consequence(tr("kami_claims.plots.release.open"), Severity.WARNING),
                Consequence(tr("kami_claims.plots.release.tax"))
            ), tr("kami_claims.plots.release.action"), "plot_release", arrayOf(c.x.toString(), c.z.toString()), danger = true, hold = true)
        }
    }

    private fun free(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        if (plots.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.plots.empty.free.title"), tr("kami_claims.plots.empty.free.desc"), Illustrations.CITIZENS)
            return
        }
        val events = ui.table(r.dropBottom(26, 4), listOf(
            Column<ClaimLine>(tr("kami_claims.plots.col.plot"), 80, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text) },
            Column.number<ClaimLine>(tr("kami_claims.plots.col.tax"), 70) { (if (it.tax >= 0) it.tax else info?.tax ?: 0).toLong() },
            Column.number<ClaimLine>(tr("kami_claims.plots.col.distance"), 70, format = { trn("kami_claims.unit.chunk", it) }) { (abs(it.x - snap.px) + abs(it.z - snap.pz)).toLong() },
            Column.text<ClaimLine>("", -1, sortable = false) { "" }
        ), plots, tableFree, { "${it.x}:${it.z}" })
        events.opened?.let { app.openMapAt(it.x, it.z) }
        val chosen = plots.firstOrNull { "${it.x}:${it.z}" in tableFree.selected }
        val bar = r.bottom(22)
        val lockReason = lock("plot") ?: if ((info?.plots ?: 0) >= snap.maxPlots) tr("kami_claims.plots.limit", Format.number(snap.maxPlots)) else null
        val row = Row(Rect(bar.x, bar.y, bar.w, CONTROL_H))
        if (ui.edgeButton(row, tr("kami_claims.plots.rent"), Icons.HOUSE, ButtonStyle.PRIMARY, chosen != null && lockReason == null, lockReason ?: tr("kami_claims.plots.select_first"), key = "rent")) chosen?.let { c ->
            val tax = Format.perDay(Format.money((if (c.tax >= 0) c.tax else info?.tax ?: 0).toLong()))
            Dialogs.confirm(app, tr("kami_claims.plots.rent.confirm.title", c.x, c.z), tr("kami_claims.chunk_type.residential"), Icons.HOUSE, listOf(
                Consequence(tr("kami_claims.plots.rent.tax", tax)),
                Consequence(tr("kami_claims.plots.rent.lapse", Format.days((info?.shutdown ?: 0).toLong()), Format.days((info?.release ?: 0).toLong())), Severity.WARNING),
                Consequence(tr("kami_claims.plots.rent.access"), Severity.SUCCESS)
            ), tr("kami_claims.plots.rent.action", tax), "plot_claim", arrayOf(c.x.toString(), c.z.toString()))
        }
        if (ui.edgeButton(row, tr("kami_claims.toast.show_on_map"), Icons.MAP, enabled = chosen != null, disabledReason = tr("kami_claims.plots.select_first"), key = "free-map")) chosen?.let { app.openMapAt(it.x, it.z) }
    }

    private fun all(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        val events = ui.table(r.dropBottom(26, 4), listOf(
            Column<ClaimLine>(tr("kami_claims.plots.col.plot"), 70, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 3, Palette.text) },
            Column.text<ClaimLine>(tr("kami_claims.chunks.col.tenant"), -1) { it.owner },
            Column.number<ClaimLine>(tr("kami_claims.plots.col.tax"), 64) { it.tax.toLong() },
            Column<ClaimLine>(tr("kami_claims.plots.col.unpaid"), 80, sort = compareBy { it.lapse }) { _, c, row ->
                if (row.lapse == 0) statusPill(c.x, c.y, tr("kami_claims.chunks.status.paid"), Severity.SUCCESS, key = "p:${row.x}:${row.z}")
                else statusPill(c.x, c.y, Format.days(row.lapse.toLong()), if (lapseState(row.lapse, info?.shutdown ?: 3) == StepState.DANGER) Severity.DANGER else Severity.WARNING, key = "p:${row.x}:${row.z}")
            },
            Column.number<ClaimLine>(tr("kami_claims.plots.col.trusted"), 50) { it.roles.toLong() }
        ), plots, tableAll, { "${it.x}:${it.z}" }, severity = { if (it.lapse > 0) Severity.WARNING else null }, emptyText = tr("kami_claims.plots.empty.all"))
        events.opened?.let { app.openMapAt(it.x, it.z) }
        val chosen = plots.firstOrNull { "${it.x}:${it.z}" in tableAll.selected }
        val bar = Row(r.bottom(22), 6)
        val evict = tr("kami_claims.plots.evict")
        if (ui.edgeButton(bar, evict, Icons.BAN, ButtonStyle.DANGER, chosen != null && can("claim"), lock("claim") ?: tr("kami_claims.plots.select_first"), key = "evict")) chosen?.let { c ->
            Dialogs.confirm(app, tr("kami_claims.plots.evict.confirm.title", c.owner), tr("kami_claims.plot.at", c.x, c.z), Icons.BAN, listOf(
                Consequence(tr("kami_claims.plots.evict.loses", c.owner), Severity.DANGER),
                Consequence(tr("kami_claims.plots.evict.builds"), Severity.WARNING)
            ), tr("kami_claims.plots.evict.action"), "plot_evict", arrayOf(c.x.toString(), c.z.toString()), danger = true, hold = true)
        }
        val setTax = tr("kami_claims.plots.set_tax")
        if (ui.edgeButton(bar, setTax, Icons.TAX, enabled = chosen != null && can("tax"), disabledReason = lock("tax") ?: tr("kami_claims.plots.select_first"), key = "plot-tax")) chosen?.let { taxDialog(it) }
    }

    private fun taxDialog(c: ClaimLine) {
        val amount = NumberState(c.tax.toLong().coerceAtLeast(0))
        app.open(Dialog(tr("kami_claims.plots.tax_dialog.title", c.x, c.z), tr("kami_claims.plots.tax_dialog.subtitle", c.owner), Icons.TAX, DialogKind.CONFIRM, 280) { s ->
            var y = s.body.y
            y += numberDialogBody(s, y, amount, tr("kami_claims.plots.tax_dialog.field"), 0, 10_000, unit = "◎", key = "tax")
            y += consequences(s.body.x, y, s.body.w, listOf(Consequence(tr("kami_claims.plots.tax_dialog.override")), Consequence(tr("kami_claims.plots.tax_dialog.from", c.owner)))) + 2
            s.used = y - s.body.y
            dialogButtons(s, tr("kami_claims.plots.tax_dialog.action"), amount.text.error == null) {
                ClaimsStore.send("plot_tax", amount.value.toString(), c.x.toString(), c.z.toString(), key = "plot_tax")
                s.close()
            }
        })
    }
}

private const val BULK_H = 22
private const val PLOT_CARD_H = 54
