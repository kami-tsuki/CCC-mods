package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Flags
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.MiniMap
import kami.claims.net.Line
import kami.claims.net.Mem
import kami.claims.net.PlayerLine
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

class RelationsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Relations"
    override val help = listOf(
        Callout("relations:lists", "Relations", "Allies may use what your laws allow allies to use. Banished players are locked out of all your land."),
        Callout("relations:add", "Add", "Ally or banish a player who is not a member.")
    )
    private val name = TextState()
    private val table = TableState<Mem>()

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val staff = lock("members")
        val add = r.top(CONTROL_H)
        ui.anchor("relations:add", add)
        val row = Row(add, 6)
        ui.textField(row.take(160), name, "Player name", Icons.PERSON, maxLength = 16, key = "rel-name")
        val ready = name.text.length >= 3
        if (ui.button(row.take(buttonWidth("Ally", Icons.HANDSHAKE)), "Ally", Icons.HANDSHAKE, ButtonStyle.PRIMARY, staff == null && ready, staff ?: "Enter a player name", key = "ally")) {
            Dialogs.confirm(app, "Ally ${name.text}", null, Icons.HANDSHAKE, listOf(
                Consequence("${name.text} may do everything your laws allow allies to do."),
                Consequence("They stay a member of their own country.")
            ), "Ally", "ally", arrayOf(name.text)) { name.set("") }
        }
        if (ui.button(row.take(buttonWidth("Banish", Icons.BAN)), "Banish", Icons.BAN, ButtonStyle.DANGER, staff == null && ready, staff ?: "Enter a player name", key = "banish")) {
            Dialogs.confirm(app, "Banish ${name.text}", null, Icons.BAN, listOf(
                Consequence("${name.text} can't use, open or build anything in your land.", Severity.DANGER),
                Consequence("If they are a member, they are removed.", Severity.WARNING)
            ), "Banish", "banish", arrayOf(name.text), danger = true, hold = true) { name.set("") }
        }
        val lists = r.dropTop(CONTROL_H + 6)
        ui.anchor("relations:lists", lists)
        if (info.relations.isEmpty()) {
            ui.emptyState(lists, "No allies or banished players", if (staff == null) "Ally friends from other countries so they can visit your markets, or banish troublemakers." else "Only ${Vocabulary.rank(minRank("members").name).label}s and higher see and change relations.", Illustrations.DIPLOMACY)
            return
        }
        ui.table(lists.dropBottom(26, 4), listOf(
            Column<Mem>("Player", -1, sort = compareBy { it.name.lowercase() }) { _, c, m -> avatar(m.id, c.x, c.y + 2, 12, m.online); Draw.text(g, m.name, c.x + 16, c.y + 4, Palette.text) },
            Column<Mem>("Relation", 120, sort = compareBy { it.rank }) { _, c, m ->
                val look = Vocabulary.rank(m.rank)
                Draw.icon(g, look.icon, c.x - 2, c.y + 2, 12)
                Draw.text(g, look.label + if (m.auto) " (family)" else "", c.x + 12, c.y + 4, look.color)
            }
        ), info.relations, table, { it.id }, severity = { if (it.rank == "banished") Severity.DANGER else null })
        val chosen = info.relations.firstOrNull { it.id in table.selected }
        val bar = r.bottom(22)
        val w = buttonWidth("Remove relation", Icons.REMOVE)
        val reason = staff ?: when {
            chosen == null -> "Select a player first"
            chosen.auto -> "Family allies come from provinces and can't be removed here"
            else -> null
        }
        if (ui.button(Rect(bar.right - w, bar.y, w, CONTROL_H), "Remove relation", Icons.REMOVE, enabled = reason == null, disabledReason = reason, key = "clear-rel")) chosen?.let {
            Dialogs.confirm(app, "Remove relation with ${it.name}", null, Icons.REMOVE, listOf(Consequence("${it.name} is treated like any stranger again.")), "Remove", "clear", arrayOf(it.id))
        }
    }
}

class WorldPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "World"
    override val needsCountry = false
    override val sections = listOf("world")
    override val help = listOf(Callout("world:tabs", "World", "Every country and every player on the server."), Callout("world:detail", "Profile", "Click a row to see more."))
    private var tab = 0
    private val search = TextState()
    private val countries = TableState<Line>()
    private val players = TableState<PlayerLine>()

    override fun opened(route: Route) {
        route.focus?.takeIf { it.startsWith("country:") }?.let { countries.selected.clear(); countries.selected += it.substringAfter(":"); tab = 0 }
    }

    override fun draw(ui: Ui, r: Rect) {
        val tabs = r.top(20)
        ui.anchor("world:tabs", tabs)
        ui.subTabs(tabs.dropRight(160), listOf(TabItem("Countries", Icons.FLAG, snap.countries.size, Severity.NEUTRAL), TabItem("Players", Icons.PEOPLE, snap.players.size, Severity.NEUTRAL)), tab, "world-tabs")?.let { tab = it }
        ui.searchField(tabs.right(154), search, if (tab == 0) "Search countries" else "Search players", key = "world-search")
        val body = r.dropTop(26)
        val detailW = if (app.compact) 0 else (body.w * 0.4).toInt().coerceIn(160, 230)
        val list = body.dropRight(detailW, if (detailW > 0) 8 else 0)
        val detail = body.right(detailW)
        ui.anchor("world:detail", detail)
        if (tab == 0) {
            val rows = snap.countries.filter { search.text.isBlank() || it.name.contains(search.text, true) }
            ui.table(list, listOf(
                Column<Line>("Country", -1, sort = compareBy { it.name.lowercase() }) { _, c, l ->
                    Flags.draw(g, Rect(c.x, c.y + 3, 14, 10), l.color, l.flag.pattern, l.flag.emblem, l.flag.secondary)
                    Draw.text(g, Draw.fit(l.name, c.w - 18), c.x + 18, c.y + 4, Palette.text)
                },
                Column.number<Line>("Citizens", 56) { it.members.toLong() },
                Column.number<Line>("Chunks", 50) { it.chunks.toLong() },
                Column.text<Line>("Relation", 70, color = { relationColor(it.relation) }) { relationLabel(it.relation) }
            ), rows, countries, { it.name }, emptyText = "No countries yet.")
            if (detailW > 0) rows.firstOrNull { it.name in countries.selected }?.let { country(ui, detail, it) }
        } else {
            val rows = snap.players.filter { search.text.isBlank() || it.name.contains(search.text, true) }
            ui.table(list, listOf(
                Column<PlayerLine>("Player", -1, sort = compareBy { it.name.lowercase() }) { _, c, p -> avatar(p.id, c.x, c.y + 2, 12, p.online); Draw.text(g, p.name, c.x + 16, c.y + 4, Palette.text) },
                Column.text<PlayerLine>("Country", 110, color = { if (it.citizenships.none { c -> c.via.isEmpty() }) Palette.textMuted else Palette.money }) { p -> p.citizenships.firstOrNull { it.via.isEmpty() }?.country ?: "none" }
            ), rows, players, { it.id }, emptyText = "Nobody here yet.")
            if (detailW > 0) rows.firstOrNull { it.id in players.selected }?.let { player(ui, detail, it) }
        }
    }

    private fun relationColor(r: String) = when (r) {
        "own" -> Palette.success
        "family" -> Palette.geoProvince
        "ally" -> Palette.geoAlly
        "banished" -> Palette.danger
        else -> Palette.textMuted
    }

    private fun relationLabel(r: String) = when (r) {
        "own" -> "yours"
        "family" -> "family"
        "ally" -> "allied"
        "banished" -> "banished you"
        else -> "foreign"
    }

    private fun country(ui: Ui, r: Rect, l: Line) {
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val f = Flow(r.inset(8), 4)
        val head = f.take(30)
        Flags.draw(ui.g, Rect(head.x, head.y + 2, 36, 26), l.color, l.flag.pattern, l.flag.emblem, l.flag.secondary)
        Draw.text(ui.g, Draw.fit(l.name, head.w - 44), head.x + 44, head.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, relationLabel(l.relation), head.x + 44, head.y + 16, relationColor(l.relation))
        ui.property(f.take(11), "President", l.president.ifEmpty { "—" })
        ui.property(f.take(11), "Citizens", "${l.members}")
        ui.property(f.take(11), "Land", Format.plural(l.chunks, "chunk"))
        ui.property(f.take(11), "Founded", if (l.founded > 0) Format.ago(l.founded) else "—")
        if (l.parent.isNotEmpty()) ui.property(f.take(11), "Province of", l.parent, Palette.geoProvince)
        if (l.provinces > 0) ui.property(f.take(11), "Provinces", "${l.provinces}")
        MiniMap.draw(ui, f.take(90), l.capitalX, l.capitalZ, 5, "country:${l.name}")
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), "Show on map", Icons.MAP, key = "country-map")) app.openMapAt(l.capitalX, l.capitalZ, false)
        y -= CONTROL_H + 3
        if (info == null && l.relation != "banished") {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), "Ask to join", Icons.INVITE, ButtonStyle.PRIMARY, pending = pending("join"), key = "join-country")) {
                Dialogs.confirm(app, "Ask to join ${l.name}", null, Icons.INVITE, listOf(
                    Consequence("An officer of ${l.name} approves or denies your request."),
                    Consequence("You can only be in one country at a time.")
                ), "Send request", "join", arrayOf(l.name))
            }
        }
    }

    private fun player(ui: Ui, r: Rect, p: PlayerLine) {
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val f = Flow(r.inset(8), 4)
        val head = f.take(28)
        ui.avatar(p.id, head.x, head.y, 24, p.online)
        Draw.text(ui.g, p.name, head.x + 30, head.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, if (p.online) "online" else "offline", head.x + 30, head.y + 15, if (p.online) Palette.success else Palette.textMuted)
        ui.section(f.take(14), "Citizenships")
        if (p.citizenships.isEmpty()) f.take(12).let { Draw.text(ui.g, "Not in any country.", it.x, it.y, Palette.textMuted) }
        p.citizenships.forEach { c ->
            val row = f.take(22)
            val look = Vocabulary.rank(c.role)
            Draw.icon(ui.g, look.icon, row.x, row.y)
            Draw.text(ui.g, c.country, row.x + 20, row.y + 1, Palette.text)
            Draw.text(ui.g, look.label + if (c.via.isNotEmpty()) " through ${c.via}" else "", row.x + 20, row.y + 11, look.color)
        }
    }
}
