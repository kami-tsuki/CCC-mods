package kami.claims.client.app.pages

import kami.libs.ui.core.Memo
import kami.libs.text.SearchQuery
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.ClaimsPage
import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.libs.ui.widget.Flags
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

private fun countryColumn() = Column<Line>(tr("kami_libs.common.country"), -1, sort = textOrder { it.name }) { _, c, l ->
    Flags.draw(g, Rect(c.x, c.y + 2, 14, 10), l.color, l.flag.pattern, l.flag.emblem, l.flag.secondary)
    fitText(c.x + 18, c.y + 3, c.w - 18, l.name)
}

private fun <T> playerColumn(id: (T) -> String, name: (T) -> String, online: (T) -> Boolean) = Column<T>(tr("kami_libs.common.player"), -1, sort = textOrder { name(it) }) { _, c, p ->
    avatar(id(p), c.x, c.y + 1, 12, online(p))
    fitText(c.x + 16, c.y + 3, c.w - 16, name(p))
}

private fun Ui.sidePanelHeader(head: Rect, l: Line, subtitle: String, color: Int) {
    Flags.draw(g, Rect(head.x, head.y + 2, 36, 26), l.color, l.flag.pattern, l.flag.emblem, l.flag.secondary)
    fitText(head.x + 44, head.y + 4, head.w - 44, l.name, style = TextStyle.HEADING)
    Draw.text(g, subtitle, head.x + 44, head.y + 16, color)
}

class RelationsPage(app: ClaimsApp) : ClaimsPage(app) {
    private val memberColumns = Memo()
    private val rankOptions = Memo()
    private val tradeColumns = Memo()
    private val tradeStatusOptions = Memo()
    private val relTabs = Memo()
    override val title get() = tr("kami_claims.nav.relations")
    override val help get() = listOf(
        Callout("relations:lists", tr("kami_claims.nav.relations"), tr("kami_claims.relations.help.lists.desc")),
        Callout("relations:add", tr("kami_libs.common.add"), tr("kami_claims.relations.help.add.desc"))
    )
    override val sections = listOf(ClaimsStore.WORLD)
    private val name = TextState()
    private val table = TableState<Mem>()
    private val countries = TableState<Line>()
    private val tradeSearch = TextState()
    private var tradeStatus = ""
    private val memberSearch = TextState()
    private var memberRank = ""
    private val tariff = NumberState(0)
    private var tariffFor = ""
    private var tab = 0

    override fun opened(route: Route) {
        route.focus?.takeIf { it.startsWith("country:") }?.let { countries.selected.clear(); countries.selected += it.substringAfter(":"); tab = 1 }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val rows = ClaimsStore.countries.filter { it.name != info.name }
        val offers = rows.count { it.alliance == "offer_in" }
        val tabs = r.top(CONTROL_H)
        ui.subTabs(tabs, relTabs.of(info.relations.size, rows.size, offers > 0, Format.locale) { listOf(
            TabItem(tr("kami_libs.common.players"), Icons.PEOPLE, info.relations.size, Severity.NEUTRAL),
            TabItem(tr("kami_claims.help.countries"), Icons.FLAG, rows.size, if (offers > 0) Severity.WARNING else Severity.NEUTRAL)
        ) }, tab, "rel-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        if (tab == 0) players(ui, body) else trade(ui, body, rows)
    }

    private fun trade(ui: Ui, r: Rect, all: List<Line>) {
        val statuses = all.map(::tradeLabel).distinct().sorted()
        val body = ui.filterBar(r, "trade:filters") { bar ->
            val row = Row(bar, 6)
            ui.select(row.takeFromRight(110), tradeStatusOptions.of(statuses, Format.locale) { listOf(Option("", tr("kami_libs.common.all"))) + statuses.map { Option(it, it) } }, tradeStatus, key = "trade-status")?.let { tradeStatus = it }
            ui.searchField(row.rest, tradeSearch, tr("kami_claims.world.search.countries"), key = "trade-search")
        }
        val q = SearchQuery.parse(tradeSearch.text)
        val rows = all.filter { q.matches(it.name) && (tradeStatus.isEmpty() || tradeLabel(it) == tradeStatus) }
        val detailW = if (app.compact) 0 else (body.w * 0.45).toInt().coerceIn(170, 240)
        val list = body.dropRight(detailW, if (detailW > 0) 8 else 0)
        ui.table(list, tradeColumns.of(Format.locale) { listOf(
            countryColumn(),
            Column.text<Line>(tr("kami_libs.common.status"), 80, color = { tradeColor(it) }) { tradeLabel(it) },
            Column.text<Line>(tr("kami_claims.trade.tariff"), 50) { if (it.tariff > 0) Format.percent(it.tariff / 100.0) else "-" }
        ) }, rows, countries, { it.name }, emptyText = tr("kami_claims.world.empty.countries"), loading = loading(ClaimsStore.WORLD))
        ui.staleRows(list, stale(ClaimsStore.WORLD))
        if (detailW > 0) (rows.firstOrNull { it.name in countries.selected } ?: rows.firstOrNull())?.let { tradeDetail(ui, body.right(detailW), it) }
    }

    private fun tradeLabel(l: Line) = Vocabulary.trade(l.trade, l.alliance).second
    private fun tradeColor(l: Line) = Vocabulary.trade(l.trade, l.alliance).first

    private fun tradeDetail(ui: Ui, r: Rect, l: Line) {
        if (tariffFor != l.name) { tariffFor = l.name; tariff.commit(l.tariff.toLong()) }
        val f = ui.sidePanel(r, 4, 8)
        ui.sidePanelHeader(f.take(30), l, tradeLabel(l), tradeColor(l))
        ui.property(f.take(11), tr("kami_claims.trade.rate"), tr(if (l.trade == "family" || l.alliance == "allied") "kami_claims.trade.rate.ally" else "kami_libs.common.normal"))
        ui.property(f.take(11), tr("kami_claims.trade.their_tariff"), if (l.theirTariff > 0) Format.percent(l.theirTariff / 100.0) else "-")
        ui.property(f.take(11), tr("kami_claims.warpage.reputation"), Format.number(l.reputation), WarPage.reputationColor(l.reputation))
        ui.property(f.take(11), tr("kami_claims.warpage.fame"), l.fame?.let { WarPage.stars(it) } ?: tr("kami_claims.warpage.fame.hidden"), Palette.brass)
        if (l.atWar) f.take(11).let { Draw.text(ui.g, tr("kami_claims.warpage.at_war"), it.x, it.y, Palette.danger) }
        rating(ui, f.take(12), l)
        val reason = lock("trade") ?: if (l.trade == "family") tr("kami_claims.trade.family") else null
        ui.section(f.take(14), tr("kami_claims.trade.alliance"))
        val row = Row(f.take(CONTROL_H), 4)
        when (l.alliance) {
            "allied" -> if (ui.button(row.rest, tr("kami_claims.trade.alliance.end"), Icons.REMOVE, ButtonStyle.DANGER, reason == null, reason, key = "alliance-end")) {
                Dialogs.confirm(app, tr("kami_claims.trade.alliance.end.title", l.name), null, Icons.REMOVE, listOf(
                    Consequence(tr("kami_claims.trade.alliance.end.rank", l.name), Severity.WARNING),
                    Consequence(tr("kami_claims.trade.alliance.end.rate"))
                ), tr("kami_claims.trade.alliance.end"), "alliance", arrayOf("end", l.name), danger = true)
            }
            "offer_in" -> {
                val half = row.rest.w / 2 - 2
                if (ui.lockedButton(row.take(half), tr("kami_libs.common.accept"), ClientLocks.feature("alliances"), Icons.HANDSHAKE, ButtonStyle.PRIMARY, reason == null, reason, pending = pending("alliance"), key = "alliance-accept")) act("alliance", "accept", l.name, key = "alliance")
                if (ui.button(row.rest, tr("kami_libs.common.decline"), Icons.REMOVE, enabled = reason == null, disabledReason = reason, key = "alliance-decline")) act("alliance", "decline", l.name, key = "alliance")
            }
            else -> {
                val why = reason ?: when {
                    l.alliance == "offer_out" -> tr("kami_claims.trade.offer_out")
                    l.trade == "embargo" -> tr("kami_claims.error.alliance_embargo")
                    else -> null
                }
                if (ui.lockedButton(row.rest, tr("kami_claims.trade.alliance.propose"), ClientLocks.feature("alliances"), Icons.HANDSHAKE, ButtonStyle.PRIMARY, why == null, why, pending = pending("alliance"), key = "alliance-propose")) {
                    Dialogs.confirm(app, tr("kami_claims.trade.alliance.propose.title", l.name), null, Icons.HANDSHAKE, listOf(
                        Consequence(tr("kami_claims.trade.alliance.propose.rank", l.name)),
                        Consequence(tr("kami_claims.trade.alliance.propose.rate"))
                    ), tr("kami_claims.trade.alliance.propose"), "alliance", arrayOf("propose", l.name))
                }
            }
        }
        ui.section(f.take(14), tr("kami_claims.trade.tariff"))
        val trow = Row(f.take(CONTROL_H), 4)
        val apply = tr("kami_claims.trade.tariff.apply")
        ui.numberField(trow.take(trow.rest.w - buttonWidth(apply, Icons.SCALES) - 4), tariff, 0, 50, 5, "%", enabled = reason == null, key = "tariff")
        val same = tariff.value.toInt() == l.tariff
        if (ui.button(trow.rest, apply, Icons.SCALES, enabled = reason == null && !same, disabledReason = reason ?: tr("kami_claims.trade.tariff.same"), pending = pending("tariff"), key = "tariff-apply")) act("tariff", l.name, tariff.value.toString())
        f.take(11).let { ui.fitText(it.x, it.y, it.w, tr("kami_claims.trade.tariff.hint"), Palette.textMuted) }
        ui.section(f.take(14), tr("kami_libs.common.embargo"))
        val erow = f.take(CONTROL_H)
        val allied = l.alliance == "allied"
        val embargoLock = ClientLocks.feature("embargoes")
        if (embargoLock != null) ui.lockedButton(erow, tr("kami_claims.trade.embargo.toggle"), embargoLock, Icons.BAN, key = "embargo-locked")
        else ui.toggle(erow, l.embargo, tr("kami_claims.trade.embargo.toggle"), reason == null && !(allied && !l.embargo), reason ?: tr("kami_claims.trade.embargo.ally"), tr("kami_claims.trade.embargo.tooltip"), key = "embargo")?.let { on ->
            if (on) Dialogs.confirm(app, tr("kami_claims.trade.embargo.title", l.name), null, Icons.BAN, listOf(
                Consequence(tr("kami_claims.trade.embargo.market", l.name), Severity.WARNING),
                Consequence(tr("kami_claims.trade.embargo.vendors", l.name), Severity.WARNING)
            ), tr("kami_claims.trade.embargo.toggle"), "embargo", arrayOf(l.name, "on"), danger = true)
            else act("embargo", l.name, "off")
        }
    }

    private fun rating(ui: Ui, r: Rect, l: Line) {
        val label = tr("kami_claims.warpage.rate")
        val x0 = r.x + Draw.width(label) + 6
        Draw.text(ui.g, label, r.x, r.y + 2, Palette.textMuted)
        var shown = l.myRating
        val halves = (1..10).map { v -> v to Rect(x0 + (v - 1) * 5, r.y, 5, r.h) }
        halves.firstOrNull { ui.hovering(it.second) }?.let { shown = it.first }
        for (i in 0 until 5) Draw.text(ui.g, if (shown >= i * 2 + 2) "★" else "☆", x0 + i * 10, r.y + 2, if (shown > i * 2) Palette.brass else Palette.textMuted)
        Draw.text(ui.g, if (shown > 0) Format.decimal(shown / 2.0, 1) else "-", x0 + 54, r.y + 2, Palette.textSecondary)
        halves.forEach { (v, cell) -> if (ui.clickable("fame-$v", cell, !pending("fame_rate"))) {
            Dialogs.confirm(app, tr("kami_claims.warpage.rate.title", l.name, Format.decimal(v / 2.0, 1)), null, Icons.STAR, listOf(Consequence(tr("kami_claims.warpage.rate.lock"), Severity.WARNING)), tr("kami_claims.warpage.rate.action"), "fame_rate", arrayOf(l.name, v.toString()))
        } }
    }

    private fun players(ui: Ui, r: Rect) {
        val info = info ?: return
        val staff = lock("members")
        val add = r.top(CONTROL_H)
        ui.anchor("relations:add", add)
        val row = Row(add, 6)
        ui.playerField(row.take(160), name, offline = { ClaimsStore.snap?.names.orEmpty() }, query = { ClaimsStore.quiet("names", it) }, key = "rel-name")
        val ready = name.text.length >= 3
        val ally = tr("kami_claims.relations.ally")
        val banish = tr("kami_claims.citizens.banish")
        val needName = tr("kami_claims.field.player.disabled")
        if (ui.edgeButton(row, ally, Icons.HANDSHAKE, ButtonStyle.PRIMARY, staff == null && ready, staff ?: needName, left = true, key = "ally")) {
            Dialogs.confirm(app, tr("kami_claims.relations.ally.confirm.title", name.text), null, Icons.HANDSHAKE, listOf(
                Consequence(tr("kami_claims.relations.ally.rights", name.text)),
                Consequence(tr("kami_claims.relations.ally.stays"))
            ), ally, "ally", arrayOf(name.text)) { name.set("") }
        }
        if (ui.lockedButton(row.take(buttonWidth(banish, Icons.BAN)), banish, ClientLocks.feature("banish"), Icons.BAN, ButtonStyle.DANGER, staff == null && ready, staff ?: needName, key = "banish")) {
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
        val ranks = info.relations.map { it.rank }.distinct().sortedBy { Vocabulary.rankOrder.indexOf(it) }
        val tableArea = ui.filterBar(lists.dropBottom(26, 4), "relations:filters") { bar ->
            val row = Row(bar, 6)
            ui.select(row.takeFromRight(120), rankOptions.of(ranks, Format.locale) { listOf(Option("", tr("kami_libs.common.all"))) + ranks.map { Option(it, Vocabulary.rank(it).label) } }, memberRank, key = "rel-rank")?.let { memberRank = it }
            ui.searchField(row.rest, memberSearch, tr("kami_claims.citizens.search"), key = "rel-search")
        }
        val mq = SearchQuery.parse(memberSearch.text)
        val members = info.relations.filter { mq.matches(it.name) && (memberRank.isEmpty() || it.rank == memberRank) }
        ui.table(tableArea, memberColumns.of(Format.locale) { listOf(
            playerColumn<Mem>({ it.id }, { it.name }, { it.online }),
            Column<Mem>(tr("kami_claims.relations.col.relation"), 120, sort = compareBy { it.rank }) { _, c, m ->
                val look = Vocabulary.rank(m.rank)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                fitText(x, c.y + 3, c.right - x, if (m.auto) tr("kami_claims.relations.bloc", look.label) else look.label, look.color)
            }
        ) }, members, table, { it.id }, severity = { if (it.rank == "banished") Severity.DANGER else null })
        val chosen = info.relations.firstOrNull { it.id in table.selected }
        val bar = r.bottom(22)
        val remove = tr("kami_claims.relations.remove")
        val reason = staff ?: when {
            chosen == null -> tr("kami_claims.relations.select_first")
            chosen.auto -> tr("kami_claims.relations.remove.disabled.bloc")
            else -> null
        }
        if (ui.edgeButton(Rect(bar.x, bar.y, bar.w, CONTROL_H), remove, Icons.REMOVE, enabled = reason == null, disabledReason = reason, key = "clear-rel")) chosen?.let {
            Dialogs.confirm(app, tr("kami_claims.relations.remove.confirm.title", it.name), null, Icons.REMOVE, listOf(Consequence(tr("kami_claims.relations.remove.stranger", it.name))), remove, "clear", arrayOf(it.id))
        }
    }
}

class WorldPage(app: ClaimsApp) : ClaimsPage(app) {
    private val playerColumns = Memo()
    private val countryColumns = Memo()
    private val worldTabs = Memo()
    override val title get() = tr("kami_claims.nav.world")
    override val needsCountry = false
    override val sections = listOf(ClaimsStore.WORLD)
    override val help get() = listOf(
        Callout("world:tabs", tr("kami_claims.nav.world"), tr("kami_claims.world.help.tabs.desc")),
        Callout("world:detail", tr("kami_libs.common.profile"), tr("kami_claims.world.help.detail.desc"))
    )
    private var tab = 0
    private val search = TextState()
    private val countries = TableState<Line>()
    private val players = TableState<PlayerLine>()

    override fun opened(route: Route) {
        route.focus?.takeIf { it.startsWith("country:") }?.let { countries.selected.clear(); countries.selected += it.substringAfter(":"); tab = 0 }
    }

    override fun draw(ui: Ui, r: Rect) {
        val body = ui.filterBar(r, "world:tabs") { tabs ->
            ui.subTabs(tabs.dropRight(160), worldTabs.of(ClaimsStore.countries.size, ClaimsStore.players.size, Format.locale) { listOf(TabItem(tr("kami_claims.help.countries"), Icons.FLAG, ClaimsStore.countries.size, Severity.NEUTRAL), TabItem(tr("kami_libs.common.players"), Icons.PEOPLE, ClaimsStore.players.size, Severity.NEUTRAL)) }, tab, "world-tabs")?.let { tab = it }
            ui.searchField(tabs.right(154), search, tr(if (tab == 0) "kami_claims.world.search.countries" else "kami_claims.world.search.players"), key = "world-search")
        }
        val detailW = if (app.compact) 0 else (body.w * 0.4).toInt().coerceIn(160, 230)
        val list = body.dropRight(detailW, if (detailW > 0) 8 else 0)
        val detail = body.right(detailW)
        ui.anchor("world:detail", detail)
        val q = SearchQuery.parse(search.text)
        if (tab == 0) {
            val rows = ClaimsStore.countries.filter { q.matches(it.name) }
            ui.table(list, countryColumns.of(snap.admin, Format.locale) { listOf(
                countryColumn(),
                Column.number<Line>(tr("kami_libs.common.level"), 40) { it.level.toLong() },
                Column.number<Line>(tr("kami_claims.world.researched"), 64) { it.researched.toLong() },
                Column.number<Line>(tr("kami_claims.nav.citizens"), 56) { it.members.toLong() },
                Column.number<Line>(tr("kami_claims.nav.chunks"), 50) { it.chunks.toLong() },
                Column.number<Line>(tr("kami_claims.warpage.reputation"), 58, color = { WarPage.reputationColor(it.reputation) }) { it.reputation.toLong() },
                Column.number<Line>(tr("kami_claims.warpage.fame"), 50, color = { Palette.brass }, format = { if (it < 0) "-" else WarPage.stars(it / 10.0) }) { l -> l.fame?.let { (it * 10).toLong() } ?: -1 },
                Column.text<Line>(tr("kami_claims.relations.col.relation"), 70, color = { relationColor(it.relation) }) { relationLabel(it.relation) }
            ) + listOfNotNull(Column.number<Line>(tr("kami_claims.kpi.power"), 44, format = { WarPage.power(it / 10.0) }) { l -> ((l.power ?: 0.0) * 10).toLong() }.takeIf { snap.admin }) }, rows, countries, { it.name }, emptyText = tr("kami_claims.world.empty.countries"), loading = loading(ClaimsStore.WORLD))
            ui.staleRows(list, stale(ClaimsStore.WORLD))
            if (detailW > 0) rows.firstOrNull { it.name in countries.selected }?.let { country(ui, detail, it) }
        } else {
            val rows = ClaimsStore.players.filter { q.matches(it.name) }
            ui.table(list, playerColumns.of(Format.locale) { listOf(
                playerColumn<PlayerLine>({ it.id }, { it.name }, { it.online }),
                Column.text<PlayerLine>(tr("kami_libs.common.country"), 110, color = { if (it.citizenships.none { c -> c.via.isEmpty() }) Palette.textMuted else Palette.money }) { p -> p.citizenships.firstOrNull { it.via.isEmpty() }?.country ?: "-" }
            ) }, rows, players, { it.id }, emptyText = tr("kami_claims.world.empty.players"), loading = loading(ClaimsStore.WORLD))
            ui.staleRows(list, stale(ClaimsStore.WORLD))
            if (detailW > 0) rows.firstOrNull { it.id in players.selected }?.let { player(ui, detail, it) }
        }
    }

    private fun relationColor(r: String) = Vocabulary.relation(r).first
    private fun relationLabel(r: String) = Vocabulary.relation(r).second

    private fun country(ui: Ui, r: Rect, l: Line) {
        val f = ui.sidePanel(r, 4, 8)
        ui.sidePanelHeader(f.take(30), l, relationLabel(l.relation), relationColor(l.relation))
        ui.property(f.take(11), tr("kami_claims.rank.president"), l.president.ifEmpty { "-" })
        ui.property(f.take(11), tr("kami_libs.common.level"), Format.number(l.level))
        ui.property(f.take(11), tr("kami_claims.world.researched"), Format.number(l.researched))
        ui.property(f.take(11), tr("kami_claims.warpage.reputation"), Format.number(l.reputation), WarPage.reputationColor(l.reputation))
        ui.property(f.take(11), tr("kami_claims.warpage.fame"), l.fame?.let(WarPage::stars) ?: tr("kami_claims.warpage.fame.hidden"), Palette.brass)
        l.power?.let { ui.property(f.take(11), tr("kami_claims.kpi.power"), WarPage.power(it)) }
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
        if (snap.admin && l.name != info?.name) {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.world.inspect"), Icons.EYE, key = "inspect-country")) { ClaimsStore.send("view", l.name); app.navigate(Route("dashboard")) }
            y -= CONTROL_H + 3
        }
        if (info == null && l.relation != "banished") {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.world.join"), Icons.INVITE, ButtonStyle.PRIMARY, pending = pending("join"), key = "join-country")) {
                Dialogs.confirm(app, tr("kami_claims.world.join.confirm.title", l.name), null, Icons.INVITE, listOf(
                    Consequence(tr("kami_claims.world.join.review", l.name)),
                    Consequence(tr("kami_claims.join.confirm.single"))
                ), tr("kami_libs.common.send_request"), "join", arrayOf(l.name))
            }
        }
    }

    private fun player(ui: Ui, r: Rect, p: PlayerLine) {
        val f = ui.sidePanel(r, 4, 8)
        val head = f.take(28)
        ui.avatar(p.id, head.x, head.y, 24, p.online)
        Draw.text(ui.g, p.name, head.x + 30, head.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, tr(if (p.online) "kami_libs.common.online" else "kami_libs.common.offline"), head.x + 30, head.y + 15, if (p.online) Palette.success else Palette.textMuted)
        ui.section(f.take(14), tr("kami_claims.world.citizenships"))
        if (p.citizenships.isEmpty()) f.take(12).let { Draw.text(ui.g, tr("kami_libs.common.no_country"), it.x, it.y, Palette.textMuted) }
        p.citizenships.forEach { c ->
            val row = f.take(20)
            val look = Vocabulary.rank(c.role)
            val x = row.x + Draw.leadIcon(ui.g, look.icon, row.x, row.y + 5) + 2
            ui.fitText(x, row.y, row.right - x, c.country, Palette.text)
            ui.fitText(x, row.y + 10, row.right - x, if (c.via.isNotEmpty()) tr("kami_claims.world.via", look.label, c.via) else look.label, look.color)
        }
        if (p.id == snap.me) return
        val rest = f.rest
        val pending = pending("gift:${p.id}")
        var y = rest.bottom - CONTROL_H
        if (p.giftIn > 0) {
            val half = (rest.w - 3) / 2
            if (ui.button(Rect(rest.x, y, half, CONTROL_H), tr("kami_libs.common.accept"), Icons.CHECK, ButtonStyle.PRIMARY, pending = pending, key = "gift-accept")) act("gift_accept", p.id, key = "gift:${p.id}")
            if (ui.button(Rect(rest.x + half + 3, y, rest.w - half - 3, CONTROL_H), tr("kami_libs.common.decline"), Icons.BAN, pending = pending, key = "gift-decline")) act("gift_decline", p.id, key = "gift:${p.id}")
            y -= 14
            ui.fitText(rest.x, y + 2, rest.w, tr("kami_claims.gift.incoming", Format.money(p.giftIn.toLong())), Palette.money)
            y -= CONTROL_H + 3
        }
        if (p.giftOut > 0) {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.gift.cancel", Format.money(p.giftOut.toLong())), Icons.REMOVE, pending = pending, key = "gift-cancel")) act("gift_cancel", p.id, key = "gift:${p.id}")
        } else if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.gift.action"), Icons.WITHDRAW, pending = pending, key = "gift-send")) Dialogs.gift(app, p.id, p.name)
    }
}
