package kami.claims.client.app

import kami.claims.Rank
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Info
import kami.claims.net.Snap
import kami.libs.ui.app.Page

abstract class ClaimsPage(val app: ClaimsApp) : Page() {
    open val sections: List<String> get() = emptyList()
    open val needsCountry: Boolean get() = true
    val snap: Snap get() = ClaimsStore.snap!!
    val info: Info? get() = ClaimsStore.info
    val limits get() = snap.limits

    fun lock(cap: String) = app.lock(cap)
    fun can(cap: String) = app.lock(cap) == null
    fun act(name: String, vararg args: String, key: String = name) = ClaimsStore.send(name, *args, key = key)
    fun pending(key: String) = ClaimsStore.isPending(key)

    fun minRank(cap: String): Rank = snap.caps[cap]?.let { runCatching { Rank.valueOf(it.uppercase()) }.getOrNull() } ?: Rank.PRESIDENT
}
