package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientNews
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.TabItem
import kami.libs.ui.widget.subTabs

class PressPage(app: ClaimsApp) : ClaimsPage(app) {
    override val needsCountry get() = false
    override val sections = listOf(ClaimsStore.WORLD)
    override val title get() = tr("kami_claims.nav.press")
    override val subtitle get() = tr("kami_claims.press.subtitle")
    private val feed = PressFeed(app)
    private val write = PressWrite(app) { tab = 0; feed.reload() }
    private var tab = 0

    init {
        subscribe(ClientNews.onResult(write::result))
    }

    override fun opened(route: Route) {
        ClientNews.poll(force = true)
        feed.reload()
        route.focus?.toLongOrNull()?.let { tab = 0; feed.open(it) }
    }

    override fun leaving(next: Route): Boolean {
        if (!write.editing) return super.leaving(next)
        val ok = guardLeave(next, write.dirty, { write.save() }) {}
        if (ok) write.leave()
        return ok && super.leaving(next)
    }

    override fun disposed() {
        write.leave()
        super.disposed()
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = ClientNews.info
        if (!info.canWrite) {
            tab = 0
            write.leave()
        }
        var body = r
        if (info.canWrite && !write.editing) {
            val tabs = listOf(TabItem(tr("kami_claims.press.tab.feed"), Icons.SCROLL), TabItem(tr("kami_claims.press.tab.write"), Icons.EDIT, info.drafts))
            ui.subTabs(r.top(CONTROL_H), tabs, tab, "press-tabs")?.let { tab = it }
            body = r.dropTop(CONTROL_H + 6)
        }
        if (tab == 0) feed.draw(ui, body) else write.draw(ui, body)
    }
}
