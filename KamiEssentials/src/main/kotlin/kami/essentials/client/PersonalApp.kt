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
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.spinner
import net.minecraft.client.Minecraft

class PersonalApp private constructor() : KamiApp() {
    var snap: Snap? = null
    private val pending = HashMap<String, Pair<String, Long>>()
    private var lastPage: String? = null
    val ready get() = snap?.let { it.viewing.orEmpty() == Personal.subjectArg } == true
    val noAccount get() = ready && Personal.account == null
    override val module get() = Personal.MODULE
    override val home = Route("dashboard")
    override val collapsibleRail get() = true
    override val collapsedGroups get() = PersonalPrefs.prefs.collapsedGroups
    override val navKey get() = listOf(snap == null, Personal.subject, Personal.sections.map { it.items().isEmpty() })

    init {
        railCollapsed = PersonalPrefs.prefs.railCollapsed
    }

    private fun offline(): String? = if (Personal.viewing) tr("kami_essentials.personal.read_only") else if (snap == null) tr("kami_essentials.prefs.offline") else null

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

    override fun autoRefresh() {
        ClientEssentials.request("open", Personal.subjectArg)
        Personal.sections.firstOrNull { route.page.startsWith(it.id + ".") }?.refresh(route.page)
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
        val name = Personal.subject?.name ?: Minecraft.getInstance().player?.gameProfile?.name
        name?.let { Draw.textRight(ui.g, it, r.right - 8, r.centerY - 4, Palette.textMuted) }
        if (!ready) ui.spinner(r.right - 20 - Draw.width(name.orEmpty()), r.centerY - 4, Palette.textMuted)
    }

    override fun banner(ui: Ui, r: Rect): Int {
        val subject = Personal.subject ?: return 0
        val box = r.top(20)
        Draw.box(ui.g, box, Palette.alpha(Palette.warning, 0x30), Palette.alpha(Palette.warning, 0x90))
        Draw.leadIcon(ui.g, Icons.EYE, box.x + 6, box.centerY)
        val exit = tr("kami_essentials.personal.viewing.exit")
        Draw.text(ui.g, Draw.fit(tr("kami_essentials.personal.viewing", subject.name), box.w - buttonWidth(exit, Icons.CLOSE) - 32), box.x + 22, box.y + 6, Palette.warning)
        if (ui.edgeButton(Rect(box.x, box.y + 2, box.w - 2, SMALL_H), exit, Icons.CLOSE, key = "exit-subject")) Personal.view(null)
        return 20
    }

    fun set(key: String, value: String) {
        pending[key] = value to System.currentTimeMillis()
        ClientEssentials.request("set", key, value, Personal.subjectArg)
    }

    fun shown(key: String, actual: String): String {
        val (value, at) = pending[key] ?: return actual
        if (value != actual && System.currentTimeMillis() - at < PENDING_MS) return value
        pending -= key
        return actual
    }

    fun shown(key: String, actual: Boolean?): Boolean? = actual?.let { shown(key, if (it) "on" else "off") == "on" }

    fun set(key: String, on: Boolean) = set(key, if (on) "on" else "off")

    companion object {
        private const val PENDING_MS = 5_000L
        val instance by lazy { PersonalApp() }
    }
}
