package kami.claims.client.app.pages

import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
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
    override val title get() = tr("kami_claims.nav.relations")
    override val help get() = listOf(
        Callout("relations:lists", tr("kami_claims.nav.relations"), tr("kami_claims.relations.help.lists.desc")),
        Callout("relations:add", tr("kami_claims.relations.help.add"), tr("kami_claims.relations.help.add.desc"))
    )
    private val name = TextState()
    private val table = TableState<Mem>()

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val staff = lock("members")
        val add = r.top(CONTROL_H)
        ui.anchor("relations:add", add)
        val row = Row(add, 6)
        ui.textField(row.take(160), name, tr("kami_claims.field.player"), Icons.PERSON, maxLength = 16, key = "rel-name")
        val ready = name.text.length >= 3
        val ally = tr("kami_claims.relations.ally")
        val banish = tr("kami_claims.citizens.banish")
        val needName = tr("kami_claims.field.player.disabled")
        if (ui.button(row.take(buttonWidth(ally, Icons.HANDSHAKE)), ally, Icons.HANDSHAKE, ButtonStyle.PRIMARY, staff == null && ready, staff ?: needName, key = "ally")) {
            Dialogs.confirm(app, tr("kami_claims.relations.ally.confirm.title", name.text), null, Icons.HANDSHAKE, listOf(
                Consequence(tr("kami_claims.relations.ally.rights", name.text)),
                Consequence(tr("kami_claims.relations.ally.stays"))
            ), ally, "ally", arrayOf(name.text)) { name.set("") }
        }
        if (ui.button(row.take(buttonWidth(banish, Icons.BAN)), banish, Icons.BAN, ButtonStyle.DANGER, staff == null && ready, staff ?: needName, key = "banish")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.banish.confirm.title", name.text), null, Icons.BAN, listOf(
                Consequence(tr("kami_claims.relations.banish.locked", name.text), Severity.DANGER),
                Consequence(tr("kami_claims.relations.banish.member"), Severity.WARNING)
            ), banish, "banish", arrayOf(name.text), danger = true, hold = true) { name.set("") }
        }
        val lists = r.dropTop(CONTROL_H + 6)
        ui.anchor("relations:lists", lists)
        if (info.relations.isEmpty()) {
            ui.emptyState(lists, tr("kami_claims.relations.empty.title"), if (staff == null) tr("kami_claims.relations.empty.desc") else tr("kami_claims.lock.rank", Vocabulary.rank(minRank("members").name).label), Illustrations.DIPLOMACY)
            return
        }
        ui.table(lists.dropBottom(26, 4), listOf(
            Column<Mem>(tr("kami_claims.relations.col.player"), -1, sort = compareBy { it.name.lowercase() }) { _, c, m -> avatar(m.id, c.x, c.y + 1, 12, m.online); Draw.text(g, m.name, c.x + 16, c.y + 3, Palette.text) },
            Column<Mem>(tr("kami_claims.relations.col.relation"), 120, sort = compareBy { it.rank }) { _, c, m ->
                val look = Vocabulary.rank(m.rank)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                Draw.text(g, Draw.fit(if (m.auto) tr("kami_claims.relations.bloc", look.label) else look.label, c.right - x), x, c.y + 3, look.color)
            }
        ), info.relations, table, { it.id }, severity = { if (it.rank == "banished") Severity.DANGER else null })
        val chosen = info.relations.firstOrNull { it.id in table.selected }
        val bar = r.bottom(22)
        val remove = tr("kami_claims.relations.remove")
        val w = buttonWidth(remove, Icons.REMOVE)
        val reason = staff ?: when {
            chosen == null -> tr("kami_claims.relations.select_first")
            chosen.auto -> tr("kami_claims.relations.remove.disabled.bloc")
            else -> null
        }
        if (ui.button(Rect(bar.right - w, bar.y, w, CONTROL_H), remove, Icons.REMOVE, enabled = reason == null, disabledReason = reason, key = "clear-rel")) chosen?.let {
            Dialogs.confirm(app, tr("kami_claims.relations.remove.confirm.title", it.name), null, Icons.REMOVE, listOf(Consequence(tr("kami_claims.relations.remove.stranger", it.name))), remove, "clear", arrayOf(it.id))
        }
    }
}

class WorldPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.world")
    override val needsCountry = false
    override val sections = listOf("world")
    override val help get() = listOf(
        Callout("world:tabs", tr("kami_claims.nav.world"), tr("kami_claims.world.help.tabs.desc")),
        Callout("world:detail", tr("kami_claims.citizens.help.profile"), tr("kami_claims.world.help.detail.desc"))
    )
    private var tab = 0
    private val search = TextState()
    private val countries = TableState<Line>()
    private val players = TableState<PlayerLine>()

    override fun opened(route: Route) {
        route.focus?.takeIf { it.startsWith("country:") }?.let { countries.selected.clear(); countries.selected += it.substringAfter(":"); tab = 0 }
    }

    override fun draw(ui: Ui, r: Rect) {
        val tabs = r.top(CONTROL_H)
        ui.anchor("world:tabs", tabs)
        ui.subTabs(tabs.dropRight(160), listOf(TabItem(tr("kami_claims.help.countries"), Icons.FLAG, snap.countries.size, Severity.NEUTRAL), TabItem(tr("kami_claims.world.players"), Icons.PEOPLE, snap.players.size, Severity.NEUTRAL)), tab, "world-tabs")?.let { tab = it }
        ui.searchField(tabs.right(154), search, tr(if (tab == 0) "kami_claims.world.search.countries" else "kami_claims.world.search.players"), key = "world-search")
        val body = r.dropTop(CONTROL_H + 6)
        val detailW = if (app.compact) 0 else (body.w * 0.4).toInt().coerceIn(160, 230)
        val list = body.dropRight(detailW, if (detailW > 0) 8 else 0)
        val detail = body.right(detailW)
        ui.anchor("world:detail", detail)
        if (tab == 0) {
            val rows = snap.countries.filter { search.text.isBlank() || it.name.contains(search.text, true) }
            ui.table(list, listOf(
                Column<Line>(tr("kami_claims.world.col.country"), -1, sort = compareBy { it.name.lowercase() }) { _, c, l ->
                    Flags.draw(g, Rect(c.x, c.y + 2, 14, 10), l.color, l.flag.pattern, l.flag.emblem, l.flag.secondary)
                    Draw.text(g, Draw.fit(l.name, c.w - 18), c.x + 18, c.y + 3, Palette.text)
                },
                Column.number<Line>(tr("kami_claims.nav.citizens"), 56) { it.members.toLong() },
                Column.number<Line>(tr("kami_claims.nav.chunks"), 50) { it.chunks.toLong() },
                Column.text<Line>(tr("kami_claims.relations.col.relation"), 70, color = { relationColor(it.relation) }) { relationLabel(it.relation) }
            ), rows, countries, { it.name }, emptyText = tr("kami_claims.world.empty.countries"))
            if (detailW > 0) rows.firstOrNull { it.name in countries.selected }?.let { country(ui, detail, it) }
        } else {
            val rows = snap.players.filter { search.text.isBlank() || it.name.contains(search.text, true) }
            ui.table(list, listOf(
                Column<PlayerLine>(tr("kami_claims.relations.col.player"), -1, sort = compareBy { it.name.lowercase() }) { _, c, p -> avatar(p.id, c.x, c.y + 1, 12, p.online); Draw.text(g, p.name, c.x + 16, c.y + 3, Palette.text) },
                Column.text<PlayerLine>(tr("kami_claims.world.col.country"), 110, color = { if (it.citizenships.none { c -> c.via.isEmpty() }) Palette.textMuted else Palette.money }) { p -> p.citizenships.firstOrNull { it.via.isEmpty() }?.country ?: "-" }
            ), rows, players, { it.id }, emptyText = tr("kami_claims.world.empty.players"))
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

    private fun relationLabel(r: String) = tr(when (r) {
        "own" -> "kami_claims.relation.1"
        "family" -> "kami_claims.relation.3"
        "ally" -> "kami_claims.relation.2"
        "banished" -> "kami_claims.relation.4"
        else -> "kami_claims.relation.0"
    })

    private fun country(ui: Ui, r: Rect, l: Line) {
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val f = Flow(r.inset(8), 4)
        val head = f.take(30)
        Flags.draw(ui.g, Rect(head.x, head.y + 2, 36, 26), l.color, l.flag.pattern, l.flag.emblem, l.flag.secondary)
        Draw.text(ui.g, Draw.fit(l.name, head.w - 44), head.x + 44, head.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, relationLabel(l.relation), head.x + 44, head.y + 16, relationColor(l.relation))
        ui.property(f.take(11), tr("kami_claims.rank.president"), l.president.ifEmpty { "-" })
        ui.property(f.take(11), tr("kami_claims.nav.citizens"), Format.number(l.members))
        ui.property(f.take(11), tr("kami_claims.kpi.land"), trn("kami_claims.unit.chunk", l.chunks))
        ui.property(f.take(11), tr("kami_claims.world.founded"), if (l.founded > 0) Format.ago(l.founded) else "-")
        if (l.parent.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.world.province_of"), l.parent, Palette.geoProvince)
        if (l.provinces > 0) ui.property(f.take(11), tr("kami_claims.nav.provinces"), Format.number(l.provinces))
        MiniMap.draw(ui, f.take(90), l.capitalX, l.capitalZ, 5, "country:${l.name}")
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.toast.show_on_map"), Icons.MAP, key = "country-map")) app.openMapAt(l.capitalX, l.capitalZ, false)
        y -= CONTROL_H + 3
        if (info == null && l.relation != "banished") {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.world.join"), Icons.INVITE, ButtonStyle.PRIMARY, pending = pending("join"), key = "join-country")) {
                Dialogs.confirm(app, tr("kami_claims.world.join.confirm.title", l.name), null, Icons.INVITE, listOf(
                    Consequence(tr("kami_claims.world.join.review", l.name)),
                    Consequence(tr("kami_claims.join.confirm.single"))
                ), tr("kami_claims.world.join.action"), "join", arrayOf(l.name))
            }
        }
    }

    private fun player(ui: Ui, r: Rect, p: PlayerLine) {
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val f = Flow(r.inset(8), 4)
        val head = f.take(28)
        ui.avatar(p.id, head.x, head.y, 24, p.online)
        Draw.text(ui.g, p.name, head.x + 30, head.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, tr(if (p.online) "kami_claims.citizens.online" else "kami_claims.world.offline"), head.x + 30, head.y + 15, if (p.online) Palette.success else Palette.textMuted)
        ui.section(f.take(14), tr("kami_claims.world.citizenships"))
        if (p.citizenships.isEmpty()) f.take(12).let { Draw.text(ui.g, tr("kami_claims.topbar.no_country"), it.x, it.y, Palette.textMuted) }
        p.citizenships.forEach { c ->
            val row = f.take(20)
            val look = Vocabulary.rank(c.role)
            val x = row.x + Draw.leadIcon(ui.g, look.icon, row.x, row.y + 5) + 2
            Draw.text(ui.g, Draw.fit(c.country, row.right - x), x, row.y, Palette.text)
            Draw.text(ui.g, Draw.fit(if (c.via.isNotEmpty()) tr("kami_claims.world.via", look.label, c.via) else look.label, row.right - x), x, row.y + 10, look.color)
        }
    }
}
