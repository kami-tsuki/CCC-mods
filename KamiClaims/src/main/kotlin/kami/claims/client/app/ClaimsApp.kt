package kami.claims.client.app

import kami.claims.Rank
import kami.claims.client.ClientClaims
import kami.claims.client.app.pages.*
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.Outcome
import kami.claims.service.AlertLine
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Callout
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.Tour
import kami.libs.ui.core.Cursor
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
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.widget.badge
import kami.libs.ui.widget.button
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.iconFor
import kami.libs.ui.widget.scroll
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class ClaimsApp : KamiApp() {
    override val home get() = if (ClaimsStore.info == null) Route("welcome") else Route("dashboard")
    private val pages = HashMap<String, Page>()
    private var bellOpen = false
    private var lastRoute = ""

    init {
        ClaimsStore.onOutcome(::outcome)
    }

    private fun create(id: String): Page = when (id) {
        "welcome" -> WelcomePage(this)
        "dashboard" -> DashboardPage(this)
        "statistics" -> StatisticsPage(this)
        "map" -> MapPage(this)
        "chunks" -> ChunksPage(this)
        "plots" -> PlotsPage(this)
        "budget" -> BudgetPage(this)
        "ledger" -> LedgerPage(this)
        "citizens" -> CitizensPage(this)
        "jobs" -> JobsPage(this)
        "ranks" -> RanksPage(this)
        "protection" -> ProtectionPage(this)
        "plotlaw" -> PlotLawPage(this)
        "identity" -> IdentityPage(this)
        "relations" -> RelationsPage(this)
        "provinces" -> ProvincesPage(this)
        "world" -> WorldPage(this)
        "help" -> HelpPage(this)
        "settings" -> SettingsPage(this)
        else -> DashboardPage(this)
    }

    override fun page(id: String): Page = pages.getOrPut(id) { create(id) }

    fun lock(cap: String): String? {
        val info = ClaimsStore.info ?: return "Join or found a country"
        val snap = ClaimsStore.snap ?: return "Loading"
        if (info.delegated && cap !in DELEGABLE) return "Province self-action only"
        val min = snap.caps[cap]?.let { runCatching { Rank.valueOf(it.uppercase()) }.getOrNull() } ?: Rank.PRESIDENT
        if (ClaimsStore.rank < min) return "Need ${Vocabulary.rank(min.name).label}+"
        return null
    }

    private fun alertsFor(page: String) = visibleAlerts().filter { it.page == page }

    fun visibleAlerts(): List<AlertLine> = ClaimsStore.snap?.alerts?.filter { it.id !in ClientClaims.prefs.dismissed } ?: emptyList()

    private fun badge(page: String, extra: Int = 0): () -> NavBadge? = {
        val alerts = alertsFor(page)
        val count = alerts.size + extra
        if (count == 0) null else NavBadge(count, alerts.map { Severity.of(it.severity) }.maxByOrNull { it.ordinal } ?: Severity.INFO)
    }

    private fun needsCountry(): String? = if (ClaimsStore.info == null) "Found or join a country" else null

    override fun nav(): List<NavGroup> {
        val none = ClaimsStore.info == null
        val overview = if (none) listOf(NavItem("welcome", "Welcome", Icons.FLAG, badge("welcome")))
        else listOf(NavItem("dashboard", "Dashboard", Icons.DASHBOARD, badge("dashboard")), NavItem("statistics", "Statistics", Icons.STATS, lock = { lock("details") }))
        return listOf(
            NavGroup("Overview", overview),
            NavGroup("Territory", listOf(
                NavItem("map", "Map", Icons.MAP),
                NavItem("chunks", "Chunks", Icons.AREA, badge("chunks"), ::needsCountry),
                NavItem("plots", "Plots", Icons.HOUSE, badge("plots"), ::needsCountry)
            )),
            NavGroup("Economy", listOf(
                NavItem("budget", "Budget", Icons.SCALES, badge("budget"), { needsCountry() ?: lock("details") }),
                NavItem("ledger", "Ledger", Icons.LEDGER, lock = { needsCountry() ?: lock("details") })
            )),
            NavGroup("Society", listOf(
                NavItem("citizens", "Citizens", Icons.PEOPLE, badge("citizens"), ::needsCountry),
                NavItem("jobs", "Jobs", Icons.TOOL, badge("jobs"), ::needsCountry),
                NavItem("ranks", "Ranks", Icons.STAR, lock = ::needsCountry)
            )),
            NavGroup("Law", listOf(
                NavItem("protection", "Protection", Icons.SHIELD, lock = ::needsCountry),
                NavItem("plotlaw", "Plot law", Icons.SCROLL, lock = ::needsCountry),
                NavItem("identity", "Identity", Icons.FLAG, lock = ::needsCountry)
            )),
            NavGroup("Diplomacy", listOf(
                NavItem("relations", "Relations", Icons.HANDSHAKE, lock = ::needsCountry),
                NavItem("provinces", "Provinces", Icons.CHAIN, badge("provinces"), ::needsCountry),
                NavItem("world", "World", Icons.GLOBE)
            )),
            NavGroup("System", listOf(NavItem("help", "Help", Icons.HELP), NavItem("settings", "Settings", Icons.SETTINGS)))
        )
    }

    override fun beforeFrame() {
        val p = ClientClaims.prefs
        ui.reduceMotion = p.reduceMotion
        ui.tooltipDelay = p.tooltipDelay.toLong()
        UiSound.volume = p.sounds
        if (ClaimsStore.info == null && route.page != "welcome" && page(route.page).let { it is ClaimsPage && it.needsCountry }) navigate(Route("welcome"), record = false, sound = false)
        if (route.page != lastRoute) {
            lastRoute = route.page
            (page(route.page) as? ClaimsPage)?.let { ClaimsStore.watch(it.sections) }
        }
        if (tour == null && !ClientClaims.prefs.tourDone && ClaimsStore.info != null) startTour()
    }

    fun startTour() {
        tour = Tour(listOf(
            Callout("topbar", "Top bar", "Treasury, daily net, runway, land, citizens.", Route("dashboard")),
            Callout("bell", "Alerts", "Urgent issues and one-click actions."),
            Callout("dashboard:attention", "Attention", "Same alerts, in dashboard context."),
            Callout("nav:map", "Map", "Select, plan, preview cost, then commit."),
            Callout("nav:budget", "Budget", "Income, spend, runway, ledger."),
            Callout("nav:protection", "Protection", "Set break/place/use/open rules by land type."),
            Callout("nav:provinces", "Provinces", "Province terms and delegation controls."),
            Callout("page-help", "Page help", "Press ? for page-specific guidance.")
        )) {
            ClientClaims.prefs.tourDone = true
            ClientClaims.savePrefs()
        }
    }

    override fun topBar(ui: Ui, r: Rect) {
        val snap = ClaimsStore.snap ?: return
        val info = snap.info
        val row = Row(r.inset(4, 0, 4, 0), 4)
        val identity = row.take(if (compact) 120 else 170)
        if (info != null) {
            Flags.draw(ui.g, Rect(identity.x, identity.y + 5, 18, 13), info.color, info.flag.pattern, info.flag.emblem, info.flag.secondary)
            Draw.text(ui.g, Draw.fit(info.name, identity.w - 24), identity.x + 23, identity.y + 5, TextStyle.HEADING)
            val look = Vocabulary.rank(info.rank)
            Draw.icon(ui.g, look.icon, identity.x + 22, identity.y + 12, 8)
            Draw.text(ui.g, look.label, identity.x + 32, identity.y + 13, look.color)
        } else {
            Draw.icon(ui.g, Icons.FLAG, identity.x, identity.y + 3)
            Draw.text(ui.g, "No country", identity.x + 20, identity.y + 5, TextStyle.HEADING, Palette.textMuted)
            Draw.text(ui.g, "Nomansland", identity.x + 20, identity.y + 14, Palette.textMuted)
        }
        val close = row.takeFromRight(20)
        if (ui.iconButton(close.centered(18, 18), Icons.CLOSE, "Close  [Esc]", key = "app-close")) Minecraft.getInstance().setScreen(null)
        val bell = row.takeFromRight(20)
        bell(ui, bell.centered(18, 18))
        if (info == null) {
            kpi(ui, row.takeFromRight(110), Icons.COIN, "You carry", Format.money(snap.funds, compact = true), Palette.money, Tip.text("Coins in your bank and inventory.", "Your money"), null)
            return
        }
        val kpis = row.rest
        val cells = kpis.columns(if (compact) 3 else 5, 3)
        val net = info.income - info.upkeep - info.jobs
        val history = snap.history
        val weekAgo = history.getOrNull(history.size - 8)?.treasury
        val trend = weekAgo?.let { info.treasury - it }
        kpi(ui, cells[0], Icons.TREASURY, "Treasury", Format.money(info.treasury, compact = true), Palette.money,
            Tip("Treasury", listOf("Balance ${Format.money(info.treasury)}" to Palette.money, (trend?.let { "7d ${Format.signed(it)} ◎" } ?: "No history") to Palette.textSecondary), keys = "Open budget"), "budget", info.treasury)
        kpi(ui, cells[1], if (net >= 0) Icons.INCOME else Icons.EXPENSE, "Net / day", Format.signed(net) + " ◎", if (net >= 0) Palette.success else Palette.danger,
            Tip("Daily net", listOf("Tax +${info.income}" to Palette.success, "Upkeep -${info.upkeep}" to Palette.danger, "Wages -${info.jobs}" to Palette.danger), keys = "Open budget"), "budget")
        if (!compact) {
            val runwayColor = runwayColor(info.treasury, net)
            kpi(ui, cells[2], Icons.CLOCK, "Runway", runwayText(info.treasury, net), runwayColor,
                Tip("Runway", listOf((if (net >= 0) "Net positive" else "-${-net} ◎/day -> ${info.treasury / -net}d") to Palette.textSecondary, "Next bill ${Format.duration(info.nextBilling)} · ${Format.money(info.nextBill)}" to Palette.textMuted)), "budget")
        }
        val debt = info.claimList.count { it.debt > 0 }
        kpi(ui, cells[if (compact) 2 else 3], Icons.AREA, "Land", "${info.chunks}", if (debt > 0) Palette.danger else Palette.text,
            Tip("Land", listOf("${info.chunks} chunks · ${info.free}/${info.freeAllowed} free" to Palette.textSecondary, (if (debt > 0) "$debt debt" else "No debt") to (if (debt > 0) Palette.danger else Palette.success))), "chunks")
        if (!compact) {
            val online = info.members.count { it.online }
            kpi(ui, cells[4], Icons.PEOPLE, "Citizens", "$online/${info.members.size}", Palette.text, Tip.text("$online online · ${info.members.size} total", "Citizens"), "citizens")
        }
    }

    private fun kpi(ui: Ui, r: Rect, icon: Icon, label: String, value: String, color: Int, tip: Tip?, page: String?, flashValue: Long? = null) {
        val hover = ui.hover("kpi:$label", r)
        ui.anchor("kpi:$label", r)
        if (hover && page != null) { Draw.fill(ui.g, r.inset(0, 2), Palette.hover); ui.cursor = Cursor.HAND }
        flashValue?.let { v -> ui.flash("kpi:$label", v).takeIf { it != 0 }?.let { Draw.fill(ui.g, r.inset(0, 2), it) } }
        Draw.icon(ui.g, icon, r.x + 3, r.y + 5, 14)
        Draw.text(ui.g, Draw.fit(label.uppercase(), r.w - 20), r.x + 19, r.y + 4, Palette.textMuted)
        Draw.text(ui.g, Draw.fit(value, r.w - 20), r.x + 19, r.y + 13, color)
        tip?.let { t -> ui.tooltip("kpi:$label", r) { t } }
        if (page != null && ui.pressed(r) != null) { UiSound.click(); navigate(Route(page)) }
    }

    fun runwayText(treasury: Long, net: Long) = if (net >= 0) "∞ stable" else "${treasury / -net} days"
    fun runwayColor(treasury: Long, net: Long) = when {
        net >= 0 -> Palette.success
        treasury / -net < 3 -> Palette.danger
        treasury / -net < 14 -> Palette.warning
        else -> Palette.text
    }

    private fun bell(ui: Ui, r: Rect) {
        val alerts = visibleAlerts()
        ui.anchor("bell", r)
        if (ui.iconButton(r, Icons.BELL, if (alerts.isEmpty()) "No alerts" else "${alerts.size} alerts", key = "bell")) bellOpen = !bellOpen
        if (alerts.isNotEmpty()) {
            val worst = alerts.map { Severity.of(it.severity) }.maxBy { it.ordinal }
            ui.badge(r.right - 8, r.y - 2, alerts.size.toString(), worst)
        }
        if (!bellOpen) return
        ui.onEscape(60) { bellOpen = false }
        ui.overlay {
            val w = 250
            val itemsH = alerts.sumOf { alertHeight(it, w - 12) }
            val panel = Rect(r.right - w, r.bottom + 4, w, (itemsH + 28).coerceIn(50, 300))
            ui.block(panel)
            Draw.shadow(ui.g, panel, 2)
            Draw.sprite(ui.g, Sprites.POPOVER, panel)
            Draw.text(ui.g, "ALERTS", panel.x + 8, panel.y + 8, TextStyle.TITLE)
            if (alerts.isEmpty()) Draw.text(ui.g, "No active alerts.", panel.x + 8, panel.y + 26, Palette.textMuted)
            ui.scroll("bell-list", panel.inset(4, 22, 4, 4), itemsH) { area ->
                var y = area.y
                alerts.forEach { a -> y += alertCard(ui, Rect(area.x, y, area.w, alertHeight(a, area.w)), a, compactCard = true) }
            }
            if (ui.input.presses.any { !it.consumed && !panel.contains(it.x, it.y) && !r.contains(it.x, it.y) }) bellOpen = false
        }
    }

    fun alertHeight(a: AlertLine, w: Int) = 16 + Draw.paragraphHeight(a.body, w - 26) + (if (a.action.isNotEmpty() || a.page.isNotEmpty()) 20 else 2) + 4

    fun alertCard(ui: Ui, r: Rect, a: AlertLine, compactCard: Boolean = false): Int {
        val sev = Severity.of(a.severity)
        val box = r.withHeight(alertHeight(a, r.w) - 4)
        Draw.fill(ui.g, box, sev.tint)
        Draw.fill(ui.g, box.left(2), sev.color)
        Draw.icon(ui.g, iconFor(sev), box.x + 5, box.y + 2)
        Draw.text(ui.g, Draw.fit(a.title, box.w - 40), box.x + 24, box.y + 5, TextStyle.HEADING, sev.color)
        val dismiss = Rect(box.right - 12, box.y + 3, 9, 9)
        if (ui.hovering(dismiss)) ui.cursor = Cursor.HAND
        Draw.text(ui.g, "×", dismiss.x + 1, dismiss.y, if (ui.hovering(dismiss)) Palette.text else Palette.textMuted)
        ui.tooltip("dismiss:${a.id}", dismiss, "Hide until it changes")
        if (ui.pressed(dismiss) != null) { ClientClaims.prefs.dismissed += a.id; ClientClaims.savePrefs() }
        val textH = Draw.paragraph(ui.g, a.body, box.x + 24, box.y + 16, box.w - 28)
        if (a.action.isNotEmpty() || a.page.isNotEmpty()) {
            val label = a.action.ifEmpty { "Open" }
            val bw = Draw.width(label) + 16
            if (ui.button(Rect(box.x + 24, box.y + 18 + textH, bw, 16), label, key = "alert:${a.id}")) { bellOpen = false; handle(a) }
        }
        return box.h + 4
    }

    fun handle(a: AlertLine) {
        when (a.act) {
            "deposit" -> Dialogs.money(this, true, a.args.firstOrNull()?.toLongOrNull() ?: 10)
            "accept" -> Dialogs.confirm(this, "Join ${a.args.firstOrNull()}", "You become a citizen", Icons.PEOPLE,
                listOf(Consequence("You can only be in one country at a time."), Consequence("You follow its laws and may rent plots.")), "Join", "accept", a.args.toTypedArray())
            "" -> {}
            else -> ClaimsStore.send(a.act, *a.args.toTypedArray())
        }
        if (a.page.isNotEmpty()) navigate(Route(a.page, focus = a.focus.ifEmpty { null }))
    }

    override fun banner(ui: Ui, r: Rect): Int {
        val info = ClaimsStore.info ?: return 0
        if (!info.delegated) return 0
        val box = r.top(22)
        Draw.fill(ui.g, box, Palette.alpha(Palette.warning, 0x30))
        Draw.outline(ui.g, box, Palette.alpha(Palette.warning, 0x90))
        Draw.icon(ui.g, Icons.CHAIN, box.x + 4, box.y + 3)
        Draw.text(ui.g, Draw.fit("Delegated: ${info.name}. Editable: land, capital, tax, laws, jobs.", box.w - 130), box.x + 24, box.y + 7, Palette.warning)
        if (ui.button(Rect(box.right - 98, box.y + 2, 94, 18), "Exit province", Icons.CLOSE, key = "exit-province")) ClaimsStore.send("view", "")
        return 22
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
        val target = if (o.x != null && o.z != null) "Show on map" else null
        toast(if (o.reason == "COOLDOWN") Severity.WARNING else Severity.DANGER, "Action failed", o.message, target) {
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
        val DELEGABLE = setOf("claim", "capital", "tax", "rules", "jobs")
        val instance by lazy { ClaimsApp() }

        fun open() {
            Minecraft.getInstance().setScreen(AppScreen(instance, Component.literal("Country")))
        }
    }
}
