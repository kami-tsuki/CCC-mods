package kami.libs.ui.app

import kami.libs.ui.text.tr
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Key
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.widget.badge
import kami.libs.ui.widget.iconButton
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

data class Route(val page: String, val params: Map<String, String> = emptyMap(), val focus: String? = null) {
    fun param(key: String) = params[key]
    fun int(key: String) = params[key]?.toIntOrNull()
}

class NavBadge(val count: Int, val severity: Severity)
class NavItem(val page: String, val label: String, val icon: Icon, val badge: () -> NavBadge? = { null }, val lock: () -> String? = { null })
class NavGroup(val label: String, val items: List<NavItem>)

abstract class Page {
    abstract val title: String
    open val subtitle: String? get() = null
    open val help: List<Callout> get() = emptyList()
    abstract fun draw(ui: Ui, r: Rect)
    open fun actions(ui: Ui, r: Rect) {}
    open fun actionsWidth(): Int = 0
    open fun opened(route: Route) {}
    open fun leaving(next: Route): Boolean = true
}

const val TOPBAR_H = 24
const val CRUMBS_H = 16
const val NAV_ROW_H = 16

abstract class KamiApp {
    val ui = Ui()
    val toasts = Toasts()
    private val dialogs = ArrayList<Dialog>()
    private val history = ArrayDeque<Route>()
    private val future = ArrayDeque<Route>()
    var tour: Tour? = null
    var help: HelpOverlay? = null
    var window = Rect.ZERO
        private set
    var content = Rect.ZERO
        private set
    abstract val home: Route
    var route: Route = Route("")
        private set
    var focusTarget: String? = null
    private var focusSince = 0L

    abstract fun nav(): List<NavGroup>
    abstract fun page(id: String): Page
    abstract fun topBar(ui: Ui, r: Rect)
    open fun banner(ui: Ui, r: Rect): Int = 0
    open fun shortcut(key: Key): Boolean = false
    open fun beforeFrame() {}

    val dialogOpen get() = dialogs.isNotEmpty()
    val compact get() = window.w < 560

    fun open(dialog: Dialog) {
        dialogs += dialog
        UiSound.open()
    }

    fun toast(severity: Severity, title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) =
        toasts.push(Toast(severity, title, body, action, onAction))

    fun navigate(next: Route, record: Boolean = true, sound: Boolean = true) {
        if (next == route) return
        if (route.page.isNotEmpty() && !page(route.page).leaving(next)) return
        if (record && route.page.isNotEmpty()) { history.addLast(route); future.clear() }
        while (history.size > 40) history.removeFirst()
        route = next
        focusTarget = next.focus
        focusSince = System.currentTimeMillis()
        page(next.page).opened(next)
        if (sound) UiSound.page()
    }

    fun back() { history.removeLastOrNull()?.let { future.addLast(route); navigate(it, record = false) } }
    fun forward() { future.removeLastOrNull()?.let { history.addLast(route); navigate(it, record = false) } }

    fun isFocus(key: String) = focusTarget == key && System.currentTimeMillis() - focusSince < 4000

    fun groupOf(pageId: String) = nav().firstOrNull { g -> g.items.any { it.page == pageId } }

    fun render(g: GuiGraphics, mx: Int, my: Int, width: Int, height: Int) {
        if (route.page.isEmpty()) navigate(home, record = false, sound = false)
        beforeFrame()
        ui.frame(g, mx, my, width, height) { frame(width, height) }
    }

    private fun frame(width: Int, height: Int) {
        val w = min(width - 8, (width * 0.92).toInt().coerceIn(400, 900))
        val h = min(height - 8, (height * 0.92).toInt().coerceIn(260, 560))
        window = Rect((width - w) / 2, (height - h) / 2, w, h)
        Draw.shadow(ui.g, window, 2)
        Draw.sprite(ui.g, Sprites.WINDOW, window)
        val top = window.top(TOPBAR_H + 1).inset(1, 1, 1, 0)
        Draw.sprite(ui.g, Sprites.TOPBAR, top)
        ui.anchor("topbar", top)
        topBar(ui, top)
        val body = window.dropTop(TOPBAR_H + 1).inset(1, 0, 1, 1)
        val sideW = if (compact) 22 else 108
        val side = body.left(sideW)
        sidebar(side)
        var area = body.dropLeft(sideW, 6).inset(0, 4, 5, 4)
        val bannerH = banner(ui, area)
        if (bannerH > 0) area = area.dropTop(bannerH, 4)
        val current = page(route.page)
        val crumbs = area.top(CRUMBS_H)
        breadcrumbs(crumbs, current)
        content = area.dropTop(CRUMBS_H, 5)
        ui.anchor("content", content)
        ui.scope(route.page) { current.draw(ui, content) }
        ui.overlay(5) { toasts.draw(ui, Rect(window.x, window.y + TOPBAR_H + 4, window.w - 8, window.h)) }
        dialogs.removeAll { !it.open }
        dialogs.lastOrNull()?.let { d -> ui.overlay(10) { ui.scope("dialog:${d.title}") { d.draw(ui, ui.screen) } } }
        help?.let { h -> ui.overlay(15) { h.draw(ui) { help = null } } }
        tour?.let { t -> if (t.done) tour = null else ui.overlay(20) { t.draw(ui, this) } }
        if (!ui.typing && !dialogOpen && tour == null) keys()
    }

    private fun sidebar(r: Rect) {
        Draw.sprite(ui.g, Sprites.SIDEBAR, r)
        var y = r.y + 4
        nav().forEachIndexed { i, group ->
            if (!compact) {
                Draw.text(ui.g, group.label.uppercase(), r.x + 7, y + 3, Palette.textMuted)
                y += 13
            } else if (i > 0) {
                Draw.hline(ui.g, r.x + 4, y + 1, r.w - 9, Palette.borderSubtle)
                y += 4
            }
            group.items.forEach { item ->
                navItem(Rect(r.x, y, r.w - 1, NAV_ROW_H), item)
                y += NAV_ROW_H
            }
            y += 4
        }
    }

    private fun navItem(r: Rect, item: NavItem) {
        val lock = item.lock()
        val active = route.page == item.page
        val hover = ui.hover("nav:${item.page}", r)
        ui.anchor("nav:${item.page}", r)
        ui.focusable("nav:${item.page}")
        if (hover) ui.cursor = Cursor.HAND
        when {
            active -> { Draw.fill(ui.g, r, Palette.selected); Draw.fill(ui.g, r.left(2), Palette.brass) }
            hover -> Draw.fill(ui.g, r, Palette.hover)
        }
        val iconX = if (compact) r.x + (r.w - Draw.ICON) / 2 else r.x + 4
        val iconY = r.y + (r.h - Draw.ICON) / 2
        if (lock != null) Draw.tintedIcon(ui.g, item.icon, iconX, iconY, Draw.ICON, Palette.alpha(0xFFFFFF, 0x60)) else Draw.icon(ui.g, item.icon, iconX, iconY)
        var shown = item.label
        if (!compact) {
            val color = when {
                lock != null -> Palette.textDisabled
                active -> Palette.text
                else -> Palette.textSecondary
            }
            shown = Draw.fit(item.label, r.w - 42)
            Draw.text(ui.g, shown, r.x + 21, r.y + (r.h - 8) / 2, color)
            if (lock != null) Draw.tintedIcon(ui.g, Icons.LOCK, r.right - Draw.ICON - 1, iconY, Draw.ICON, Palette.textMuted)
        }
        item.badge()?.takeIf { it.count > 0 && lock == null }?.let { b ->
            val label = if (b.count > 99) "99+" else b.count.toString()
            if (compact) ui.badge(r.right - 10, r.y, label, b.severity) else ui.badge(r.right - Draw.width(label) - 9, r.y + (r.h - 10) / 2, label, b.severity)
        }
        ui.tooltip("nav:${item.page}", r) {
            when {
                lock != null -> Tip(item.label, listOf(lock to Palette.warning), Severity.WARNING, Icons.LOCK)
                compact -> Tip.text(groupOf(item.page)?.label ?: "", item.label)
                shown != item.label -> Tip.text(item.label)
                else -> null
            }
        }
        ui.focusRing("nav:${item.page}", r)
        if (lock == null && (ui.pressed(r) != null || ui.activatedByKey("nav:${item.page}"))) navigate(Route(item.page))
    }

    private fun breadcrumbs(r: Rect, current: Page) {
        var x = r.x
        val textY = r.y + (r.h - 8) / 2
        if (ui.iconButton(Rect(x, r.y, r.h, r.h), Icons.BACK, tr("kami_libs.app.back.tooltip"), enabled = history.isNotEmpty(), key = "history-back")) back()
        x += r.h
        if (ui.iconButton(Rect(x, r.y, r.h, r.h), Icons.FORWARD, tr("kami_libs.app.forward.tooltip"), enabled = future.isNotEmpty(), key = "history-forward")) forward()
        x += r.h + 6
        groupOf(route.page)?.let { g ->
            x = Draw.text(ui.g, g.label, x, textY, Palette.textMuted)
            x = Draw.text(ui.g, " / ", x, textY, Palette.textDisabled)
        }
        x = Draw.text(ui.g, current.title, x, textY, TextStyle.HEADING)
        val actionsRight = r.right - r.h - 4
        val actionsX = max(x + 8, actionsRight - current.actionsWidth())
        current.subtitle?.takeIf { actionsX - x > 32 }?.let { Draw.text(ui.g, Draw.fit("  ·  $it", actionsX - 8 - x), x, textY, Palette.textMuted) }
        val helpR = Rect(r.right - r.h, r.y, r.h, r.h)
        if (current.help.isNotEmpty() && ui.iconButton(helpR, Icons.HELP, tr("kami_libs.app.help.tooltip"), key = "page-help")) help = HelpOverlay(current.help)
        if (actionsRight > actionsX) current.actions(ui, Rect(actionsX, r.y, actionsRight - actionsX, r.h))
        Draw.hline(ui.g, r.x, r.bottom + 2, r.w, Palette.borderSubtle)
    }

    private fun keys() {
        val input = ui.input
        input.keys.filter { !it.consumed }.forEach { k ->
            val handled = when {
                k.alt && k.code == GLFW.GLFW_KEY_LEFT -> { back(); true }
                k.alt && k.code == GLFW.GLFW_KEY_RIGHT -> { forward(); true }
                k.code == GLFW.GLFW_KEY_BACKSPACE -> { back(); true }
                k.code == GLFW.GLFW_KEY_SLASH && k.shift -> { page(route.page).help.takeIf { it.isNotEmpty() }?.let { help = HelpOverlay(it) }; true }
                k.ctrl && k.code in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 -> {
                    nav().getOrNull(k.code - GLFW.GLFW_KEY_1)?.items?.firstOrNull { it.lock() == null }?.let { navigate(Route(it.page)) }
                    true
                }
                else -> shortcut(k)
            }
            if (handled) k.consumed = true
        }
    }

    open fun escape(): Boolean = ui.escape()

    open fun closed() {
        ui.close()
        dialogs.clear()
        help = null
    }
}
