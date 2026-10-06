package kami.libs.ui.app

import kami.libs.ui.style.Icon

class AppModule(val id: String, val label: String, val icon: Icon, val order: Int = 100, val open: () -> Unit)

object Modules {
    private val modules = ArrayList<AppModule>()

    val all: List<AppModule> get() = modules.sortedBy { it.order }

    fun register(m: AppModule) {
        val i = modules.indexOfFirst { it.id == m.id }
        if (i >= 0) modules[i] = m else modules += m
    }
}
