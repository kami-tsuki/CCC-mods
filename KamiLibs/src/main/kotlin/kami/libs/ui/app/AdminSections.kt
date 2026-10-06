package kami.libs.ui.app

class AdminSection(
    val id: String,
    val order: Int,
    val group: () -> String,
    val items: () -> List<NavItem>,
    val create: (KamiApp, String) -> Page?,
    val refresh: (String) -> Unit = {}
)

object AdminSections {
    const val MODULE = "admin"

    private val registered = ArrayList<AdminSection>()

    val sections: List<AdminSection> get() = registered.sortedBy { it.order }

    fun register(section: AdminSection) {
        val i = registered.indexOfFirst { it.id == section.id }
        if (i >= 0) registered[i] = section else registered += section
    }

    var opener: ((Route) -> Unit)? = null
    val available: Boolean get() = opener != null

    fun open(route: Route) { opener?.invoke(route) }
}
