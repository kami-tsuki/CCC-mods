package kami.claims.client.app

import kami.claims.client.rankOf
import kami.claims.Rank
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Info
import kami.claims.net.Snap
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route

abstract class ClaimsPage(val app: ClaimsApp) : Page() {
    open val sections: List<String> get() = emptyList()
    open val needsCountry: Boolean get() = true
    val snap: Snap get() = ClaimsStore.snap!!
    val info: Info? get() = ClaimsStore.info
    val limits get() = snap.limits
    val nobodyOnline get() = info?.members?.none { it.online } == true
    private var subscriptions = emptyList<() -> Unit>()

    fun lock(cap: String) = app.lock(cap)
    fun can(cap: String) = app.lock(cap) == null
    fun act(name: String, vararg args: String, key: String = name) = ClaimsStore.send(name, *args, key = key)
    fun pending(key: String) = ClaimsStore.isPending(key)

    protected fun subscribe(vararg unsubscribers: () -> Unit) {
        subscriptions.forEach { it() }
        subscriptions = unsubscribers.toList()
    }

    override fun leaving(next: Route): Boolean {
        subscribe()
        return true
    }

    fun minRank(cap: String): Rank = snap.caps[cap]?.let(::rankOf) ?: Rank.PRESIDENT
}
