package kami.libs.ui

object AutoRefresh {
    const val PERIOD = 10_000L

    @Volatile
    var allowed = true
        private set

    @Volatile
    var global = true
        private set

    val active get() = allowed && UiPrefs.prefs.autoRefresh

    private var last = 0L

    fun receive(state: String) {
        allowed = state.getOrNull(0) != '0'
        global = state.getOrNull(1) != '0'
    }

    fun due(): Boolean {
        if (!active) return false
        val now = System.currentTimeMillis()
        if (now - last < PERIOD) return false
        last = now
        return true
    }
}
