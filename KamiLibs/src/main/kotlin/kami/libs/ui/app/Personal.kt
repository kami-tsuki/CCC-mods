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
        set(value) {
            field = value
            accountOf = subjectArg
        }

    private var accountOf: String? = null

    val loading: Boolean get() = account == null || accountOf != subjectArg

    class Subject(val uuid: String, val name: String)

    var subject: Subject? = null

    val viewing: Boolean get() = subject != null

    val subjectArg: String get() = subject?.uuid.orEmpty()

    fun view(target: Subject?) {
        subject = target
        open(Route("dashboard"))
    }
}
