package kami.essentials.client.admin

import kami.essentials.admin.PlayerDetail
import kami.libs.ui.app.AdminKit
import kami.libs.ui.app.Remote
import kami.libs.admin.Admin
import kami.libs.text.Text
import kami.libs.ui.app.AdminSections
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft

class AdminApp private constructor() : KamiApp() {
    private var lastPage: String? = null
    internal var playerUuid: String? = null
    internal val playerDetail = Remote("players.detail", PlayerDetail.serializer()) { listOfNotNull(playerUuid) }
    override val module get() = AdminSections.MODULE
    override val home = Route("dashboard")
    override val navKey get() = listOf(Admin.level, AdminSections.sections.map { it.group() to it.items().map { i -> i.page } })

    init {
        bind(AdminPrefs.prefs, AdminPrefs::save)
    }

    override fun buildNav(): List<NavGroup> {
        val groups = linkedMapOf(
            "kami_libs.common.overview" to mutableListOf(
                NavItem("dashboard", tr("kami_libs.common.dashboard"), Icons.DASHBOARD),
                NavItem("economy", tr("kami_libs.common.economy"), Icons.COIN),
                NavItem("coins", tr("kami_essentials.admin.nav.coins"), Icons.DEPOSIT),
                NavItem("bank", tr("kami_essentials.bank.group"), Icons.TREASURY),
                NavItem("journal", tr("kami_essentials.admin.nav.journal"), Icons.LEDGER)
            ),
            "kami_essentials.admin.activity" to mutableListOf(
                NavItem("activity", tr("kami_essentials.admin.activity"), Icons.STATS),
                NavItem("staff", tr("kami_essentials.admin.nav.staff"), Icons.SHIELD),
                NavItem("audit", tr("kami_essentials.admin.nav.audit"), Icons.SCROLL)
            ),
            "kami_essentials.admin.nav.players" to mutableListOf(
                NavItem("players", tr("kami_essentials.admin.nav.directory"), Icons.PEOPLE),
                NavItem("statistics", tr("kami_libs.common.statistics"), Icons.STATS),
                NavItem("player", tr("kami_libs.common.details"), Icons.PERSON)
            ),
            "kami_essentials.admin.nav.moderation" to mutableListOf(
                NavItem("bans", tr("kami_essentials.admin.nav.bans"), Icons.BAN),
                NavItem("mutes", tr("kami_essentials.admin.nav.mutes"), Icons.LOCK),
                NavItem("warns", tr("kami_essentials.admin.nav.warns"), Icons.WARNING)
            )
        )
        AdminSections.sections.forEach { s -> s.items().takeIf { it.isNotEmpty() }?.let { groups.getOrPut(s.group()) { mutableListOf() } += it } }
        return groups.map { (key, items) -> NavGroup(tr(key), items, key) }
    }

    override fun create(id: String): Page = when (id) {
        "economy" -> EconomyPage()
        "coins" -> CoinsPage(this)
        "bank" -> BankPage()
        "journal" -> JournalPage(this)
        "activity" -> ActivityPage()
        "staff" -> StaffPage()
        "audit" -> AuditPage(this)
        "players" -> DirectoryPage(this)
        "statistics" -> StatisticsPage()
        "player" -> PlayerPage(this)
        "bans" -> banPage(this)
        "mutes" -> mutePage(this)
        "warns" -> WarnPage(this)
        else -> AdminSections.sections.firstOrNull { id.startsWith(it.id + ".") }?.create(this, id) ?: DashboardPage(this)
    }

    override fun beforeFrame() {
        val page = route.page
        if (page == lastPage) return
        lastPage = page
        AdminSections.sections.firstOrNull { page.startsWith(it.id + ".") }?.refresh(page)
    }

    override fun topBar(ui: Ui, r: Rect) {
        Draw.leadIcon(ui.g, Icons.SHIELD, r.x + 6, r.centerY, Palette.brass)
        Draw.text(ui.g, tr("kami_essentials.admin.module"), r.x + 24, r.centerY - 4, TextStyle.HEADING)
        Draw.textRight(ui.g, tr("kami_essentials.admin.level.${Admin.level.name.lowercase()}"), r.right - 8, r.centerY - 4, Palette.textMuted)
    }

    fun openPlayer(uuid: String) = navigate(Route("player", mapOf("uuid" to uuid)))

    fun prefetchPlayer(uuid: String) = playerDetail.prefetch(listOf(uuid))

    companion object {
        private var current: AdminApp? = null

        fun reset() {
            current = null
            AdminData.clear()
        }

        fun open(route: Route? = null) {
            Admin.refreshLevel()
            AdminKit.epoch++
            val mc = Minecraft.getInstance()
            val app = current ?: AdminApp().also { current = it }
            if ((mc.screen as? AppScreen)?.app !== app) mc.setScreen(AppScreen(app, Text.msg("kami_essentials.admin.module")))
            route?.let { app.navigate(it) }
            AdminSections.sections.forEach { it.refresh("") }
        }
    }
}
