package kami.essentials.client

import kami.essentials.net.Snap
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft

class PersonalApp private constructor() : KamiApp() {
    var snap: Snap? = null
    private var lastPage: String? = null
    override val module get() = Personal.MODULE
    override val home = Route("dashboard")
    override val collapsibleRail get() = true
    override val collapsedGroups get() = PersonalPrefs.prefs.collapsedGroups
    override val navKey get() = listOf(snap == null, Personal.sections.map { it.items().isEmpty() })

    init {
        railCollapsed = PersonalPrefs.prefs.railCollapsed
    }

    private fun offline(): String? = if (snap == null) tr("kami_essentials.prefs.offline") else null

    private fun group(id: String, label: String, items: List<NavItem>) = NavGroup(label, items, id, collapsible = true)

    override fun buildNav() = listOf(group("overview", tr("kami_essentials.personal.nav.overview"), listOf(NavItem("dashboard", tr("kami_essentials.personal.nav.dashboard"), Icons.DASHBOARD)))) +
        Personal.sections.mapNotNull { s -> s.items().takeIf { it.isNotEmpty() }?.let { group(s.id, tr(s.group()), it) } } +
        listOf(
            group("client", tr("kami_essentials.nav.group.client"), listOf(NavItem("interface", tr("kami_essentials.nav.interface"), Icons.BRUSH))),
            group("server", tr("kami_essentials.nav.group.server"), listOf(
                NavItem("chat", tr("kami_essentials.nav.chat"), Icons.SCROLL, lock = ::offline),
                NavItem("player", tr("kami_essentials.nav.player"), Icons.PERSON, lock = ::offline),
                NavItem("discord", tr("kami_essentials.nav.discord"), Icons.GLOBE, lock = { offline() ?: if (snap?.discordOn == true) null else tr("kami_essentials.prefs.unavailable") })
            ))
        )

    override fun create(id: String): Page = when (id) {
        "interface" -> InterfacePage()
        "chat" -> ChatPage(this)
        "player" -> PlayerPage(this)
        "discord" -> DiscordPage(this)
        else -> Personal.sections.firstOrNull { id.startsWith(it.id + ".") }?.create(this, id) ?: DashboardPage(this)
    }

    override fun beforeFrame() {
        val page = route.page
        if (page == lastPage) return
        lastPage = page
        Personal.sections.firstOrNull { page.startsWith(it.id + ".") }?.refresh(page)
    }

    override fun railChanged() {
        PersonalPrefs.prefs.railCollapsed = railCollapsed
        PersonalPrefs.save()
    }

    override fun collapseChanged() {
        PersonalPrefs.save()
    }

    override fun topBar(ui: Ui, r: Rect) {
        Draw.leadIcon(ui.g, Icons.PERSON, r.x + 6, r.centerY, Palette.brass)
        Draw.text(ui.g, tr("kami_essentials.module"), r.x + 24, r.centerY - 4, TextStyle.HEADING)
        Minecraft.getInstance().player?.let { Draw.textRight(ui.g, it.gameProfile.name, r.right - 8, r.centerY - 4, Palette.textMuted) }
    }

    fun set(key: String, value: String) = ClientEssentials.request("set", key, value)

    fun set(key: String, on: Boolean) = set(key, if (on) "on" else "off")

    companion object {
        val instance by lazy { PersonalApp() }
    }
}
