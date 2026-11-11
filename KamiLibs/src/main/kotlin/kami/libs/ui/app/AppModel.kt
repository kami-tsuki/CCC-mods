package kami.libs.ui.app

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Severity
import kami.libs.ui.widget.Lock

data class Route(val page: String, val params: Map<String, String> = emptyMap(), val focus: String? = null) {
    fun param(key: String) = params[key]
    fun int(key: String) = params[key]?.toIntOrNull()

    companion object {
        val CLOSE = Route("\u0000close")
    }
}

class NavBadge(val count: Int, val severity: Severity)
class NavItem(val page: String, val label: String, val icon: Icon, val badge: () -> NavBadge? = { null }, val lock: () -> String? = { null }, val teaser: () -> Lock? = { null }) {
    val key = "nav:$page"
}
class NavGroup(val label: String, val items: List<NavItem>, val id: String = label) {
    val openKey = "nav-open:$id"
    val headerKey = "navgroup:$id"
}

abstract class Page {
    abstract val title: String
    open val subtitle: String? get() = null
    open val help: List<Callout> get() = emptyList()
    abstract fun draw(ui: Ui, r: Rect)
    open fun actions(ui: Ui, r: Rect) {}
    open fun actionsWidth(): Int = 0
    open fun opened(route: Route) {}
    open fun leaving(next: Route): Boolean = true
    open fun disposed() {}
}

const val NOTICE_LAYER = 30
val CRUMBS_H: Int get() = Metrics.crumbsH
val NAV_ROW_H: Int get() = Metrics.rowH

interface NavPrefs {
    var railCollapsed: Boolean
    val collapsedGroups: MutableSet<String>
}
