package kami.libs.ui.app

import com.mojang.blaze3d.vertex.PoseStack
import kami.libs.log.Log
import kami.libs.ui.AutoRefresh
import kami.libs.ui.UiPrefs
import kami.libs.ui.anim.reveal
import kami.libs.ui.anim.anim
import kami.libs.ui.text.tr
import kami.libs.ui.core.Key
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.pin.Pins
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.widget.emptyState
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.iconButton
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.roundToInt

private val TOPBAR_H: Int get() = Metrics.topbarH
private const val PAGE_SHIFT = 10
private const val PAGE_VEIL = 0.6f

abstract class KamiApp {
    val ui = Ui()
    val toasts = Toasts()
    private val pages = HashMap<String, Page>()
    private val dialogs = ArrayList<Dialog>()
    private val failed = HashSet<Class<*>>()
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
    private var pageVisit = 0L
    private var pageDirection = 1

    private var navPrefs: NavPrefs? = null
    private var navSave: () -> Unit = {}
    private val localGroups = mutableSetOf<String>()
    val collapsedGroups: MutableSet<String> get() = navPrefs?.collapsedGroups ?: localGroups
    private fun collapseChanged() = navSave()

    protected fun bind(prefs: NavPrefs, save: () -> Unit) {
        navPrefs = prefs
        navSave = save
        railCollapsed = prefs.railCollapsed
    }

    open val module: String? get() = null
    private val sidebar = Sidebar(this)

    private val navMemo = Memo()
    protected open val navKey: Any? get() = null
    protected abstract fun buildNav(): List<NavGroup>
    fun nav(): List<NavGroup> = navMemo.of(Format.locale, navKey) { buildNav() }
    abstract fun create(id: String): Page
    fun page(id: String): Page = pages.getOrPut(id) { create(id) }
    fun dropPages() { pages.values.forEach(Page::disposed); pages.clear() }
    abstract fun topBar(ui: Ui, r: Rect)
    open fun banner(ui: Ui, r: Rect): Int = 0
    open fun shortcut(key: Key): Boolean = false
    open fun beforeFrame() {}
    open fun autoRefresh() {}

    val dialogOpen get() = dialogs.isNotEmpty()
    val compact get() = window.w < 560
    val collapsibleRail: Boolean get() = navPrefs != null
    var railCollapsed = false
        private set
    internal val rail get() = compact || railCollapsed

    fun open(dialog: Dialog) {
        dialogs += dialog
        UiSound.open()
    }

    fun toast(severity: Severity, title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) =
        toasts.push(Toast(severity, title, body, action, onAction))

    private var refused: (() -> Unit)? = null

    fun resume() = refused?.also { refused = null }?.invoke()

    fun navigate(next: Route, record: Boolean = true, sound: Boolean = true, direction: Int = 1): Boolean {
        if (next == route) return true
        if (route.page.isNotEmpty() && !page(route.page).leaving(next)) {
            refused = { navigate(next, record, sound, direction) }
            return false
        }
        if (record && route.page.isNotEmpty()) { history.addLast(route); future.clear() }
        while (history.size > 40) history.removeFirst()
        route = next
        pageVisit++
        pageDirection = direction
        groupOf(next.page)?.let { if (collapsedGroups.remove(it.id)) collapseChanged() }
        sidebar.reveal(next.page)
        focusTarget = next.focus
        focusSince = System.currentTimeMillis()
        page(next.page).opened(next)
        if (sound) UiSound.page()
        return true
    }

    fun back() {
        val previous = history.removeLastOrNull() ?: return
        val current = route
        if (navigate(previous, record = false, direction = -1)) future.addLast(current) else { history.addLast(previous); refused = ::back }
    }

    fun forward() {
        val upcoming = future.removeLastOrNull() ?: return
        val current = route
        if (navigate(upcoming, record = false)) history.addLast(current) else { future.addLast(upcoming); refused = ::forward }
    }

    fun isFocus(key: String) = focusTarget == key && System.currentTimeMillis() - focusSince < 4000

    fun groupOf(pageId: String) = nav().firstOrNull { g -> g.items.any { it.page == pageId } }

    fun render(g: GuiGraphics, mx: Int, my: Int, width: Int, height: Int) {
        if (route.page.isEmpty()) navigate(home, record = false, sound = false)
        beforeFrame()
        if (AutoRefresh.due()) autoRefresh()
        ui.frame(g, mx, my, width, height) { frame(ui.screen.w, ui.screen.h) }
    }

    private fun frame(width: Int, height: Int) {
        val (w, h) = WindowSize.of(width, height, UiPrefs.prefs.windowLarge)
        window = Rect((width - w) / 2, (height - h) / 2, w, h)
        Draw.shadow(ui.g, window, 2)
        Draw.sprite(ui.g, Sprites.WINDOW, window)
        val top = window.top(TOPBAR_H + 2).inset(2, 2, 2, 0)
        Draw.sprite(ui.g, Sprites.TOPBAR, top)
        ui.anchor("topbar", top)
        topBar(ui, top)
        val body = window.dropTop(TOPBAR_H + 2).inset(2, 0, 2, 2)
        val sideW = ui.anim("rail:width", if (rail) 22f else 108f, 16f).roundToInt()
        val side = body.left(sideW)
        sidebar.draw(side)
        var area = body.dropLeft(sideW, 6).inset(0, 4, 5, 4)
        val bannerH = banner(ui, area)
        if (bannerH > 0) area = area.dropTop(bannerH, 4)
        val current = page(route.page)
        val crumbs = area.top(CRUMBS_H)
        breadcrumbs(crumbs, current)
        content = area.dropTop(CRUMBS_H, 5)
        ui.anchor("content", content)
        val appear = ui.reveal("page", pageVisit)
        ui.scope(route.page) {
            if (appear >= 1f) drawPage(current, content)
            else ui.clip(content) { drawPage(current, content.slideIn(appear, PAGE_SHIFT * pageDirection, 0)) }
        }
        Draw.veilBox(ui.g, content, appear, strength = PAGE_VEIL)
        ui.overlay(4) { Pins.renderEditable(ui) }
        ui.overlay(NOTICE_LAYER) { toasts.draw(ui, Rect(window.x, window.y + TOPBAR_H + 4, window.w - 8, window.h)) }
        dialogs.removeAll { !it.open }
        dialogs.lastOrNull()?.let { d -> ui.overlay(10) { ui.scope("dialog:${System.identityHashCode(d)}") { d.draw(ui, ui.screen) } } }
        help?.let { h -> ui.overlay(15) { h.draw(ui) { help = null } } }
        tour?.let { t -> if (t.done) tour = null else ui.overlay(20) { t.draw(ui, this) } }
        if (!ui.typing && !dialogOpen && tour == null && help == null) keys()
    }

    private fun drawPage(page: Page, r: Rect) {
        val pose = ui.g.pose()
        val saved = pose.last()
        try {
            val lock = nav().firstNotNullOfOrNull { g -> g.items.firstOrNull { it.page == route.page } }?.lock()
            if (lock != null) ui.emptyState(r, page.title, lock, key = "locked:${route.page}") else page.draw(ui, r)
        } catch (e: Exception) {
            pageFailed(page, r, e, saved)
        } catch (e: LinkageError) {
            pageFailed(page, r, e, saved)
        } catch (e: StackOverflowError) {
            pageFailed(page, r, e, saved)
        }
    }

    private fun pageFailed(page: Page, r: Rect, e: Throwable, saved: PoseStack.Pose) {
        val pose = ui.g.pose()
        while (pose.last() !== saved && !pose.clear()) pose.popPose()
        if (failed.add(page.javaClass)) Log.of("libs").error("Page {} failed to render", page.javaClass.name, e)
        Draw.textCentered(ui.g, tr("kami_libs.app.page_error"), r, Palette.danger)
    }

    fun isCollapsed(group: NavGroup) = !rail && group.id in collapsedGroups

    internal fun toggle(group: NavGroup) {
        if (!collapsedGroups.add(group.id)) collapsedGroups.remove(group.id)
        collapseChanged()
    }

    internal fun toggleRail() {
        railCollapsed = !railCollapsed
        navPrefs?.let { it.railCollapsed = railCollapsed; navSave() }
    }

    private fun breadcrumbs(r: Rect, current: Page) {
        var x = r.x
        val textY = Draw.textY(r)
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
        current.subtitle?.takeIf { actionsX - x > 32 }?.let { ui.fitText(x, textY, actionsX - 8 - x, "  ·  $it", Palette.textMuted) }
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

    open fun canClose(): Boolean = route.page.isEmpty() || page(route.page).leaving(Route.CLOSE)

    fun closeScreen() = Minecraft.getInstance().setScreen(null)

    fun requestClose() { if (canClose()) closeScreen() }

    open fun closed() {
        ui.close()
        dialogs.clear()
        help = null
    }
}
