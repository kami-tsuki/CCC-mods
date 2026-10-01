package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClientLocks
import kami.claims.research.Capacity
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.libs.ui.widget.Flags
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.libs.ui.app.consequences
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Line
import kami.claims.net.ProvinceLine
import kami.claims.net.ProvinceOfferLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.Route
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.wizardButtons
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*

class ProvincesPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.provinces")
    override val sections = listOf("world")
    override val help get() = listOf(
        Callout("provinces:status", tr("kami_claims.provinces.help.status"), tr("kami_claims.provinces.help.status.desc")),
        Callout("provinces:tabs", tr("kami_claims.provinces.tab.offers"), tr("kami_claims.provinces.help.offers.desc")),
        Callout("provinces:family", tr("kami_claims.provinces.help.bloc"), tr("kami_claims.provinces.help.bloc.desc"))
    )

    private var tab = 0
    private var selected: String? = null

    override fun opened(route: Route) {
        val focus = route.focus ?: return
        when {
            focus.startsWith("offer:") || focus.startsWith("request:") -> tab = 1
            focus.startsWith("province:") -> { tab = 0; selected = focus.substringAfter(":") }
        }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        ClientLocks.unlock(Capacity.PROVINCES, tr("kami_claims.nav.provinces"))?.takeIf { info.parent.isEmpty() }?.let { return ui.lockedPanel(r, tr("kami_claims.nav.provinces"), tr("kami_claims.provinces.locked.teaser"), it, Icons.CHAIN, "provinces-locked") }
        val offers = info.provinceInvites.size + info.provinceRequests.size
        val tabsRect = r.top(CONTROL_H)
        ui.anchor("provinces:tabs", tabsRect)
        ui.subTabs(tabsRect, listOf(TabItem(tr("kami_claims.nav.dashboard"), Icons.CHAIN), TabItem(tr("kami_claims.provinces.tab.offers"), Icons.SCROLL, offers, Severity.WARNING), TabItem(tr("kami_claims.provinces.tab.start"), Icons.HANDSHAKE)), tab, "province-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        when (tab) {
            0 -> overview(ui, body)
            1 -> offers(ui, body)
            else -> start(ui, body)
        }
    }

    private fun overview(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.parent.isNotEmpty()) {
            val box = r.top(250)
            ui.anchor("provinces:status", box)
            provinceStatus(ui, box)
            return
        }
        if (info.provinces.isEmpty()) {
            ui.anchor("provinces:status", r.top(120))
            val start = ui.emptyState(r, tr("kami_claims.budget.independent"), tr("kami_claims.provinces.empty.desc"),
                Illustrations.PROVINCE, tr("kami_claims.provinces.empty.action"), Icons.HANDSHAKE, "empty-provinces")
            if (start) tab = 2
            return
        }
        val tributes = info.provinces.sumOf { estimate(it.mode, it.amount, it.income) }
        val tiles = r.top(46).columns(4, 6)
        ui.statTile(tiles[0], tr("kami_claims.nav.provinces"), Format.number(info.provinces.size), Icons.CHAIN)
        ui.statTile(tiles[1], tr("kami_claims.provinces.tribute_day"), "≈ ${Format.number(tributes)}", Icons.INCOME, Palette.success)
        ui.statTile(tiles[2], tr("kami_claims.provinces.missed"), Format.number(info.provinces.sumOf { it.debt }), Icons.DEBT, if (info.provinces.any { it.debt > 0 }) Palette.danger else Palette.text)
        ui.statTile(tiles[3], tr("kami_claims.provinces.want_out"), Format.number(info.provinces.count { it.wantsIndependence }), Icons.BROKEN_CHAIN, if (info.provinces.any { it.wantsIndependence }) Palette.warning else Palette.text)
        val rest = r.dropTop(52)
        val treeH = hierarchyHeight(rest.w, info.provinces.size).coerceAtMost(rest.h / 2)
        val tree = rest.top(treeH)
        ui.anchor("provinces:family", tree)
        val root = GraphNode(info.name, info.name, tr("kami_claims.provinces.overlord"), badge = { g, b -> Flags.draw(g, b, info.color, info.flag.pattern, info.flag.emblem, info.flag.secondary) })
        val nodes = info.provinces.map { p ->
            GraphNode(p.name, p.name, when {
                p.wantsIndependence -> tr("kami_claims.province.wants_independence")
                p.debt > 0 -> tr("kami_claims.provinces.missed_count", p.debt)
                else -> tr("kami_claims.welcome.invite.row", trn("kami_claims.unit.citizen", p.members), trn("kami_claims.unit.chunk", p.chunks))
            }, Vocabulary.tribute(p.mode, p.amount), if (p.debt > 0) Palette.danger else Palette.geoProvince, if (p.wantsIndependence) Severity.WARNING else if (p.debt > 0) Severity.DANGER else null) { g, b -> Flags.draw(g, b, p.color, p.flag.pattern, p.flag.emblem, p.flag.secondary) }
        }
        ui.clip(tree) { ui.hierarchy(tree, root, nodes, selected)?.let { if (it != info.name) selected = it } }
        val chosen = info.provinces.firstOrNull { it.name == selected } ?: info.provinces.firstOrNull()?.also { selected = it.name } ?: return
        provinceDetail(ui, rest.dropTop(treeH, 6), chosen)
    }

    private fun countryOption(c: Line) = Option(c.name, c.name, null, tr("kami_claims.welcome.invite.row", trn("kami_claims.unit.citizen", c.members), trn("kami_claims.unit.chunk", c.chunks)), c.color)

    private fun estimate(mode: String, amount: Double, income: Long) = Vocabulary.perDay(if (mode == "percent") income * amount else amount).toLong()

    private fun provinceDetail(ui: Ui, r: Rect, p: ProvinceLine) {
        val body = ui.card(r, p.name, Icons.CHAIN, if (p.wantsIndependence) Severity.WARNING else null, key = "province-card")
        val cols = body.columns(2, 10)
        val f = Flow(cols[0], 2)
        ui.property(f.take(11), tr("kami_claims.provinces.tribute"), Vocabulary.tribute(p.mode, p.amount))
        ui.property(f.take(11), tr("kami_claims.provinces.estimate"), "≈ ${Format.perDay(Format.money(estimate(p.mode, p.amount, p.income)))}")
        ui.property(f.take(11), tr("kami_claims.provinces.missed"), "${p.debt} / ${snap.maxProvinceDebt}", if (p.debt > 0) Palette.danger else Palette.text)
        ui.property(f.take(11), tr("kami_claims.nav.citizens"), Format.number(p.members))
        ui.property(f.take(11), tr("kami_claims.kpi.land"), trn("kami_claims.unit.chunk", p.chunks))
        if (p.wantsIndependence) {
            f.skip(4)
            f.take(ui.callout(f.rest, Severity.WARNING, tr("kami_claims.provinces.asks_independence", p.name, trn("kami_claims.unit.day", limits?.independenceCooldownDays ?: 3))))
        }
        val g = Flow(cols[1], 3)
        val staff = lock("province")
        fun action(label: String, icon: Icon, style: ButtonStyle = ButtonStyle.SECONDARY, key: String, run: () -> Unit) {
            if (ui.button(g.take(CONTROL_H), label, icon, style, staff == null, staff, pending = pending(key), key = "prov-$key:${p.name}")) run()
        }
        if (p.wantsIndependence) {
            action(tr("kami_claims.provinces.grant"), Icons.BROKEN_CHAIN, ButtonStyle.PRIMARY, "province_release") { release(p, true) }
            action(tr("kami_claims.provinces.decline"), Icons.CROSS, key = "province_decline") {
                Dialogs.confirm(app, tr("kami_claims.provinces.decline.confirm.title", p.name), tr("kami_claims.provinces.decline.confirm.subtitle", p.name), Icons.CHAIN, listOf(
                    Consequence(tr("kami_claims.provinces.decline.notified", p.name)),
                    Consequence(tr("kami_claims.provinces.decline.cooldown", trn("kami_claims.unit.day", limits?.independenceCooldownDays ?: 3)), Severity.WARNING)
                ), tr("kami_claims.provinces.decline"), "province_decline", arrayOf(p.name))
            }
        }
        action(tr("kami_claims.provinces.manage", p.name), Icons.EDIT, if (p.wantsIndependence) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY, "view") { ClaimsStore.send("view", p.name); app.navigate(Route("dashboard")) }
        action(tr("kami_claims.provinces.change_tribute"), Icons.PERCENT, key = "province_tax") { termsDialog(tr("kami_claims.provinces.change_tribute.title", p.name), "province_tax", p.name, p.mode, p.amount, p.income) }
        if (p.debt > 0) action(tr("kami_claims.provinces.forgive"), Icons.UNDO, key = "province_forgive") {
            Dialogs.confirm(app, tr("kami_claims.provinces.forgive.confirm.title", p.name), null, Icons.UNDO, listOf(
                Consequence(tr("kami_claims.provinces.forgive.reset")), Consequence(tr("kami_claims.provinces.forgive.lost"), Severity.WARNING)
            ), tr("kami_claims.provinces.forgive.action"), "province_forgive", arrayOf(p.name))
        }
        action(tr("kami_claims.provinces.give"), Icons.FORWARD, key = "province_give") { giveDialog(p) }
        if (!p.wantsIndependence) action(tr("kami_claims.provinces.release"), Icons.BROKEN_CHAIN, ButtonStyle.DANGER, "province_release") { release(p, false) }
    }

    private fun release(p: ProvinceLine, requested: Boolean) {
        Dialogs.confirm(app, tr(if (requested) "kami_claims.provinces.grant.confirm.title" else "kami_claims.provinces.release.confirm.title", p.name), tr("kami_claims.provinces.release.confirm.subtitle"), Icons.BROKEN_CHAIN, listOf(
            Consequence(tr("kami_claims.provinces.release.final"), Severity.DANGER),
            Consequence(tr("kami_claims.provinces.release.tribute", Format.perDay(Format.money(estimate(p.mode, p.amount, p.income)))), Severity.WARNING),
            Consequence(tr("kami_claims.provinces.release.alliance"), Severity.WARNING),
            Consequence(tr("kami_claims.provinces.release.control"))
        ), tr(if (requested) "kami_claims.provinces.grant" else "kami_claims.provinces.release"), "province_release", arrayOf(p.name), danger = true, hold = true)
    }

    private fun giveDialog(p: ProvinceLine) {
        var target: String? = null
        val typed = TextState()
        app.open(Dialog(tr("kami_claims.provinces.give.title", p.name), tr("kami_claims.provinces.give.subtitle"), Icons.FORWARD, DialogKind.DESTRUCTIVE, 320) { s ->
            val candidates = snap.countries.filter { it.parent.isEmpty() && it.name != p.name && it.name != info?.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.provinces.give.field")); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), candidates.map { countryOption(it) }, target, tr("kami_claims.provinces.choose_country"), key = "give-target")?.let { target = it }
            y += CONTROL_H + 6
            y += consequences(s.body.x, y, s.body.w, listOf(
                Consequence(target?.let { tr("kami_claims.provinces.give.takes_over", it, p.name) } ?: tr("kami_claims.provinces.give.takes_over.any", p.name), Severity.WARNING),
                Consequence(tr("kami_claims.provinces.give.final"), Severity.DANGER)
            )) + 4
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.dialog.type_to_confirm", p.name)); y += 11
            textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typed, p.name, key = "give-typed"); y += CONTROL_H + 4
            s.used = y - s.body.y
            val ready = target != null && typed.text.equals(p.name, true)
            dialogButtons(s, tr("kami_claims.provinces.give.action"), ready, if (target == null) tr("kami_claims.provinces.choose_country.first") else tr("kami_claims.dialog.type_required", p.name), hold = true) {
                ClaimsStore.send("province_give", p.name, target!!, "confirm", key = "province_give")
                s.close()
            }
        })
    }

    private fun provinceStatus(ui: Ui, r: Rect) {
        val info = info ?: return
        val body = ui.card(r, tr("kami_claims.provinces.status.title", info.parent), Icons.CHAIN, Severity.WARNING, key = "status")
        val f = Flow(body, 3)
        val top = f.take(34)
        Flags.draw(ui.g, Rect(top.x, top.y + 2, 36, 26), info.parentColor, 0, 0, 0xFFFFFF)
        Draw.text(ui.g, tr("kami_claims.confirm.province.tribute", Vocabulary.tribute(info.taxMode, info.taxAmount)), top.x + 44, top.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, tr("kami_claims.provinces.status.estimate", Format.perDay(Format.money(estimate(info.taxMode, info.taxAmount, info.income))), info.provinceDebt, snap.maxProvinceDebt), top.x + 44, top.y + 16, if (info.provinceDebt > 0) Palette.danger else Palette.textMuted)
        val cols = f.take(100).columns(2, 10)
        rightsList(ui, cols[0], tr("kami_claims.provinces.rights.may", info.parent), snap.delegable, Palette.warning, Icons.WARNING)
        rightsList(ui, cols[1], tr("kami_claims.provinces.rights.keep"), snap.kept, Palette.success, Icons.CHECK)
        f.take(ui.callout(f.rest, Severity.DANGER, tr("kami_claims.confirm.province.bound", info.parent)))
        val row = f.take(CONTROL_H)
        val staff = lock("province")
        when {
            info.independenceRequested -> {
                ui.statusPill(row.x, row.y + 4, tr("kami_claims.provinces.independence.pending", info.parent), Severity.WARNING)
                if (ui.edgeButton(Rect(row.x, row.y, row.w, CONTROL_H), tr("kami_claims.provinces.independence.withdraw"), enabled = staff == null, disabledReason = staff, pending = pending("province_withdraw"), key = "withdraw-indep")) act("province_withdraw")
            }
            info.independenceCooldown > 0 -> ui.statusPill(row.x, row.y + 4, tr("kami_claims.provinces.independence.cooldown", Format.duration(info.independenceCooldown)), Severity.DANGER)
            else -> {
                val request = tr("kami_claims.provinces.independence.request")
                if (ui.edgeButton(Rect(row.x, row.y, row.w, CONTROL_H), request, Icons.BROKEN_CHAIN, ButtonStyle.PRIMARY, staff == null, staff, pending = pending("province_independence"), left = true, key = "req-indep")) {
                    Dialogs.confirm(app, tr("kami_claims.provinces.independence.confirm.title", info.parent), tr("kami_claims.provinces.independence.confirm.subtitle"), Icons.BROKEN_CHAIN, listOf(
                        Consequence(tr("kami_claims.provinces.independence.decides", info.parent)),
                        Consequence(tr("kami_claims.provinces.independence.wait", trn("kami_claims.unit.day", limits?.independenceCooldownDays ?: 3)), Severity.WARNING)
                    ), tr("kami_claims.world.join.action"), "province_independence", emptyArray())
                }
            }
        }
    }

    private fun rightsList(ui: Ui, r: Rect, title: String, items: List<String>, color: Int, icon: Icon) {
        Draw.fill(ui.g, r, Palette.alpha(color, 0x14))
        Draw.text(ui.g, title.uppercase(Format.locale), r.x + 5, r.y + 4, color)
        var y = r.y + 16
        items.forEach { text ->
            val x = r.x + 5 + Draw.leadIcon(ui.g, icon, r.x + 5, y + Draw.LINE / 2 - 1) + 2
            y += Draw.paragraph(ui.g, tr(text), x, y, r.right - x - 4) + 2
        }
    }

    private fun offers(ui: Ui, r: Rect) {
        val info = info ?: return
        val f = Flow(r, 6)
        ui.section(f.take(14), tr("kami_claims.provinces.offers.invites"), Format.number(info.provinceInvites.size))
        if (info.provinceInvites.isEmpty()) f.take(12).let { Draw.text(ui.g, tr("kami_claims.provinces.offers.invites.empty"), it.x, it.y, Palette.textMuted) }
        info.provinceInvites.forEach { o ->
            val row = f.take(OFFER_H)
            val focus = app.isFocus("offer:${o.name}")
            Draw.sprite(ui.g, Sprites.CARD, row)
            ui.attention(row, focus)
            Flags.draw(ui.g, Rect(row.x + 6, row.y + 7, 22, 16), o.color, 0, 0, 0xFFFFFF)
            Draw.text(ui.g, tr(if (o.answered) "kami_claims.alert.offer.answered" else "kami_claims.alert.offer.title", o.name), row.x + 36, row.y + 5, TextStyle.HEADING)
            Draw.text(ui.g, tr("kami_claims.provinces.offer.row", Vocabulary.tribute(o.mode, o.amount), trn("kami_claims.unit.citizen", o.members), Format.until(o.until)), row.x + 36, row.y + 17, Palette.textMuted)
            val review = tr("kami_claims.alert.offer.action")
            if (ui.edgeButton(Rect(row.x, row.y + (OFFER_H - CONTROL_H) / 2, row.w - 6, CONTROL_H), review, Icons.SCROLL, ButtonStyle.PRIMARY, lock("province") == null, lock("province"), key = "review:${o.name}")) agreement(o)
        }
        f.skip(6)
        ui.section(f.take(14), tr("kami_claims.provinces.offers.requests"), Format.number(info.provinceRequests.size))
        if (info.parent.isNotEmpty()) { f.take(12).let { Draw.text(ui.g, tr("kami_claims.error.province_nested"), it.x, it.y, Palette.textMuted) }; return }
        if (info.provinceRequests.isEmpty()) f.take(12).let { Draw.text(ui.g, tr("kami_claims.provinces.offers.requests.empty"), it.x, it.y, Palette.textMuted) }
        info.provinceRequests.forEach { name ->
            val line = snap.countries.firstOrNull { it.name == name }
            val row = f.take(OFFER_H)
            Draw.sprite(ui.g, Sprites.CARD, row)
            ui.attention(row, app.isFocus("request:$name"))
            Flags.draw(ui.g, Rect(row.x + 6, row.y + 7, 22, 16), line?.color ?: 0x888888, line?.flag?.pattern ?: 0, line?.flag?.emblem ?: 0, line?.flag?.secondary ?: 0xFFFFFF)
            val staff = lock("province")
            val terms = tr("kami_claims.provinces.set_terms")
            val deny = tr("kami_claims.chat.deny")
            val tw = buttonWidth(terms, Icons.PERCENT)
            val dw = buttonWidth(deny)
            val textW = row.w - 36 - tw - dw - 20
            Draw.text(ui.g, Draw.fit(name, textW, TextStyle.HEADING), row.x + 36, row.y + 5, TextStyle.HEADING)
            Draw.text(ui.g, Draw.fit(line?.let { countryOption(it).description } ?: "", textW), row.x + 36, row.y + 17, Palette.textMuted)
            val actions = Row(Rect(row.x, row.y + (OFFER_H - CONTROL_H) / 2, row.w - 6, CONTROL_H))
            if (ui.edgeButton(actions, deny, enabled = staff == null, disabledReason = staff, key = "deny:$name")) act("province_deny", name)
            if (ui.edgeButton(actions, terms, Icons.PERCENT, ButtonStyle.PRIMARY, staff == null, staff, key = "terms:$name")) termsDialog(tr("kami_claims.provinces.terms.title", name), "province_approve", name, "percent", 0.1, line?.income ?: 0)
        }
    }

    private fun termsDialog(title: String, action: String, target: String, mode0: String, amount0: Double, income: Long) {
        var mode = mode0
        var percent = (amount0 * 100).coerceIn(0.0, 100.0)
        val flat = NumberState(if (mode0 == "flat") amount0.toLong() else 10)
        app.open(Dialog(title, if (action == "province_approve") tr("kami_claims.provinces.terms.subtitle") else null, Icons.PERCENT, DialogKind.CONFIRM, 320) { s ->
            val limits = snap.limits
            val min = (limits?.tributeMin ?: 0.0) * 100
            val max = (limits?.tributeMax ?: 0.5) * 100
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.provinces.terms.type")); y += 11
            segmented(Rect(s.body.x, y, s.body.w, CONTROL_H), listOf(
                Option("percent", tr("kami_claims.provinces.terms.percent"), Icons.PERCENT, tr("kami_claims.provinces.terms.percent.desc")),
                Option("flat", tr("kami_claims.provinces.terms.flat"), Icons.COIN, tr("kami_claims.provinces.terms.flat.desc"))
            ), mode, key = "mode")?.let { mode = it }
            y += CONTROL_H + 8
            if (mode == "percent") {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.provinces.terms.share"), Format.percent(percent / 100)); y += 11
                slider(Rect(s.body.x, y, s.body.w, CONTROL_H), percent.coerceIn(min, max), min, max, 1.0, format = { Format.percent(it / 100) }, key = "percent")?.let { percent = it }
            } else {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.provinces.terms.per_day")); y += 11
                numberField(Rect(s.body.x, y, s.body.w, CONTROL_H), flat, 0, 100_000, unit = "◎", key = "flat")
            }
            y += CONTROL_H + 6
            val estimate = if (mode == "percent") (income * percent / 100).toLong() else flat.value
            property(Rect(s.body.x, y, s.body.w, 11), tr("kami_claims.provinces.terms.today"), "≈ ${Format.perDay(Format.money(estimate))}", Palette.money); y += 14
            y += consequences(s.body.x, y, s.body.w, listOf(
                Consequence(tr("kami_claims.provinces.terms.unpaid")),
                Consequence(tr("kami_claims.provinces.terms.later"))
            ))
            s.used = y - s.body.y + 4
            dialogButtons(s, tr(if (action == "province_approve") "kami_claims.provinces.terms.send" else "kami_claims.provinces.terms.save")) {
                ClaimsStore.send(action, target, mode, if (mode == "percent") percent.toInt().toString() else flat.value.toString(), key = action)
                s.close()
            }
        })
    }

    private fun agreement(o: ProvinceOfferLine) {
        var understood = false
        val typed = TextState()
        app.open(Dialog(tr("kami_claims.confirm.province.title", o.name), tr("kami_claims.confirm.province.authority", info?.name ?: ""), Icons.CHAIN, DialogKind.DESTRUCTIVE, 400,
            listOf(tr("kami_claims.provinces.sign.step.terms"), tr("kami_claims.provinces.sign.step.authority"), tr("kami_claims.provinces.sign.step.sign")),
            stale = { if (ClaimsStore.info?.provinceInvites?.none { it.name == o.name } == true) tr("kami_claims.provinces.sign.stale") else null }) { s ->
            val info = ClaimsStore.info ?: return@Dialog
            val b = s.body
            var y = b.y
            when (s.step) {
                0 -> {
                    g.blitSprite(Illustrations.PROVINCE, b.x, y, 32, 32)
                    y += maxOf(36, Draw.paragraph(g, tr("kami_claims.provinces.sign.intro", o.name, info.name), b.x + 40, y + 2, b.w - 40) + 6)
                    property(Rect(b.x, y, b.w, 11), tr("kami_claims.provinces.tribute"), Vocabulary.tribute(o.mode, o.amount), Palette.money); y += 13
                    property(Rect(b.x, y, b.w, 11), tr("kami_claims.provinces.terms.today"), tr("kami_claims.provinces.sign.estimate", Format.perDay(Format.money(estimate(o.mode, o.amount, info.income))), Format.money(info.income)), Palette.money); y += 13
                    property(Rect(b.x, y, b.w, 11), tr("kami_claims.provinces.sign.unpaid"), tr("kami_claims.provinces.sign.unpaid.value", o.name), Palette.warning); y += 13
                    property(Rect(b.x, y, b.w, 11), tr("kami_claims.provinces.sign.expires"), Format.until(o.until)); y += 17
                    if (info.provinces.isNotEmpty()) y += callout(Rect(b.x, y, b.w, 0), Severity.WARNING, tr("kami_claims.provinces.sign.nested", trn("kami_claims.unit.province", info.provinces.size), o.name)) + 4
                }
                1 -> {
                    Draw.fill(g, Rect(b.x, y, b.w, 16), Palette.alpha(Palette.danger, 0x30))
                    Draw.leadIcon(g, Icons.WARNING, b.x + 4, y + 8)
                    Draw.text(g, tr("kami_claims.provinces.sign.warning").uppercase(Format.locale), b.x + 20, y + 4, TextStyle.TITLE, Palette.danger)
                    y += 22
                    val cols = Rect(b.x, y, b.w, 118).columns(listOf(1.2f, 1f), 8)
                    rightsList(this, cols[0], tr("kami_claims.provinces.rights.may", o.name), snap.delegable, Palette.danger, Icons.WARNING)
                    rightsList(this, cols[1], tr("kami_claims.provinces.rights.keep"), snap.kept, Palette.success, Icons.CHECK)
                    y += 124
                    y += callout(Rect(b.x, y, b.w, 0), Severity.DANGER, tr("kami_claims.provinces.sign.bound", o.name)) + 4
                    y += callout(Rect(b.x, y, b.w, 0), Severity.INFO, tr("kami_claims.provinces.sign.allies", o.name)) + 6
                    checkbox(Rect(b.x, y, b.w, 14), tr("kami_claims.provinces.sign.understood", info.name), understood, key = "understood")?.let { understood = it }
                    y += 18
                }
                else -> {
                    Draw.paragraph(g, tr("kami_claims.provinces.sign.howto"), b.x, y, b.w); y += 16
                    fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_claims.dialog.type_to_confirm", info.name)); y += 11
                    textField(Rect(b.x, y, b.w, CONTROL_H), typed, info.name, key = "sign-name", autoFocus = true); y += CONTROL_H + 6
                    y += consequences(b.x, y, b.w, listOf(Consequence(tr("kami_claims.provinces.sign.notified")))) + 2
                }
            }
            s.used = y - b.y
            val reason = when (s.step) {
                1 -> if (!understood) tr("kami_claims.provinces.sign.tick") else null
                2 -> if (!typed.text.equals(info.name, true)) tr("kami_claims.dialog.type_required", info.name) else null
                else -> null
            }
            wizardButtons(s, tr("kami_claims.provinces.sign.action"), reason == null, reason, hold = s.step == 2) {
                ClaimsStore.send("province_accept", o.name, "confirm", key = "province_accept")
                s.close()
            }
        })
    }

    private fun start(ui: Ui, r: Rect) {
        val info = info ?: return
        val cards = r.top(START_CARD_H).columns(2, 6)
        val staff = lock("province")
        val isProvince = info.parent.isNotEmpty()
        val a = ui.card(cards[0], tr("kami_claims.provinces.start.invite"), Icons.INVITE)
        Draw.paragraph(ui.g, tr("kami_claims.provinces.start.invite.desc"), a.x, a.y, a.w)
        if (ui.button(Rect(a.x, a.bottom - CONTROL_H, a.w, CONTROL_H), tr("kami_claims.provinces.start.invite.action"), Icons.INVITE, ButtonStyle.PRIMARY, staff == null && !isProvince, staff ?: tr("kami_claims.error.province_nested"), key = "invite-country")) inviteDialog()
        val b = ui.card(cards[1], tr("kami_claims.provinces.start.request"), Icons.CHAIN, Severity.WARNING)
        Draw.paragraph(ui.g, tr("kami_claims.provinces.start.request.desc"), b.x, b.y, b.w)
        if (ui.button(Rect(b.x, b.bottom - CONTROL_H, b.w, CONTROL_H), tr("kami_claims.provinces.start.request.action"), Icons.CHAIN, enabled = staff == null && !isProvince, disabledReason = staff ?: tr("kami_claims.error.already_province"), key = "request-country")) requestDialog()
        val explain = r.dropTop(START_CARD_H + 6)
        ui.card(explain, tr("kami_claims.provinces.how"), Icons.INFO).let { c ->
            var y = c.y
            (1..5).map { tr("kami_claims.provinces.how.$it") }.forEach { line ->
                val x = c.x + Draw.leadIcon(ui.g, Icons.CHEVRON_RIGHT, c.x, y + Draw.LINE / 2 - 1) + 2
                y += Draw.paragraph(ui.g, line, x, y, c.right - x) + 3
            }
        }
    }

    private fun inviteDialog() {
        var target: String? = null
        app.open(Dialog(tr("kami_claims.provinces.start.invite"), null, Icons.INVITE, DialogKind.CONFIRM, 300) { s ->
            val candidates = snap.countries.filter { it.parent.isEmpty() && it.name != info?.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.world.col.country")); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), candidates.map { countryOption(it) }, target, tr("kami_claims.provinces.choose_country"), key = "invite-target")?.let { target = it }
            y += CONTROL_H + 8
            y += consequences(s.body.x, y, s.body.w, listOf(Consequence(tr("kami_claims.provinces.invite.next")))) + 4
            s.used = y - s.body.y
            dialogButtons(s, tr("kami_claims.provinces.invite.action"), target != null, tr("kami_claims.provinces.choose_country.first")) {
                s.close()
                val name = target ?: return@dialogButtons
                val line = snap.countries.firstOrNull { it.name == name }
                termsDialog(tr("kami_claims.provinces.terms.title", name), "province_invite", name, "percent", 0.1, line?.income ?: 0)
            }
        })
    }

    private fun requestDialog() {
        var target: String? = null
        app.open(Dialog(tr("kami_claims.provinces.start.request"), null, Icons.CHAIN, DialogKind.CONFIRM, 320) { s ->
            val candidates = snap.countries.filter { it.parent.isEmpty() && it.name != info?.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.provinces.overlord")); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), candidates.map { countryOption(it) }, target, tr("kami_claims.provinces.choose_country"), key = "request-target")?.let { target = it }
            y += CONTROL_H + 8
            y += consequences(s.body.x, y, s.body.w, listOf(
                Consequence(tr("kami_claims.provinces.request.answer")),
                Consequence(tr("kami_claims.provinces.request.sign"), Severity.SUCCESS)
            )) + 4
            s.used = y - s.body.y
            dialogButtons(s, tr("kami_claims.world.join.action"), target != null, tr("kami_claims.provinces.choose_country.first")) {
                ClaimsStore.send("province_request", target!!, key = "province_request")
                s.close()
            }
        })
    }
}

private const val OFFER_H = 30
private const val START_CARD_H = 112
