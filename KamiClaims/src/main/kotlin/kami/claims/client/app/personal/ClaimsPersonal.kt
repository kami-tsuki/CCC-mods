package kami.claims.client.app.personal

import kami.claims.client.store.ClaimsStore
import kami.claims.net.Act
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.PersonalSection
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft

object ClaimsPersonal {
    private const val PREFIX = "claims."
    private var wired: KamiApp? = null

    fun register() = Personal.register(PersonalSection("claims", 20, { "kami_libs.common.country" }, ::items, ::create, ::refresh))

    private fun items(): List<NavItem> {
        if (ClaimsStore.snap == null && !hasChannel()) return emptyList()
        return listOf(
            NavItem("${PREFIX}plots", tr("kami_claims.personal.plots"), Icons.HOUSE),
            NavItem("${PREFIX}debts", tr("kami_claims.personal.debts"), Icons.DEBT, { DebtsPage.badge() })
        )
    }

    private fun create(host: KamiApp, id: String): Page? {
        wire(host)
        return when (id) {
            "${PREFIX}plots" -> MyPlotsPage(host)
            "${PREFIX}debts" -> DebtsPage(host)
            else -> null
        }
    }

    private fun hasChannel() = Minecraft.getInstance().connection?.hasChannel(Act.type) == true

    private fun refresh(id: String) {
        if (hasChannel()) ClaimsStore.quiet("watch", "dashboard")
    }

    private fun wire(host: KamiApp) {
        if (wired === host) return
        wired = host
        ClaimsStore.onOutcome { o ->
            if (!host.route.page.startsWith(PREFIX)) return@onOutcome
            if (o.ok) host.toast(Severity.SUCCESS, o.message.substringBefore(". ").take(70), o.message.substringAfter(". ", "").ifEmpty { null })
            else host.toast(Severity.DANGER, tr("kami_claims.toast.failed"), o.message)
        }
    }
}
