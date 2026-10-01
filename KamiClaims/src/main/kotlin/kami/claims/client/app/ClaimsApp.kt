package kami.claims.client.app

import kami.libs.ui.widget.Flags
import kami.libs.ui.text.trJson
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.client.ClientClaims
import kami.claims.client.app.pages.*
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientResearch
import kami.claims.research.Capacity
import kami.claims.client.store.Outcome
import kami.claims.service.AlertLine
import kami.claims.client.store.ResearchChange
import kami.libs.ui.anim.countUp
import kami.libs.ui.anim.flash
import kami.libs.ui.anim.floatText
import kami.libs.ui.anim.gained
import kami.libs.ui.anim.tween
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.Toast
import kami.libs.ui.app.Tour
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.UiScale
import kami.libs.ui.core.Key
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.widget.badge
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.button
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.iconSlot
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.iconFor
import kami.libs.ui.widget.PopoverAlign
import kami.libs.ui.widget.popover
import kami.libs.ui.widget.scroll
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class ClaimsApp : KamiApp() {
    override val home get() = if (ClaimsStore.info == null) Route("welcome") else Route("dashboard")
    private var bellOpen = false
    private var lastRoute = ""

    val isOpen get() = (Minecraft.getInstance().screen as? AppScreen)?.app === this

    init {
        ClaimsStore.onOutcome(::outcome)
        ClientResearch.onChange(::researched)
    }

    private fun researched(change: ResearchChange) {
        if (!isOpen || change.completed.isEmpty()) return
        UiSound.complete()
        if (route.page == "research") return
        change.completed.take(MAX_COMPLETE_TOASTS).forEach { key ->
            val label = ClientResearch.node(key)?.label()?.resolve() ?: return@forEach
            toasts.push(Toast(Severity.SUCCESS, tr("kami_claims.research.complete", label), null, tr("kami_claims.research.action.show")) { navigate(Route("research", focus = key)) }, sound = false)
        }
    }

    override fun create(id: String): Page = when (id) {
        "welcome" -> WelcomePage(this)
        "dashboard" -> DashboardPage(this)
        "statistics" -> StatisticsPage(this)
        "map" -> MapPage(this)
        "chunks" -> ChunksPage(this)
        "plots" -> PlotsPage(this)
        "budget" -> BudgetPage(this)
        "ledger" -> LedgerPage(this)
        "loans" -> LoansPage(this)
        "citizens" -> CitizensPage(this)
        "jobs" -> JobsPage(this)
        "ranks" -> RanksPage(this)
        "protection" -> ProtectionPage(this)
        "plotlaw" -> PlotLawPage(this)
        "identity" -> IdentityPage(this)
        "relations" -> RelationsPage(this)
        "provinces" -> ProvincesPage(this)
        "world" -> WorldPage(this)
        "research" -> ResearchPage(this)
        "levels" -> ResearchLevelsPage(this)
        "queue" -> ResearchQueuePage(this)
        "research_buffs" -> ResearchBuffsPage(this)
        "help" -> HelpPage(this)
        "settings" -> SettingsPage(this)
        else -> DashboardPage(this)
    }

    fun lock(cap: String) = ClientLocks.cap(cap)

    private fun alertsFor(page: String) = visibleAlerts().filter { it.page == page }

    fun visibleAlerts(): List<AlertLine> = ClaimsStore.snap?.alerts?.filter { it.id !in ClientClaims.prefs.dismissed } ?: emptyList()

    private fun badge(page: String, extra: Int = 0): () -> NavBadge? = {
        val alerts = alertsFor(page)
        val count = alerts.size + extra
        if (count == 0) null else NavBadge(count, alerts.map { Severity.of(it.severity) }.maxByOrNull { it.ordinal } ?: Severity.INFO)
    }

    private fun needsCountry(): String? = if (ClaimsStore.info == null) tr("kami_claims.lock.no_country") else null

    private fun group(id: String, label: String, items: List<NavItem>) = NavGroup(label, items, id, collapsible = true)

    override val collapsedGroups get() = ClientClaims.prefs.collapsedGroups

    override fun collapseChanged() { ClientClaims.savePrefs() }

    override val navKey get() = ClaimsStore.info == null

    override fun buildNav(): List<NavGroup> {
        val none = ClaimsStore.info == null
        val overview = if (none) listOf(NavItem("welcome", tr("kami_claims.nav.welcome"), Icons.FLAG, badge("welcome")))
        else listOf(NavItem("dashboard", tr("kami_claims.nav.dashboard"), Icons.DASHBOARD, badge("dashboard")), NavItem("statistics", tr("kami_claims.nav.statistics"), Icons.STATS, lock = { lock("details") }))
        return listOf(
            group("overview", tr("kami_claims.nav.group.overview"), overview),
            group("territory", tr("kami_claims.nav.group.territory"), listOf(
                NavItem("map", tr("kami_claims.nav.map"), Icons.MAP),
                NavItem("chunks", tr("kami_claims.nav.chunks"), Icons.AREA, badge("chunks"), ::needsCountry),
                NavItem("plots", tr("kami_claims.nav.plots"), Icons.HOUSE, badge("plots"), ::needsCountry)
            )),
            group("research", tr("kami_claims.nav.group.research"), listOf(
                NavItem("research", tr("kami_claims.nav.research"), Icons.TREE, badge("research"), ::needsCountry),
                NavItem("levels", tr("kami_claims.nav.levels"), Icons.STAR, lock = ::needsCountry),
                NavItem("queue", tr("kami_claims.nav.queue"), Icons.SCROLL, lock = ::needsCountry),
                NavItem("research_buffs", tr("kami_claims.nav.buffs"), Icons.SHIELD, lock = ::needsCountry, teaser = ClientLocks::buffsTeaser)
            )),
            group("economy", tr("kami_claims.nav.group.economy"), listOf(
                NavItem("budget", tr("kami_claims.nav.budget"), Icons.SCALES, badge("budget"), { needsCountry() ?: lock("details") }),
                NavItem("ledger", tr("kami_claims.nav.ledger"), Icons.LEDGER, lock = { needsCountry() ?: lock("details") }),
                NavItem("loans", tr("kami_claims.nav.loans"), Icons.COIN, badge("loans"), ::needsCountry, ClientLocks::loansTeaser)
            )),
            group("society", tr("kami_claims.nav.group.society"), listOf(
                NavItem("citizens", tr("kami_claims.nav.citizens"), Icons.PEOPLE, badge("citizens"), ::needsCountry),
                NavItem("jobs", tr("kami_claims.nav.jobs"), Icons.TOOL, badge("jobs"), ::needsCountry),
                NavItem("ranks", tr("kami_claims.nav.ranks"), Icons.STAR, lock = ::needsCountry)
            )),
            group("law", tr("kami_claims.nav.group.law"), listOf(
                NavItem("protection", tr("kami_claims.nav.protection"), Icons.SHIELD, lock = ::needsCountry),
                NavItem("plotlaw", tr("kami_claims.nav.plotlaw"), Icons.SCROLL, lock = ::needsCountry),
                NavItem("identity", tr("kami_claims.nav.identity"), Icons.FLAG, lock = ::needsCountry)
            )),
            group("diplomacy", tr("kami_claims.nav.group.diplomacy"), listOf(
                NavItem("relations", tr("kami_claims.nav.relations"), Icons.HANDSHAKE, lock = ::needsCountry),
                NavItem("provinces", tr("kami_claims.nav.provinces"), Icons.CHAIN, badge("provinces"), ::needsCountry, { ClientLocks.unlock(Capacity.PROVINCES, tr("kami_claims.nav.provinces")) }),
                NavItem("world", tr("kami_claims.nav.world"), Icons.GLOBE)
            )),
            group("system", tr("kami_claims.nav.group.system"), listOf(NavItem("help", tr("kami_claims.nav.help"), Icons.HELP), NavItem("settings", tr("kami_claims.nav.settings"), Icons.SETTINGS)))
        )
    }

    override fun beforeFrame() {
        val p = ClientClaims.prefs
        ui.reduceMotion = p.reduceMotion
        ui.tooltipDelay = p.tooltipDelay.toLong()
        UiSound.volume = p.sounds
        UiScale.factor = p.uiScale
        if (ClaimsStore.info == null && route.page != "welcome" && page(route.page).let { it is ClaimsPage && it.needsCountry }) navigate(Route("welcome"), record = false, sound = false)
        if (route.page != lastRoute) {
            lastRoute = route.page
            (page(route.page) as? ClaimsPage)?.let { ClaimsStore.watch(it.sections) }
        }
        if (tour == null && !ClientClaims.prefs.tourDone && ClaimsStore.info != null) {
            ClientClaims.finishTour()
            startTour()
        }
    }

    fun startTour() {
        tour = Tour(listOf(
            Callout("topbar", tr("kami_claims.tour.topbar"), tr("kami_claims.tour.topbar.desc"), Route("dashboard")),
            Callout("bell", tr("kami_claims.common.alerts"), tr("kami_claims.tour.bell.desc")),
            Callout("dashboard:attention", tr("kami_claims.tour.attention"), tr("kami_claims.tour.attention.desc")),
            Callout("nav:map", tr("kami_claims.nav.map"), tr("kami_claims.tour.map.desc")),
            Callout("nav:budget", tr("kami_claims.nav.budget"), tr("kami_claims.tour.budget.desc")),
            Callout("nav:protection", tr("kami_claims.nav.protection"), tr("kami_claims.tour.protection.desc")),
            Callout("nav:provinces", tr("kami_claims.cap.province"), tr("kami_claims.tour.provinces.desc")),
            Callout("page-help", tr("kami_claims.tour.help"), tr("kami_claims.tour.help.desc"))
        )) {
            ClientClaims.finishTour()
        }
    }

    override fun topBar(ui: Ui, r: Rect) {
        val snap = ClaimsStore.snap ?: return
        val info = snap.info
        val row = Row(r.inset(4, 0, 4, 0), 4)
        val identity = row.take(if (compact) 120 else 170)
        if (info != null) {
            Flags.draw(ui.g, Rect(identity.x, identity.y + 6, 16, 12), info.color, info.flag.pattern, info.flag.emblem, info.flag.secondary)
            Draw.text(ui.g, Draw.fit(info.name, identity.w - 22), identity.x + 21, identity.y + 3, TextStyle.HEADING)
            val look = Vocabulary.rank(info.rank)
            Draw.text(ui.g, Draw.fit(look.label, identity.w - 22), identity.x + 21, identity.y + 13, look.color)
        } else {
            Draw.leadIcon(ui.g, Icons.FLAG, identity.x, identity.centerY, Palette.textMuted)
            Draw.text(ui.g, tr("kami_libs.common.no_country"), identity.x + 18, identity.y + 3, TextStyle.HEADING, Palette.textMuted)
            Draw.text(ui.g, tr("kami_claims.help.term.nomansland"), identity.x + 18, identity.y + 13, Palette.textMuted)
        }
        if (ui.iconButton(row.iconSlot(), Icons.CLOSE, tr("kami_libs.common.close.tooltip"), key = "app-close")) Minecraft.getInstance().setScreen(null)
        bell(ui, row.iconSlot())
        if (info == null) {
            kpi(ui, row.takeFromRight(110), Icons.COIN, tr("kami_claims.kpi.funds"), Format.money(snap.funds, compact = true), Palette.money, { Tip.text(tr("kami_claims.kpi.funds.tooltip"), tr("kami_claims.kpi.funds")) }, null)
            return
        }
        if (ClientResearch.state.country.isNotEmpty()) {
            if (compact) levelCell(ui, row.takeFromRight(LEVEL_COMPACT_W)) else levelKpi(ui, row.takeFromRight(LEVEL_W))
        }
        val kpis = row.rest
        val cells = kpis.columns(if (compact) 1 else 2, 6)
        val net = info.income - info.upkeep - info.jobs
        val history = snap.history
        val weekAgo = history.getOrNull(history.size - 8)?.treasury
        val trend = weekAgo?.let { info.treasury - it }
        kpi(ui, cells[0], Icons.TREASURY, tr("kami_claims.kpi.treasury"), Format.money(ui.countUp("treasury", info.treasury), compact = true), Palette.money,
            { Tip(tr("kami_claims.kpi.treasury"), listOf(tr("kami_claims.kpi.treasury.balance", Format.money(info.treasury)) to Palette.money,
                (trend?.let { tr("kami_claims.kpi.treasury.week", Format.signedMoney(it)) } ?: tr("kami_claims.kpi.treasury.no_history")) to Palette.textSecondary,
                tr("kami_claims.kpi.net.tax", Format.signedMoney(info.income)) to Palette.success,
                tr("kami_claims.kpi.net.upkeep", Format.signedMoney(-info.upkeep)) to Palette.danger,
                tr("kami_claims.kpi.net.wages", Format.signedMoney(-info.jobs)) to Palette.danger,
                (if (net >= 0) tr("kami_claims.kpi.runway.positive") else tr("kami_claims.kpi.runway.negative", Format.perDay(Format.money(-net)), Format.days(info.treasury / -net))) to Palette.textSecondary), keys = tr("kami_claims.kpi.open_budget")) }, "budget", info.treasury)
        if (!compact) {
            val online = info.members.count { it.online }
            kpi(ui, cells[1], Icons.PEOPLE, tr("kami_claims.kpi.citizens"), "$online/${info.members.size}", Palette.text,
                { Tip.text(tr("kami_claims.kpi.citizens.tooltip", Format.number(online), Format.number(info.members.size)), tr("kami_claims.kpi.citizens")) }, "citizens")
        }
    }

    private fun levelKpi(ui: Ui, r: Rect) {
        kpi(ui, r, Icons.STAR, tr("kami_libs.common.level"), tr("kami_libs.lock.ui.level", ClientResearch.state.level), Palette.brass, { Tip.text(xpText(), tr("kami_libs.common.level")) }, "levels")
        xpStrip(ui, r)
    }

    private fun levelCell(ui: Ui, r: Rect) {
        val hit = ui.clickable("kpi:level-cell", r)
        if (ui.hovering(r)) Draw.fill(ui.g, r.inset(0, 2), Palette.hover)
        val label = tr("kami_claims.kpi.level.short", ClientResearch.state.level)
        Draw.text(ui.g, label, r.x + (r.w - Draw.width(label)) / 2, r.y + 8, Palette.brass)
        xpStrip(ui, r)
        ui.tooltip("kpi:level-cell", r) { Tip.text(xpText(), tr("kami_libs.common.level")) }
        if (hit) navigate(Route("levels"))
    }

    private fun xpStrip(ui: Ui, r: Rect) {
        val s = ClientResearch.state
        ui.gained("xp-gain", s.xp).takeIf { it > 0 }?.let { ui.floatText(r.centerX, r.bottom - 2, tr("kami_claims.kpi.xp_gain", Format.number(it)), Palette.brass) }
        if (!ClientResearch.xpTracked) return
        Draw.thinBar(ui.g, Rect(r.x + 4, r.bottom - 3, r.w - 8, 2), ui.tween("level-xp", ClientResearch.xpFraction, 500), Palette.brass)
    }

    private fun kpi(ui: Ui, r: Rect, icon: Icon, label: String, value: String, color: Int, tip: (() -> Tip?)?, page: String?, flashValue: Long? = null) {
        val key = "kpi:$label"
        ui.anchor(key, r)
        if (page != null && ui.hovering(r)) Draw.fill(ui.g, r.inset(0, 2), Palette.hover)
        flashValue?.let { v -> ui.flash(key, v).takeIf { it != 0 }?.let { Draw.fill(ui.g, r.inset(0, 2), it) } }
        val textX = r.x + 6
        Draw.text(ui.g, Draw.fit(label.uppercase(Format.locale), r.right - textX), textX, r.y + 3, Palette.textMuted)
        Draw.text(ui.g, Draw.fit(value, r.right - textX), textX, r.y + 13, color)
        tip?.let { build -> ui.tooltip(key, r) { build() } }
        if (page != null && ui.clickable(key, r)) navigate(Route(page))
    }

    fun runwayText(treasury: Long, net: Long) = if (net >= 0) tr("kami_claims.runway.stable") else trn("kami_claims.unit.day", treasury / -net)
    fun runwayColor(treasury: Long, net: Long) = when {
        net >= 0 -> Palette.success
        treasury / -net < 3 -> Palette.danger
        treasury / -net < 14 -> Palette.warning
        else -> Palette.text
    }

    private fun bell(ui: Ui, r: Rect) {
        val alerts = visibleAlerts()
        ui.anchor("bell", r)
        if (ui.iconButton(r, Icons.BELL, if (alerts.isEmpty()) tr("kami_claims.alerts.none") else trn("kami_claims.unit.alert", alerts.size), key = "bell")) bellOpen = !bellOpen
        if (alerts.isNotEmpty()) {
            val worst = alerts.map { Severity.of(it.severity) }.maxBy { it.ordinal }
            ui.badge(r.right - 8, r.y - 2, alerts.size.toString(), worst)
        }
        if (!bellOpen) return
        ui.onEscape(60) { bellOpen = false }
        val w = 250
        val itemsH = alerts.sumOf { alertHeight(it, w - 12) }
        ui.popover(r, w, (itemsH + 28).coerceIn(50, 300), align = PopoverAlign.END, onOutside = { bellOpen = false }) { panel ->
            Draw.text(g, tr("kami_claims.common.alerts").uppercase(Format.locale), panel.x + 8, panel.y + 8, TextStyle.TITLE)
            if (alerts.isEmpty()) Draw.text(g, tr("kami_claims.alerts.none"), panel.x + 8, panel.y + 26, Palette.textMuted)
            scroll("bell-list", panel.inset(4, 22, 4, 4), itemsH) { area ->
                var y = area.y
                alerts.forEach { a -> y += alertCard(ui, Rect(area.x, y, area.w, alertHeight(a, area.w)), a, compactCard = true) }
            }
        }
    }

    fun alertHeight(a: AlertLine, w: Int) = 16 + Draw.paragraphHeight(trJson(a.body), w - 26) + (if (a.action.isNotEmpty() || a.page.isNotEmpty()) 20 else 2) + 4

    fun alertCard(ui: Ui, r: Rect, a: AlertLine, compactCard: Boolean = false): Int {
        val sev = Severity.of(a.severity)
        val box = r.withHeight(alertHeight(a, r.w) - 4)
        Draw.fill(ui.g, box, sev.tint)
        Draw.fill(ui.g, box.left(2), sev.color)
        Draw.leadIcon(ui.g, iconFor(sev), box.x + 6, box.y + 8)
        Draw.text(ui.g, Draw.fit(trJson(a.title), box.w - 40), box.x + 24, box.y + 4, TextStyle.HEADING, sev.color)
        val dismiss = Rect(box.right - 12, box.y + 3, 9, 9)
        if (ui.hovering(dismiss)) ui.cursor = Cursor.HAND
        Draw.text(ui.g, "×", dismiss.x + 1, dismiss.y, if (ui.hovering(dismiss)) Palette.text else Palette.textMuted)
        ui.tooltip("dismiss:${a.id}", dismiss, tr("kami_claims.alerts.dismiss.tooltip"))
        if (ui.pressed(dismiss) != null) { ClientClaims.prefs.dismissed += a.id; ClientClaims.savePrefs() }
        val textH = Draw.paragraph(ui.g, trJson(a.body), box.x + 24, box.y + 16, box.w - 28)
        if (a.action.isNotEmpty() || a.page.isNotEmpty()) {
            val label = trJson(a.action).ifEmpty { tr("kami_libs.common.open") }
            val bw = Draw.width(label) + 16
            if (ui.button(Rect(box.x + 24, box.y + 18 + textH, bw, 16), label, key = "alert:${a.id}")) { bellOpen = false; handle(a) }
        }
        return box.h + 4
    }

    fun handle(a: AlertLine) {
        when (a.act) {
            "deposit" -> Dialogs.money(this, true, a.args.firstOrNull()?.toLongOrNull() ?: 10)
            "accept" -> Dialogs.confirm(this, tr("kami_claims.join.confirm.title", a.args.firstOrNull() ?: ""), tr("kami_claims.join.confirm.subtitle"), Icons.PEOPLE,
                listOf(Consequence(tr("kami_claims.join.confirm.single")), Consequence(tr("kami_claims.join.confirm.laws"))), tr("kami_libs.common.join"), "accept", a.args.toTypedArray())
            "" -> {}
            else -> ClaimsStore.send(a.act, *a.args.toTypedArray())
        }
        if (a.page.isNotEmpty()) navigate(Route(a.page, focus = a.focus.ifEmpty { null }))
    }

    override fun banner(ui: Ui, r: Rect): Int {
        val info = ClaimsStore.info ?: return 0
        if (!info.delegated) return 0
        val box = r.top(20)
        Draw.box(ui.g, box, Palette.alpha(Palette.warning, 0x30), Palette.alpha(Palette.warning, 0x90))
        Draw.leadIcon(ui.g, Icons.CHAIN, box.x + 6, box.centerY)
        val exit = tr("kami_claims.delegated.exit")
        val exitW = buttonWidth(exit, Icons.CLOSE)
        Draw.text(ui.g, Draw.fit(tr("kami_claims.delegated.banner", info.name), box.w - exitW - 32), box.x + 22, box.y + 6, Palette.warning)
        if (ui.edgeButton(Rect(box.x, box.y + 2, box.w - 2, SMALL_H), exit, Icons.CLOSE, key = "exit-province")) ClaimsStore.send("view", "")
        return 20
    }

    override fun shortcut(key: Key): Boolean = when (key.code) {
        GLFW.GLFW_KEY_M -> { navigate(Route("map")); true }
        GLFW.GLFW_KEY_H -> { navigate(Route(home.page)); true }
        else -> false
    }

    private fun outcome(o: Outcome) {
        if (o.ok) {
            toast(Severity.SUCCESS, o.message.substringBefore(". ").take(70), o.message.substringAfter(". ", "").ifEmpty { null })
            return
        }
        val target = if (o.x != null && o.z != null) tr("kami_claims.toast.show_on_map") else null
        toast(if (o.reason == "COOLDOWN") Severity.WARNING else Severity.DANGER, tr("kami_claims.toast.failed"), o.message, target) {
            navigate(Route("map", mapOf("x" to o.x.toString(), "z" to o.z.toString())))
        }
    }

    fun openMapAt(x: Int, z: Int, select: Boolean = true) = navigate(Route("map", mapOf("x" to x.toString(), "z" to z.toString(), "select" to select.toString())))

    override fun closed() {
        super.closed()
        bellOpen = false
        ClaimsStore.quiet("close")
        ClaimsStore.reset()
        lastRoute = ""
    }

    companion object {
        private const val LEVEL_W = 84
        private const val LEVEL_COMPACT_W = 36
        private const val MAX_COMPLETE_TOASTS = 3
        val instance by lazy { ClaimsApp() }

        fun open() {
            Minecraft.getInstance().setScreen(AppScreen(instance, Component.translatable("kami_libs.common.country")))
        }
    }
}
