package kami.claims.client.admin

import kami.claims.admin.CountryRequests
import kami.claims.admin.NewsInsights
import kami.claims.admin.NewsRequests
import kami.claims.press.PressSettings
import kami.libs.ui.app.AdminSection
import kami.libs.ui.app.AdminSections
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Remote
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

object PressAdminSection {
    const val INSIGHTS_PAGE = "press.insights"
    const val ARTICLES_PAGE = "press.articles"
    const val COMPOSE_PAGE = "press.compose"
    const val SETTINGS_PAGE = "press.settings"
    private const val REFRESH_MS = 30_000L

    val insights = Remote(NewsRequests.INSIGHTS, NewsInsights.serializer(), REFRESH_MS)
    val settings = Remote(NewsRequests.SETTINGS, PressSettings.serializer(), REFRESH_MS)
    val names = Remote(CountryRequests.NAMES, MapSerializer(String.serializer(), String.serializer()), REFRESH_MS)

    fun register() = AdminSections.register(AdminSection("press", 40, { "kami_claims.admin.press.group" }, {
        listOf(
            NavItem(INSIGHTS_PAGE, tr("kami_claims.admin.press.insights"), Icons.STATS),
            NavItem(ARTICLES_PAGE, tr("kami_claims.admin.press.articles"), Icons.SCROLL),
            NavItem(COMPOSE_PAGE, tr("kami_claims.admin.press.compose"), Icons.EDIT),
            NavItem(SETTINGS_PAGE, tr("kami_claims.admin.press.settings"), Icons.GEAR)
        )
    }, { app, id ->
        when (id) {
            INSIGHTS_PAGE -> PressInsightsPage()
            COMPOSE_PAGE -> PressComposePage(app)
            SETTINGS_PAGE -> PressSettingsPage(app)
            else -> PressArticlesPage(app)
        }
    }))
}
