package kami.libs.ui.app

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
    open fun opened(route: Route) {}
    open fun leaving(next: Route): Boolean = true
}

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
        Draw.shadow(ui.g, window, 3)
        Draw.sprite(ui.g, Sprites.WINDOW, window)
        val top = window.top(28).inset(3, 3, 3, 0)
        Draw.sprite(ui.g, Sprites.TOPBAR, top)
        ui.anchor("topbar", top)
        topBar(ui, top)
        val body = window.dropTop(28).inset(3, 2, 3, 3)
        val sideW = if (compact) 26 else 116
        val side = body.left(sideW)
        sidebar(side)
        var area = body.dropLeft(sideW, 6).inset(0, 4, 4, 2)
        val bannerH = banner(ui, area)
        if (bannerH > 0) area = area.dropTop(bannerH, 4)
        val current = page(route.page)
        val crumbs = area.top(20)
        breadcrumbs(crumbs, current)
        content = area.dropTop(20, 4)
        ui.anchor("content", content)
        ui.scope(route.page) { current.draw(ui, content) }
        ui.overlay(5) { toasts.draw(ui, Rect(window.x, window.y + 32, window.w - 8, window.h)) }
        dialogs.removeAll { !it.open }
        dialogs.lastOrNull()?.let { d -> ui.overlay(10) { ui.scope("dialog:${d.title}") { d.draw(ui, ui.screen) } } }
        help?.let { h -> ui.overlay(15) { h.draw(ui) { help = null } } }
        tour?.let { t -> if (t.done) tour = null else ui.overlay(20) { t.draw(ui, this) } }
        if (!ui.typing && !dialogOpen && tour == null) keys()
    }

    private fun sidebar(r: Rect) {
        Draw.sprite(ui.g, Sprites.SIDEBAR, r)
        var y = r.y + 4
        nav().forEach { group ->
            if (!compact) {
                Draw.text(ui.g, group.label.uppercase(), r.x + 7, y + 3, Palette.textMuted)
                y += 13
            } else y += 4
            group.items.forEach { item ->
                val row = Rect(r.x + 2, y, r.w - 4, 18)
                navItem(row, item)
                y += 19
            }
            y += 3
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
        Draw.icon(ui.g, item.icon, r.x + 3, r.y + 1)
        if (lock != null) Draw.fill(ui.g, Rect(r.x + 3, r.y + 1, 16, 16), Palette.alpha(Palette.sunken, 0x90))
        if (!compact) {
            val color = when {
                lock != null -> Palette.textDisabled
                active -> Palette.text
                else -> Palette.textSecondary
            }
            Draw.text(ui.g, Draw.fit(item.label, r.w - 44), r.x + 23, r.y + 5, color)
            if (lock != null) Draw.icon(ui.g, Icons.LOCK, r.right - 17, r.y + 1)
        }
        item.badge()?.takeIf { it.count > 0 && lock == null }?.let { b ->
            val label = if (b.count > 99) "99+" else b.count.toString()
            if (compact) ui.badge(r.right - 10, r.y, label, b.severity) else ui.badge(r.right - Draw.width(label) - 9, r.y + 4, label, b.severity)
        }
        ui.tooltip("nav:${item.page}", r) {
            when {
                lock != null -> Tip(item.label, listOf(lock to Palette.warning), Severity.WARNING, Icons.LOCK)
                compact -> Tip.text(groupOf(item.page)?.label ?: "", item.label)
                else -> null
            }
        }
        ui.focusRing("nav:${item.page}", r)
        if (lock == null && (ui.pressed(r) != null || ui.activatedByKey("nav:${item.page}"))) navigate(Route(item.page))
    }

    private fun breadcrumbs(r: Rect, current: Page) {
        var x = r.x
        if (ui.iconButton(Rect(x, r.y + 1, 18, 18), Icons.BACK, "Back  [Alt+←]", enabled = history.isNotEmpty(), key = "history-back")) back()
        x += 20
        if (ui.iconButton(Rect(x, r.y + 1, 18, 18), Icons.FORWARD, "Forward  [Alt+→]", enabled = future.isNotEmpty(), key = "history-forward")) forward()
        x += 24
        groupOf(route.page)?.let { g ->
            x = Draw.text(ui.g, g.label, x, r.y + 6, Palette.textMuted)
            x = Draw.text(ui.g, "  ›  ", x, r.y + 6, Palette.textMuted)
        }
        x = Draw.text(ui.g, current.title, x, r.y + 6, TextStyle.TITLE)
        current.subtitle?.let { Draw.text(ui.g, Draw.fit("  ·  $it", r.right - x - 120), x, r.y + 6, Palette.textMuted) }
        val helpR = Rect(r.right - 18, r.y + 1, 18, 18)
        if (current.help.isNotEmpty() && ui.iconButton(helpR, Icons.HELP, "How this page works  [?]", key = "page-help")) help = HelpOverlay(current.help)
        current.actions(ui, Rect(max(x + 8, r.right - 300), r.y, r.right - 22 - max(x + 8, r.right - 300), r.h))
        Draw.hline(ui.g, r.x, r.bottom + 1, r.w, Palette.borderSubtle)
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

    fun escape(): Boolean = ui.escape()

    open fun closed() {
        ui.close()
        dialogs.clear()
        help = null
    }
}
