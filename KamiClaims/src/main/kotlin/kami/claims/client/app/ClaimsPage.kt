package kami.claims.client.app

import kami.claims.gov.Perm
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Info
import kami.claims.net.Snap
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.leaveGuard

abstract class ClaimsPage(val app: ClaimsApp) : Page() {
    open val sections: List<String> get() = emptyList()
    open val needsCountry: Boolean get() = true
    val snap: Snap get() = ClaimsStore.snap!!
    val info: Info? get() = ClaimsStore.info
    val limits get() = snap.limits
    val nobodyOnline get() = info?.members?.none { it.online } == true
    private val subscriptions = ArrayList<() -> Unit>()

    fun lock(perm: Perm) = app.lock(perm)
    fun can(perm: Perm) = app.lock(perm) == null
    fun act(name: String, vararg args: String, key: String = name) = ClaimsStore.send(name, *args, key = key)
    fun enqueue(name: String, vararg args: String, key: String = name) = ClaimsStore.enqueue(name, *args, key = key)
    fun pending(key: String) = ClaimsStore.isPending(key)
    fun onSuccess(key: String, action: () -> Unit) = subscribe(ClaimsStore.onOutcome { if (it.ok && it.key == key) action() })

    fun subscribe(vararg unsubscribers: () -> Unit) {
        subscriptions += unsubscribers
    }

    override fun disposed() {
        subscriptions.forEach { it() }
        subscriptions.clear()
    }

    private val guard = leaveGuard(app)

    protected fun guardLeave(next: Route, dirty: Boolean, save: (() -> Unit)?, saveReason: String? = null, discard: () -> Unit) = app.forceLeave || guard.allow(next, dirty, save, saveReason, discard)

    fun stale(section: String) = ClaimsStore.stale(section)
    fun loading(section: String) = !ClaimsStore.loaded(section) || stale(section)
}
