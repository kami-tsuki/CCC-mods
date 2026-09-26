package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.app.consequences
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.net.ClaimLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.Route
import kami.libs.ui.app.dialogButtons
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
    override val title = "Chunks"
    override val help = listOf(
        Callout("chunks:filters", "Filters", "Search coords/owners and filter by type/state."),
        Callout("chunks:table", "Your land", "Click to select, Ctrl/Shift for multi, double-click to open on map."),
        Callout("chunks:bulk", "Bulk actions", "Retype selected chunks or release them.")
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
        c.debt > 0 -> "Debt ${c.debt}/${limits?.maxDebt ?: 3}"
        c.free -> "Free"
        c.locked.isNotEmpty() -> "New"
        else -> "Paid"
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val bar = Row(r.top(20), 6)
        ui.anchor("chunks:filters", r.top(20))
        ui.searchField(bar.take(150), search, "Search coord or owner", key = "chunk-search")
        ui.select(bar.take(130), listOf(Option("all", "All types", Icons.LAYERS)) + snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color) }, type, key = "chunk-type")?.let { type = it }
        ui.segmented(bar.take(200), listOf(Option("all", "All"), Option("debt", "In debt", Icons.DEBT), Option("plots", "Plots", Icons.HOUSE), Option("free", "Free")), state, key = "chunk-state")?.let { state = it }
        val rows = info.claimList.filter { c ->
            (type == "all" || c.type == type) && when (state) {
                "debt" -> c.debt > 0
                "plots" -> c.owner.isNotEmpty()
                "free" -> c.free
                else -> true
            } && (search.text.isBlank() || "${c.x}, ${c.z} ${c.x},${c.z} ${c.owner} ${c.type}".contains(search.text.trim(), true))
        }
        val summary = "${rows.size}/${info.chunks} · ${info.free} free · ${info.claimList.count { it.debt > 0 }} debt · ${info.upkeep} ◎/day"
        Draw.textRight(ui.g, Draw.fit(summary, bar.rest.w), r.right, r.y + 6, Palette.textMuted)
        val selected = rows.filter { "${it.x}:${it.z}" in table.selected }
        val tableArea = if (selected.isNotEmpty()) r.dropTop(24).dropBottom(26, 4) else r.dropTop(24)
        ui.anchor("chunks:table", tableArea)
        if (info.claimList.isEmpty()) {
            if (ui.emptyState(tableArea, "No land yet", "Claim chunks on the map with a cost preview before commit.", Illustrations.FOUND, "Open map", Icons.MAP)) app.navigate(Route("map"))
            return
        }
        val events = ui.table(tableArea, listOf(
            Column<ClaimLine>("Chunk", 80, sort = compareBy({ it.x }, { it.z })) { _, c, row ->
                Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 4, Palette.text)
                if (row.capital) Draw.icon(g, Icons.CROWN, c.right - 12, c.y + 2, 12)
            },
            Column<ClaimLine>("Type", 100, sort = compareBy { it.type }) { _, c, row ->
                val look = Vocabulary.type(row.type)
                Draw.icon(g, look.icon, c.x - 1, c.y + 1, 14)
                Draw.text(g, Draw.fit(look.label, c.w - 15), c.x + 14, c.y + 4, look.color)
            },
            Column<ClaimLine>("Upkeep", 56, Align.RIGHT, compareBy { price(it) }) { _, c, row -> Draw.textRight(g, if (row.free) "free" else "%.1f ◎".format(price(row)), c.right, c.y + 4, Palette.textSecondary) },
            Column<ClaimLine>("State", 76, sort = compareBy { -it.debt }) { _, c, row ->
                val sev = when { row.debt > 0 -> Severity.DANGER; row.free -> Severity.SUCCESS; row.locked.isNotEmpty() -> Severity.INFO; else -> Severity.NEUTRAL }
                statusPill(c.x, c.y + 1, status(row), sev, row.locked.ifEmpty { null }, key = "st:${row.x}:${row.z}")
            },
            Column.text<ClaimLine>("Plot owner", -1, color = { if (it.lapse > 0) Palette.danger else Palette.textSecondary }) { row -> row.owner.ifEmpty { "—" } + if (row.lapse > 0) "  (${row.lapse}d unpaid)" else "" },
            Column<ClaimLine>("Claimed", 64, Align.RIGHT, compareBy { it.at }) { _, c, row -> Draw.textRight(g, if (row.at > 0) Format.ago(row.at) else "", c.right, c.y + 4, Palette.textMuted) }
        ), rows, table, { "${it.x}:${it.z}" }, multi = can("claim"), severity = { if (it.debt > 0) Severity.DANGER else null }, emptyText = "No chunks match these filters.")
        events.opened?.let { app.openMapAt(it.x, it.z) }
        if (selected.isNotEmpty()) bulk(ui, r.bottom(24), selected)
    }

    private fun price(c: ClaimLine): Double {
        val t = snap.types.firstOrNull { it.name == c.type } ?: return 0.0
        return t.price.toDouble() / max(1, t.period)
    }

    private fun bulk(ui: Ui, r: Rect, selected: List<ClaimLine>) {
        ui.anchor("chunks:bulk", r)
        Draw.fill(ui.g, r, Palette.selected)
        Draw.fill(ui.g, r.left(2), Palette.brass)
        Draw.text(ui.g, "${Format.plural(selected.size, "chunk")} selected", r.x + 8, r.y + 8, TextStyle.HEADING)
        val row = Row(r.inset(4, 2), 4)
        row.take(Draw.width("${Format.plural(selected.size, "chunk")} selected", TextStyle.HEADING) + 10)
        if (ui.button(row.takeFromRight(buttonWidth("Release…", Icons.REMOVE)), "Release…", Icons.REMOVE, ButtonStyle.DANGER, can("claim"), lock("claim"), key = "bulk-release")) {
            val saved = selected.filter { !it.free }.sumOf { price(it) }
            val blocked = selected.filter { it.capital || it.locked.isNotEmpty() }
            Dialogs.confirm(app, "Release ${Format.plural(selected.size, "chunk")}", "to nomansland", Icons.REMOVE, listOfNotNull(
                Consequence("Upkeep saved: ${"%.1f".format(saved)} ◎/day.", Severity.SUCCESS),
                Consequence("Other countries can claim immediately.", Severity.DANGER),
                if (blocked.isNotEmpty()) Consequence("${blocked.size} blocked: capital or <24h chunk.", Severity.WARNING) else null,
                Consequence("Splitting chunks are kept.")
            ), "Release land", "unclaimcells", arrayOf("cells", cellsArg(selected)), danger = true, hold = true) { table.clear() }
        }
        if (bulkType.isEmpty()) bulkType = snap.types.firstOrNull()?.name ?: ""
        val apply = row.takeFromRight(buttonWidth("Set type"))
        val typeRect = row.takeFromRight(120)
        ui.select(typeRect, snap.types.map { Option(it.name, Vocabulary.type(it.name).label, Vocabulary.type(it.name).icon, null, Vocabulary.type(it.name).color) }, bulkType, key = "bulk-type")?.let { bulkType = it }
        if (ui.button(apply, "Set type", enabled = can("claim"), disabledReason = lock("claim"), key = "bulk-type-go")) {
            val t = snap.types.firstOrNull { it.name == bulkType }
            val delta = selected.filter { !it.free }.sumOf { (t?.let { it.price.toDouble() / max(1, it.period) } ?: 0.0) - price(it) }
            val losingPlots = selected.count { it.owner.isNotEmpty() && bulkType != "residential" }
            Dialogs.confirm(app, "Retype ${Format.plural(selected.size, "chunk")} to ${Vocabulary.type(bulkType).label}", null, Icons.EDIT, listOfNotNull(
                Consequence("Upkeep delta: ${"%+.1f".format(delta)} ◎/day.", if (delta > 0) Severity.WARNING else Severity.SUCCESS),
                if (losingPlots > 0) Consequence("$losingPlots plot owners lose plots.", Severity.DANGER) else null,
                Consequence("${Vocabulary.type(bulkType).label} rules apply immediately.")
            ), "Retype", "typecells", arrayOf(bulkType, "cells", cellsArg(selected))) { table.clear() }
        }
        if (ui.button(row.takeFromRight(buttonWidth("Show on map", Icons.MAP)), "Show on map", Icons.MAP, key = "bulk-map")) selected.firstOrNull()?.let { app.openMapAt(it.x, it.z) }
    }
}

class PlotsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Plots"
    override val help = listOf(
        Callout("plots:tabs", "Plots", "My plots, free plots, and all plots for leaders."),
        Callout("plots:list", "Plot cards", "Tax, payment state, and access at a glance.")
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
        val tabsRect = r.top(20)
        ui.anchor("plots:tabs", tabsRect)
        ui.subTabs(tabsRect, listOf(
            TabItem("My plots", Icons.HOUSE, mine.size, Severity.INFO),
            TabItem("Free to rent", Icons.ADD, free.size, Severity.SUCCESS),
            TabItem("All plots", Icons.LEDGER, taken.count { it.lapse > 0 }, Severity.WARNING, lock("details"))
        ), tab, "plot-tabs")?.let { tab = it }
        Draw.textRight(ui.g, "Rented: ${mine.size}/${snap.maxPlots}", r.right, r.y + 6, Palette.textMuted)
        val body = r.dropTop(26)
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
        fun st(threshold: Int) = when {
            c.lapse >= threshold -> StepState.DANGER
            c.lapse > 0 && c.lapse < threshold -> StepState.CURRENT
            else -> StepState.PENDING
        }
        return listOf(
            Step("Paid", if (c.lapse == 0) "today" else "no", if (c.lapse == 0) StepState.DONE else StepState.DANGER),
            Step("Locked", "after ${shut}d", st(shut)),
            Step("Lost", "after ${total}d", st(total))
        )
    }

    private fun mine(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        if (plots.isEmpty()) {
            if (ui.emptyState(r, "No rented plots", "Rent residential chunks as plots. You pay daily tax and control access.", Illustrations.CITIZENS, "See free plots", Icons.HOUSE)) tab = 1
            return
        }
        val focus = focusPlot?.takeIf { f -> plots.any { it.x == f.first && it.z == f.second } } ?: (plots.first().x to plots.first().z).also { focusPlot = it }
        val (list, detail) = r.columns(listOf(1f, 1.3f), 8)
        ui.scroll("my-plots", list, plots.size * 64) { area ->
            plots.forEachIndexed { i, c ->
                val card = Rect(area.x, area.y + i * 64, area.w, 60)
                val chosen = focus == c.x to c.z
                Draw.sprite(ui.g, if (chosen) Sprites.CARD_HOVER else Sprites.CARD, card)
                if (chosen) Draw.fill(ui.g, card.left(2), Palette.brass)
                ui.attention(card, app.isFocus("${c.x}:${c.z}"))
                Draw.text(ui.g, "Plot ${c.x}, ${c.z}", card.x + 8, card.y + 6, TextStyle.HEADING)
                Draw.textRight(ui.g, "${c.tax} ◎/day", card.right - 6, card.y + 6, Palette.money)
                ui.timeline(Rect(card.x + 10, card.y + 20, card.w - 20, 34), lapseSteps(c))
                if (ui.pressed(card) != null) { focusPlot = c.x to c.z; ClaimsStore.quiet("chunk", c.x.toString(), c.z.toString()) }
            }
        }
        val c = plots.first { it.x == focus.first && it.z == focus.second }
        val body = ui.card(detail, "Plot ${c.x}, ${c.z}", Icons.HOUSE, if (c.lapse > 0) Severity.WARNING else null)
        val f = Flow(body, 4)
        val top = f.take(80)
        MiniMap.draw(ui, top.left(80), c.x, c.z, 2, "plot", listOf(c.x to c.z))
        val props = Flow(top.dropLeft(88), 2)
        ui.property(props.take(11), "Tax", "${c.tax} ◎ per day")
        ui.property(props.take(11), "Unpaid", if (c.lapse == 0) "no" else "${c.lapse} days", if (c.lapse > 0) Palette.danger else Palette.success)
        ui.property(props.take(11), "Trusted players", "${c.roles}")
        if (ui.link(props.rest.x, props.rest.y + 4, "Show on map", key = "plot-map")) app.openMapAt(c.x, c.z)
        val d = snap.detail?.takeIf { it.x == c.x && it.z == c.z }
        ui.section(f.take(14), "Plot access")
        if (d == null) ClaimsStore.quiet("chunk", c.x.toString(), c.z.toString())
        val roles = d?.roles ?: emptyList()
        if (roles.isEmpty()) f.take(12).let { Draw.text(ui.g, "Only you. Add players below.", it.x, it.y, Palette.textMuted) }
        roles.forEach { line ->
            val row = f.take(14)
            val name = line.substringBefore(":")
            val role = line.substringAfter(": ")
            Draw.text(ui.g, name, row.x, row.y + 3, Palette.text)
            ui.chip(row.x + 100, row.y, role, Vocabulary.rank(if (role == "household") "citizen" else role).color)
            if (ui.iconButton(Rect(row.right - 16, row.y - 1, 16, 16), Icons.REMOVE, "Remove $name", key = "untrust:$name")) act("plot_untrust", name, c.x.toString(), c.z.toString(), key = "plot_untrust")
        }
        f.skip(4)
        ui.section(f.take(14), "Add a player")
        val roles2 = listOf(
            Option("household", "Household", Icons.HOUSE, "Full build/use access like owner."),
            Option("allied", "Guest", Icons.HANDSHAKE, "Can use doors/buttons, no build."),
            Option("banished", "Banned", Icons.BAN, "No entry.")
        )
        val entry = f.take(CONTROL_H)
        ui.textField(entry.left(entry.w - 90), trustName, "Player name", Icons.PERSON, maxLength = 16, key = "trust-name")
        if (ui.button(entry.right(86), "Add", Icons.ADD, ButtonStyle.PRIMARY, trustName.text.length >= 3, "Enter a player name", pending = pending("plot_trust"), key = "trust-go")) {
            act("plot_trust", trustName.text, trustRole, c.x.toString(), c.z.toString(), key = "plot_trust")
            trustName.set("")
        }
        ui.radioGroup(f.take(radioGroupHeight(roles2, body.w)), roles2, trustRole, key = "trust-role")?.let { trustRole = it }
        val bottom = f.rest
        if (ui.button(Rect(bottom.x, bottom.bottom - CONTROL_H, bottom.w, CONTROL_H), "Give up this plot", Icons.REMOVE, ButtonStyle.DANGER, key = "plot-release")) {
            Dialogs.confirm(app, "Give up plot ${c.x}, ${c.z}", null, Icons.HOUSE, listOf(
                Consequence("You stop paying its tax."),
                Consequence("Everyone you trusted loses access.", Severity.WARNING),
                Consequence("Anyone in your country may rent it afterwards.", Severity.WARNING)
            ), "Give up plot", "plot_release", arrayOf(c.x.toString(), c.z.toString()), danger = true, hold = true)
        }
    }

    private fun free(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        if (plots.isEmpty()) {
            ui.emptyState(r, "No free plots", "Leaders must mark chunks as residential before renting.", Illustrations.CITIZENS)
            return
        }
        val events = ui.table(r.dropBottom(26, 4), listOf(
            Column<ClaimLine>("Plot", 80, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 4, Palette.text) },
            Column.number<ClaimLine>("Tax / day", 70) { (if (it.tax >= 0) it.tax else info?.tax ?: 0).toLong() },
            Column.number<ClaimLine>("Distance", 70, format = { "$it chunks" }) { (abs(it.x - snap.px) + abs(it.z - snap.pz)).toLong() },
            Column.text<ClaimLine>("", -1, sortable = false) { "" }
        ), plots, tableFree, { "${it.x}:${it.z}" })
        events.opened?.let { app.openMapAt(it.x, it.z) }
        val chosen = plots.firstOrNull { "${it.x}:${it.z}" in tableFree.selected }
        val bar = r.bottom(22)
        val lockReason = lock("plot") ?: if ((info?.plots ?: 0) >= snap.maxPlots) "Plot limit reached (${snap.maxPlots})" else null
        val w = buttonWidth("Rent selected plot", Icons.HOUSE)
        if (ui.button(Rect(bar.right - w, bar.y, w, CONTROL_H), "Rent selected plot", Icons.HOUSE, ButtonStyle.PRIMARY, chosen != null && lockReason == null, lockReason ?: "Select a plot first", key = "rent")) chosen?.let { c ->
            val tax = if (c.tax >= 0) c.tax else info?.tax ?: 0
            Dialogs.confirm(app, "Rent plot ${c.x}, ${c.z}", "Residential plot", Icons.HOUSE, listOf(
                Consequence("Tax: $tax ◎/day from your bank."),
                Consequence("No payment: lock after ${info?.shutdown}d, release after ${info?.release}d.", Severity.WARNING),
                Consequence("You control plot access.", Severity.SUCCESS)
            ), "Rent for $tax ◎ / day", "plot_claim", arrayOf(c.x.toString(), c.z.toString()))
        }
        if (ui.button(Rect(bar.right - w - 90, bar.y, 86, CONTROL_H), "Show on map", Icons.MAP, enabled = chosen != null, disabledReason = "Select a plot first", key = "free-map")) chosen?.let { app.openMapAt(it.x, it.z) }
    }

    private fun all(ui: Ui, r: Rect, plots: List<ClaimLine>) {
        val events = ui.table(r.dropBottom(26, 4), listOf(
            Column<ClaimLine>("Plot", 70, sort = compareBy({ it.x }, { it.z })) { _, c, row -> Draw.text(g, "${row.x}, ${row.z}", c.x, c.y + 4, Palette.text) },
            Column.text<ClaimLine>("Owner", -1) { it.owner },
            Column.number<ClaimLine>("Tax / day", 64) { it.tax.toLong() },
            Column<ClaimLine>("Unpaid", 80, sort = compareBy { it.lapse }) { _, c, row ->
                if (row.lapse == 0) statusPill(c.x, c.y + 1, "Paid", Severity.SUCCESS, key = "p:${row.x}:${row.z}")
                else statusPill(c.x, c.y + 1, "${row.lapse}d", if (row.lapse >= (info?.shutdown ?: 3)) Severity.DANGER else Severity.WARNING, key = "p:${row.x}:${row.z}")
            },
            Column.number<ClaimLine>("Trusted", 50) { it.roles.toLong() }
        ), plots, tableAll, { "${it.x}:${it.z}" }, severity = { if (it.lapse > 0) Severity.WARNING else null }, emptyText = "No rented plots yet.")
        events.opened?.let { app.openMapAt(it.x, it.z) }
        val chosen = plots.firstOrNull { "${it.x}:${it.z}" in tableAll.selected }
        val bar = Row(r.bottom(22), 6)
        if (ui.button(bar.takeFromRight(buttonWidth("Evict owner…", Icons.BAN)), "Evict owner…", Icons.BAN, ButtonStyle.DANGER, chosen != null && can("claim"), lock("claim") ?: "Select a plot first", key = "evict")) chosen?.let { c ->
            Dialogs.confirm(app, "Evict ${c.owner}", "from plot ${c.x}, ${c.z}", Icons.BAN, listOf(
                Consequence("${c.owner} loses the plot and everyone they trusted loses access.", Severity.DANGER),
                Consequence("Their builds stay, but they can't change them anymore.", Severity.WARNING)
            ), "Evict", "plot_evict", arrayOf(c.x.toString(), c.z.toString()), danger = true, hold = true)
        }
        if (ui.button(bar.takeFromRight(buttonWidth("Set tax…", Icons.TAX)), "Set tax…", Icons.TAX, enabled = chosen != null && can("tax"), disabledReason = lock("tax") ?: "Select a plot first", key = "plot-tax")) chosen?.let { taxDialog(it) }
    }

    private fun taxDialog(c: ClaimLine) {
        val amount = NumberState(c.tax.toLong().coerceAtLeast(0))
        app.open(Dialog("Tax for plot ${c.x}, ${c.z}", "Rented by ${c.owner}", Icons.TAX, DialogKind.CONFIRM, 280) { s ->
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Tax per day"); y += 11
            numberField(Rect(s.body.x, y, s.body.w, CONTROL_H), amount, 0, 10_000, unit = "◎", key = "tax")
            y += CONTROL_H + 6
            y += consequences(s.body.x, y, s.body.w, listOf(Consequence("Overrides the country plot tax for this plot only."), Consequence("${c.owner} pays it from the next billing on."))) + 2
            s.used = y - s.body.y
            dialogButtons(s, "Save tax", amount.text.error == null) {
                ClaimsStore.send("plot_tax", amount.value.toString(), c.x.toString(), c.z.toString(), key = "plot_tax")
                s.close()
            }
        })
    }
}
