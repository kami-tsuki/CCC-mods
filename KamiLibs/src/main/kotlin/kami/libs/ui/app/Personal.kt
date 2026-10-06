package kami.libs.ui.app

import kami.libs.economy.Account

class PersonalSection(
    val id: String,
    val order: Int,
    val group: () -> String,
    val items: () -> List<NavItem>,
    val create: (KamiApp, String) -> Page?,
    val refresh: (String) -> Unit = {}
)

object Personal {
    const val MODULE = "personal"

    private val registered = ArrayList<PersonalSection>()

    val sections: List<PersonalSection> get() = registered.sortedBy { it.order }

    fun register(section: PersonalSection) {
        val i = registered.indexOfFirst { it.id == section.id }
        if (i >= 0) registered[i] = section else registered += section
    }

    var opener: ((Route) -> Unit)? = null
    val available: Boolean get() = opener != null

    fun open(route: Route) { opener?.invoke(route) }

    var account: Account? = null
}
