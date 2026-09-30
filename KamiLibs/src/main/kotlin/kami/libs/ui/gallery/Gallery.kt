package kami.libs.ui.gallery

import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.Tour
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.wizardButtons
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

class Gallery : KamiApp() {
    override val home = Route("buttons")
    private val pages = mapOf(
        "buttons" to ButtonsPage(this), "inputs" to InputsPage(), "display" to DisplayPage(), "data" to DataPage(), "overlays" to OverlaysPage(this)
    )

    override fun nav() = listOf(
        NavGroup("Controls", listOf(NavItem("buttons", "Buttons", Icons.CURSOR), NavItem("inputs", "Inputs", Icons.EDIT, { NavBadge(2, Severity.INFO) }))),
        NavGroup("Content", listOf(NavItem("display", "Display", Icons.DASHBOARD), NavItem("data", "Data", Icons.STATS))),
        NavGroup("Layers", listOf(NavItem("overlays", "Overlays", Icons.LAYERS), NavItem("locked", "Locked page", Icons.LOCK, lock = { "Example of a page you cannot open yet" })))
    )

    override fun create(id: String): Page = pages[id] ?: pages.getValue("buttons")

    override fun topBar(ui: Ui, r: Rect) {
        Draw.icon(ui.g, Icons.LAYERS, r.x + 6, r.y + 3)
        Draw.text(ui.g, "KAMI UI GALLERY", r.x + 26, r.y + 9, TextStyle.TITLE, Palette.brass)
        val tile = Rect(r.right - 150, r.y + 3, 120, 18)
        ui.money(tile.x, tile.y + 5, 12_480)
        if (ui.iconButton(Rect(r.right - 22, r.y + 3, 18, 18), Icons.CLOSE, "Close", key = "gallery-close")) Minecraft.getInstance().setScreen(null)
    }

    companion object {
        fun open() = Minecraft.getInstance().setScreen(AppScreen(Gallery(), Component.literal("Kami UI Gallery")))
    }
}

private class ButtonsPage(val app: Gallery) : Page() {
    override val title = "Buttons"
    override val help = listOf(Callout("content", "Components", "Every button style in every state. Hover, press and tab through them."))
    private var pending = false

    override fun draw(ui: Ui, r: Rect) {
        val f = Flow(r, 8)
        ui.section(f.take(14), "Styles")
        val row = f.take(CONTROL_H).columns(4, 6)
        ui.button(row[0], "Primary", Icons.CHECK, ButtonStyle.PRIMARY)
        ui.button(row[1], "Secondary", Icons.EDIT)
        ui.button(row[2], "Danger", Icons.DANGER, ButtonStyle.DANGER)
        ui.button(row[3], "Ghost", style = ButtonStyle.GHOST)
        ui.section(f.take(14), "States")
        val states = f.take(CONTROL_H).columns(4, 6)
        ui.button(states[0], "Disabled", enabled = false, disabledReason = "Needs Chancellor or higher")
        if (ui.button(states[1], "Pending", pending = pending, key = "pending")) pending = true
        ui.button(states[2], "With tooltip", tip = "Every icon-only control has a tooltip.")
        if (ui.holdButton(states[3], "Hold to confirm")) app.toast(Severity.SUCCESS, "Confirmed", "The hold completed.")
        ui.section(f.take(14), "Icon buttons and links")
        val icons = f.take(CONTROL_H)
        listOf(Icons.MAP, Icons.SEARCH, Icons.FILTER, Icons.SETTINGS, Icons.HELP).forEachIndexed { i, icon ->
            ui.iconButton(Rect(icons.x + i * 22, icons.y, 20, 20), icon, icon.sprite.path, selected = i == 0)
        }
        if (ui.link(icons.x + 130, icons.y + 6, "Open the overlays page")) app.navigate(Route("overlays"))
        ui.section(f.take(14), "Key hints")
        ui.keyHints(r.x, f.take(14).y, listOf("Shift+LMB" to "select area", "Esc" to "close"))
    }
}

private class InputsPage : Page() {
    override val title = "Inputs"
    private val name = TextState()
    private val amount = NumberState(120)
    private val search = TextState()
    private var checked = true
    private var toggled = false
    private var mode = "percent"
    private var tribute = 15.0
    private var access = "citizen"

    override fun draw(ui: Ui, r: Rect) {
        val (left, right) = r.columns(2, 12)
        val f = Flow(left, 6)
        ui.fieldLabel(f.take(9), "Country name", "${name.text.length}/24")
        name.error = if (name.text.length < 3) "Use at least 3 characters" else null
        ui.textField(f.take(CONTROL_H), name, "e.g. Kingdom of Ash", Icons.FLAG, maxLength = 24, key = "name")
        ui.fieldHelp(f.take(9), name, "Letters, digits, _ and -")
        ui.fieldLabel(f.take(9), "Amount")
        ui.numberField(f.take(CONTROL_H), amount, 1, 5000, unit = "◎", key = "amount")
        ui.fieldHelp(f.take(9), amount.text, "You carry 1,240 ◎")
        ui.fieldLabel(f.take(9), "Search")
        ui.searchField(f.take(CONTROL_H), search)
        ui.fieldLabel(f.take(9), "Tribute")
        ui.slider(f.take(CONTROL_H), tribute, 0.0, 50.0, 1.0, format = { "${it.toInt()}%" })?.let { tribute = it }
        val g = Flow(right, 6)
        ui.checkbox(g.take(14), "Show me a guided tour", checked)?.let { checked = it }
        ui.checkbox(g.take(14), "Disabled checkbox", false, enabled = false, disabledReason = "Locked by the server")
        ui.toggle(g.take(14), toggled, "Fire spreads here")?.let { toggled = it }
        ui.segmented(g.take(CONTROL_H), listOf(Option("percent", "Percent", Icons.PERCENT), Option("flat", "Flat", Icons.COIN)), mode)?.let { mode = it }
        val options = listOf(
            Option("none", "Nobody", Icons.BAN, "No one may do this"),
            Option("officer", "Officers", Icons.SHIELD, "Officers and higher"),
            Option("citizen", "Citizens", Icons.PEOPLE, "All members"),
            Option("allied", "Allies", Icons.HANDSHAKE, "Members and allied players"),
            Option("any", "Everyone", Icons.GLOBE, "Anyone, even strangers")
        )
        ui.select(g.take(CONTROL_H), options, access, key = "access")?.let { access = it }
        val radioArea = g.take(radioGroupHeight(options.take(3), right.w))
        ui.radioGroup(radioArea, options.take(3), access)?.let { access = it }
    }
}

private class DisplayPage : Page() {
    override val title = "Display"

    override fun draw(ui: Ui, r: Rect) {
        val tiles = r.top(58).columns(4, 6)
        ui.statTile(tiles[0], "Treasury", Format.number(12_480), Icons.TREASURY, Palette.money, trend = Trend(640, "+6% vs 7d"), spark = listOf(9, 10, 12, 11, 12, 13, 12).map { it * 1000L })
        ui.statTile(tiles[1], "Net / day", "+84", Icons.INCOME, Palette.success, sub = "in 212 · out 128")
        ui.statTile(tiles[2], "Runway", "43d", Icons.CLOCK, Palette.warning, sub = "next bill 3h 12m")
        ui.statTile(tiles[3], "Territory", "46", Icons.MAP, sub = "9 free · 1 in debt")
        val (a, b) = r.dropTop(64).columns(2, 8)
        val body = ui.card(a, "Properties", Icons.INFO, help = "Read-only fields with dotted leaders.")
        val f = Flow(body, 3)
        ui.property(f.take(11), "Owner", "Kingdom of Ash", Palette.money)
        ui.property(f.take(11), "Coordinates", "12, -4", copy = true)
        ui.property(f.take(11), "Upkeep", "5 ◎ / day")
        f.skip(4)
        ui.progress(f.take(6), 0.68, Palette.brass, "340 / 500")
        f.skip(4)
        ui.meter(f.take(6), 2, 3, { if (it >= 3) Severity.DANGER else Severity.WARNING }, "debt 2/3")
        f.skip(6)
        var cx = body.x
        cx += ui.chip(cx, f.take(13).y, "Mining", Palette.chart[0], Icons.PICKAXE) + 4
        ui.statusPill(cx, f.rest.y - 17, "In debt", Severity.DANGER)
        f.skip(4)
        ui.timeline(f.take(34), listOf(Step("Billed", "today", StepState.DONE), Step("Debt 1", "1d", StepState.DONE), Step("Debt 2", "2d", StepState.CURRENT), Step("Lost", "in 1d", StepState.PENDING)))
        val body2 = ui.card(b, "Feedback", Icons.BELL, Severity.WARNING)
        val g = Flow(body2, 4)
        ui.banner(g.take(24), Severity.WARNING, "3 chunks are in debt", "They are lost in 1 day.", "Deposit")
        g.take(ui.callout(g.rest, Severity.INFO, "Callouts explain something in place, with an icon and a tinted background."))
        ui.emptyState(g.remaining(), "No jobs yet", "Jobs pay citizens for mining, farming and forestry.", action = "Create first job", actionIcon = Icons.ADD)
    }
}

private class DataPage : Page() {
    override val title = "Data"
    private class Row(val name: String, val type: String, val upkeep: Long, val debt: Int)
    private val rows = List(60) { Row("Chunk ${it * 3 - 40}, ${it % 7 - 3}", listOf("mining", "farming", "residential", "market")[it % 4], (it % 5 + 1).toLong(), if (it % 11 == 0) 2 else 0) }
    private val table = TableState<Row>()

    override fun draw(ui: Ui, r: Rect) {
        val (left, right) = r.columns(listOf(1.1f, 1f), 8)
        ui.table(left, listOf(
            Column.text<Row>("Chunk", -1) { it.name },
            Column.text<Row>("Type", 70) { it.type },
            Column.number<Row>("Upkeep", 50) { it.upkeep },
            Column.number<Row>("Debt", 36, color = { if (it.debt > 0) Palette.danger else Palette.textMuted }) { it.debt.toLong() }
        ), rows, table, { it.name }, multi = true, severity = { if (it.debt > 0) Severity.DANGER else null })
        val f = Flow(right, 6)
        val balance = listOf(900L, 950, 1020, 1000, 1100, 1180, 1240)
        ui.lineChart(f.take(90), listOf(Series("Treasury", balance + forecast(1240, 84, 5), Palette.money, area = true, dashedFrom = balance.lastIndex)), List(12) { "d$it" })
        ui.barChart(f.take(50), listOf(212, 180, 240, 200, 260), listOf(128, 130, 150, 128, 140), List(5) { "day $it" })
        ui.stackedBar(f.take(8), listOf(Slice("Mining", 36, Palette.chart[0]), Slice("Residential", 50, Palette.chart[6]), Slice("Market", 12, Palette.chart[1])))
        val row = f.take(60)
        ui.donut(row.left(60), listOf(Slice("Taxes", 90, Palette.success), Slice("Tribute", 122, Palette.geoProvince)), "212")
        ui.hierarchy(Rect(row.x + 66, row.y - 6, row.w - 66, 100), GraphNode("root", "Kingdom", "overlord"), listOf(GraphNode("a", "Riverhold", "15%", "15%"), GraphNode("b", "Stonewatch", "debt 1", "80◎", Palette.danger, Severity.WARNING)), "a")
    }
}

private class OverlaysPage(val app: Gallery) : Page() {
    override val title = "Overlays"
    private var acknowledged = false
    private val typed = TextState()

    override fun draw(ui: Ui, r: Rect) {
        val cells = r.top(CONTROL_H).columns(4, 6)
        if (ui.button(cells[0], "Confirm dialog")) app.open(Dialog("Claim 12 chunks", "As Mining land", Icons.PICKAXE) { s ->
            s.used = paragraph(s, "You pay 36 ◎ now and 36 ◎ per day. Runway drops from ∞ to 190 days.")
            dialogButtons(s, "Claim 12 chunks") { s.close(); app.toast(Severity.SUCCESS, "Claimed 12 chunks", "Upkeep is now 64 ◎/day") }
        })
        if (ui.button(cells[1], "Danger dialog", style = ButtonStyle.DANGER)) app.open(Dialog("Disband country", "This cannot be undone", Icons.DANGER, DialogKind.DESTRUCTIVE) { s ->
            var y = s.body.y
            y += Draw.paragraph(g, "All land becomes nomansland and every citizen loses their country.", s.body.x, y, s.body.w) + 6
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Type Kingdom to confirm"); y += 12
            textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typed, "Kingdom", key = "typed"); y += 26
            s.used = y - s.body.y
            dialogButtons(s, "Hold to disband", enabled = typed.text == "Kingdom", disabledReason = "Type the name first", hold = true) { s.close() }
        })
        if (ui.button(cells[2], "Wizard")) app.open(Dialog("Become a province", "of Kingdom of Ash", Icons.CHAIN, DialogKind.DESTRUCTIVE, 340, listOf("Terms", "Authority", "Sign")) { s ->
            s.used = paragraph(s, when (s.step) { 0 -> "Tribute: 15% of your plot tax."; 1 -> "Kingdom of Ash can manage your land, laws and jobs."; else -> "Hold to sign the agreement." })
            if (s.step == 1) checkbox(Rect(s.body.x, s.body.y + 30, s.body.w, 14), "I understand", acknowledged)?.let { acknowledged = it }
            if (s.step == 1) s.used = 46
            wizardButtons(s, "Hold to sign", canNext = s.step != 1 || acknowledged, nextReason = "Tick the box first", hold = s.step == 2) { s.close() }
        })
        if (ui.button(cells[3], "Start tour")) app.tour = Tour(listOf(
            Callout("topbar", "Top bar", "Your country's key numbers live here."),
            Callout("nav:inputs", "Navigation", "Pages are grouped by topic.", Route("overlays")),
            Callout("content", "Content", "The page itself.")
        )) {}
        val t = r.dropTop(28).top(CONTROL_H).columns(4, 6)
        Severity.entries.drop(1).forEachIndexed { i, s -> if (ui.button(t[i], "${s.name.lowercase()} toast", key = "toast$i")) app.toast(s, "Example ${s.name.lowercase()}", "Toasts stack at the top right.", "Show") {} }
        ui.tooltip("rich", Rect(r.x, r.y + 60, 200, 20)) { Tip("Upkeep", listOf("Mining 12 × 3 = 36 ◎" to Palette.textSecondary, "Residential 10 × 5 = 50 ◎" to Palette.textSecondary), keys = "Click to open the budget") }
        Draw.text(ui.g, "Hover here for a rich tooltip", r.x, r.y + 66, Palette.link)
    }

    private fun Ui.paragraph(s: kami.libs.ui.app.DialogScope, text: String) = Draw.paragraph(g, text, s.body.x, s.body.y, s.body.w) + 4
}
